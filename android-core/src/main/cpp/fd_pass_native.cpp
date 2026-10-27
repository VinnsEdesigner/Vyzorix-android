// Passing file descriptors over a unix socket.
//
// This is the mechanism that makes the fd-capability model work: the broker
// holds a master fd (a PTY, later a USB device), and hands a duplicate to the
// Debian-side process over a socket. The receiver gets a usable descriptor
// without ever opening a device node — which matters because the receiver runs
// inside proot, where device nodes are not reliably reachable.
//
// SCM_RIGHTS is the kernel facility for this. Note that descriptors sent this
// way are *duplicated* into the receiver: the sender keeps its own and both must
// close independently.

#include "include/pty_broker.h"

#include <cerrno>
#include <cstdio>
#include <cstring>

#include <fcntl.h>
#include <poll.h>
#include <unistd.h>
#include <sys/socket.h>

namespace vyzorix::pty {
namespace {

void SetDetail(char *detail, size_t size, const char *what) {
    if (detail == nullptr || size == 0) {
        return;
    }
    snprintf(detail, size, "%s: %s", what, strerror(errno));
}

void SetMessage(char *detail, size_t size, const char *message) {
    if (detail == nullptr || size == 0) {
        return;
    }
    snprintf(detail, size, "%s", message);
}

}  // namespace

Status SendFds(int socket_fd, const int *fds, size_t fd_count, const uint8_t *payload,
               size_t payload_size, char *detail, size_t detail_size) {
    if (socket_fd < 0 || (fds == nullptr && fd_count > 0)) {
        SetMessage(detail, detail_size, "invalid argument");
        return Status::InvalidArgument;
    }

    struct msghdr msg {};
    struct iovec io {};

    // Always send at least one payload byte. A zero-length iovec is legal but
    // some kernels treat an empty message as a no-op and silently drop the
    // control data, so a single byte keeps the send unambiguous.
    uint8_t fallback = 0;
    if (payload != nullptr && payload_size > 0) {
        io.iov_base = const_cast<uint8_t *>(payload);
        io.iov_len = payload_size;
    } else {
        io.iov_base = &fallback;
        io.iov_len = 1;
    }
    msg.msg_iov = &io;
    msg.msg_iovlen = 1;

    // The control buffer must be large enough for the header plus the descriptor
    // array. CMSG_SPACE accounts for alignment.
    char control[CMSG_SPACE(sizeof(int) * 8)];
    memset(control, 0, sizeof(control));

    if (fd_count > 0) {
        if (fd_count > 8) {
            SetMessage(detail, detail_size, "too many fds for one message");
            return Status::InvalidArgument;
        }

        msg.msg_control = control;
        msg.msg_controllen = CMSG_SPACE(sizeof(int) * fd_count);

        struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
        cmsg->cmsg_level = SOL_SOCKET;
        cmsg->cmsg_type = SCM_RIGHTS;
        cmsg->cmsg_len = CMSG_LEN(sizeof(int) * fd_count);
        memcpy(CMSG_DATA(cmsg), fds, sizeof(int) * fd_count);
    }

    ssize_t sent;
    do {
        sent = sendmsg(socket_fd, &msg, 0);
    } while (sent < 0 && errno == EINTR);

    if (sent < 0) {
        SetDetail(detail, detail_size, "sendmsg");
        return Status::FdPassFailed;
    }

    return Status::Ok;
}

Status ReceiveFds(int socket_fd, int timeout_ms, FdMessage *out_message, char *detail,
                  size_t detail_size) {
    if (socket_fd < 0 || out_message == nullptr) {
        SetMessage(detail, detail_size, "invalid argument");
        return Status::InvalidArgument;
    }

    out_message->fds.clear();
    out_message->payload.clear();

    struct pollfd pfd {};
    pfd.fd = socket_fd;
    pfd.events = POLLIN;

    int ready;
    do {
        ready = poll(&pfd, 1, timeout_ms);
    } while (ready < 0 && errno == EINTR);

    if (ready < 0) {
        SetDetail(detail, detail_size, "poll");
        return Status::FdPassFailed;
    }
    if (ready == 0) {
        SetMessage(detail, detail_size, "timed out waiting for fds");
        return Status::TimedOut;
    }

    uint8_t payload[1024];
    char control[CMSG_SPACE(sizeof(int) * 8)];
    memset(control, 0, sizeof(control));

    struct iovec io {};
    io.iov_base = payload;
    io.iov_len = sizeof(payload);

    struct msghdr msg {};
    msg.msg_iov = &io;
    msg.msg_iovlen = 1;
    msg.msg_control = control;
    msg.msg_controllen = sizeof(control);

    ssize_t received;
    do {
        received = recvmsg(socket_fd, &msg, 0);
    } while (received < 0 && errno == EINTR);

    if (received < 0) {
        SetDetail(detail, detail_size, "recvmsg");
        return Status::FdPassFailed;
    }
    if (received == 0) {
        SetMessage(detail, detail_size, "socket closed by peer");
        return Status::FdPassFailed;
    }

    for (struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg); cmsg != nullptr;
         cmsg = CMSG_NXTHDR(&msg, cmsg)) {
        if (cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS) {
            continue;
        }

        size_t data_len = cmsg->cmsg_len - CMSG_LEN(0);
        size_t count = data_len / sizeof(int);

        for (size_t i = 0; i < count; i++) {
            int fd = 0;
            memcpy(&fd, CMSG_DATA(cmsg) + i * sizeof(int), sizeof(int));
            out_message->fds.push_back(fd);
        }
    }

    // The payload includes the one-byte keepalive, so copy only what is real.
    if (received > 0) {
        out_message->payload.assign(payload, payload + received);
    }

    return Status::Ok;
}

}  // namespace vyzorix::pty
