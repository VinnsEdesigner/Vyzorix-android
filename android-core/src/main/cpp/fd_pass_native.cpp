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
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

namespace vyzorix::pty {
namespace {

// Pending connections the kernel will hold for the bridge socket. Matches the
// backlog LocalServerSocket uses; the bridge caps live connections at 16 anyway.
constexpr int kListenBacklog = 50;

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

} // namespace

Status SendFds(int socket_fd, const int *fds, size_t fd_count, char *detail, size_t detail_size) {
    if (socket_fd < 0 || (fds == nullptr && fd_count > 0)) {
        SetMessage(detail, detail_size, "invalid argument");
        return Status::InvalidArgument;
    }

    struct msghdr msg {};
    struct iovec io {};

    // Always send one byte. A zero-length iovec is legal, but some kernels treat
    // an empty message as a no-op and drop the control data with it.
    uint8_t keepalive = 0;
    io.iov_base = &keepalive;
    io.iov_len = 1;
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

    ssize_t sent = -1;
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

    struct pollfd pfd {};
    pfd.fd = socket_fd;
    pfd.events = POLLIN;

    int ready = -1;
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

    ssize_t received = -1;
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

    return Status::Ok;
}

Status BindListenSocket(const std::string &path, int *out_fd, char *detail, size_t detail_size) {
    if (path.empty() || out_fd == nullptr) {
        SetMessage(detail, detail_size, "invalid argument");
        return Status::InvalidArgument;
    }

    // AF_UNIX path names are bounded by sun_path (108 bytes on Linux, including
    // the NUL). A longer path would be silently truncated by the kernel, so it is
    // rejected rather than bound to a surprising name.
    if (path.size() >= sizeof(sockaddr_un::sun_path)) {
        SetMessage(detail, detail_size, "socket path too long");
        return Status::InvalidArgument;
    }

    // A crashed process leaves the node behind and bind() over it fails with
    // EADDRINUSE, so remove any stale node first.
    unlink(path.c_str());

    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        SetDetail(detail, detail_size, "socket");
        return Status::SyscallFailed;
    }

    sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    memcpy(addr.sun_path, path.c_str(), path.size());

    if (bind(fd, reinterpret_cast<sockaddr *>(&addr), sizeof(addr)) != 0) {
        SetDetail(detail, detail_size, "bind");
        close(fd);
        return Status::SyscallFailed;
    }

    // The file mode is the access control for a filesystem socket. Android's
    // setReadable/setWritable are a best-effort on top; setting 0600 here means
    // the node is owner-only from the moment it exists, with no window where it
    // is world-accessible.
    if (chmod(path.c_str(), S_IRUSR | S_IWUSR) != 0) {
        SetDetail(detail, detail_size, "chmod");
        close(fd);
        unlink(path.c_str());
        return Status::SyscallFailed;
    }

    if (listen(fd, kListenBacklog) != 0) {
        SetDetail(detail, detail_size, "listen");
        close(fd);
        unlink(path.c_str());
        return Status::SyscallFailed;
    }

    *out_fd = fd;

    return Status::Ok;
}

} // namespace vyzorix::pty
