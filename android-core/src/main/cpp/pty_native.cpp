// PTY allocation and I/O.
//
// Implements the syscall sequence.
// Nothing here throws; every path returns a Status and fills `detail`.
//
// The important property of this file: the PTY is allocated by *this* process
// (the app), not by anything inside proot. That is what makes terminal support
// independent of what the container can see.

#include "include/pty_broker.h"

#include <cerrno>
#include <cstdio>
#include <cstring>

#include <cstdlib>
#include <fcntl.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

namespace vyzorix::pty {
namespace {

// Writes "name: strerror" into the caller's detail buffer. Always NUL-terminated.
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

Status OpenPty(PtyPair *out_pair, char *detail, size_t detail_size) {
    if (out_pair == nullptr) {
        SetMessage(detail, detail_size, "out_pair is null");
        return Status::InvalidArgument;
    }

    // O_NOCTTY: we do not want this fd to become our controlling terminal. The
    // child sets its own up deliberately in SpawnOnPty.
    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) {
        SetDetail(detail, detail_size, "posix_openpt");
        return Status::SyscallFailed;
    }

    if (grantpt(master) != 0) {
        SetDetail(detail, detail_size, "grantpt");
        close(master);
        return Status::SyscallFailed;
    }

    if (unlockpt(master) != 0) {
        SetDetail(detail, detail_size, "unlockpt");
        close(master);
        return Status::SyscallFailed;
    }

    // ptsname is not thread-safe (it uses a static buffer). Copy immediately.
    const char *slave_name = ptsname(master);
    if (slave_name == nullptr) {
        SetDetail(detail, detail_size, "ptsname");
        close(master);
        return Status::SyscallFailed;
    }
    std::string slave_path(slave_name);

    int slave = open(slave_path.c_str(), O_RDWR | O_NOCTTY);
    if (slave < 0) {
        SetDetail(detail, detail_size, "open slave");
        close(master);
        return Status::SyscallFailed;
    }

    out_pair->master = master;
    out_pair->slave = slave;
    out_pair->slave_path = slave_path;

    return Status::Ok;
}

Status SetWindowSize(int fd, WindowSize size, char *detail, size_t detail_size) {
    if (fd < 0) {
        SetMessage(detail, detail_size, "invalid fd");
        return Status::InvalidArgument;
    }

    struct winsize ws {};
    ws.ws_row = size.rows;
    ws.ws_col = size.cols;

    if (ioctl(fd, TIOCSWINSZ, &ws) != 0) {
        SetDetail(detail, detail_size, "TIOCSWINSZ");
        return Status::SyscallFailed;
    }

    return Status::Ok;
}

Status ReadMaster(int master_fd, uint8_t *buffer, size_t buffer_size, int timeout_ms,
                  ReadResult *out_result, char *detail, size_t detail_size) {
    if (master_fd < 0 || buffer == nullptr || out_result == nullptr || buffer_size == 0) {
        SetMessage(detail, detail_size, "invalid argument");
        return Status::InvalidArgument;
    }

    out_result->bytes = 0;
    out_result->timed_out = false;
    out_result->eof = false;

    // Poll before reading. A blocking read on a PTY master hangs forever when the
    // peer produces nothing, which is the normal state of an idle shell.
    struct pollfd pfd {};
    pfd.fd = master_fd;
    pfd.events = POLLIN;

    int ready = -1;
    do {
        ready = poll(&pfd, 1, timeout_ms);
    } while (ready < 0 && errno == EINTR);

    if (ready < 0) {
        SetDetail(detail, detail_size, "poll");
        return Status::SyscallFailed;
    }

    if (ready == 0) {
        out_result->timed_out = true;
        return Status::Ok;
    }

    ssize_t n = -1;
    do {
        n = read(master_fd, buffer, buffer_size);
    } while (n < 0 && errno == EINTR);

    if (n < 0) {
        // EIO on a PTY master means the slave side is gone; that is EOF, not an
        // error worth surfacing.
        if (errno == EIO) {
            out_result->eof = true;
            return Status::Ok;
        }
        SetDetail(detail, detail_size, "read");
        return Status::SyscallFailed;
    }

    if (n == 0) {
        out_result->eof = true;
        return Status::Ok;
    }

    out_result->bytes = static_cast<size_t>(n);
    return Status::Ok;
}

Status WriteMaster(int master_fd, const uint8_t *data, size_t size, size_t *out_written,
                   char *detail, size_t detail_size) {
    if (master_fd < 0 || (data == nullptr && size > 0) || out_written == nullptr) {
        SetMessage(detail, detail_size, "invalid argument");
        return Status::InvalidArgument;
    }

    *out_written = 0;

    while (*out_written < size) {
        ssize_t n = write(master_fd, data + *out_written, size - *out_written);
        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }
            // A non-blocking master can report EAGAIN; the caller should retry
            // after polling for writability. Report it as a timeout rather than
            // a hard failure so the caller can decide.
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                SetDetail(detail, detail_size, "write would block");
                return Status::TimedOut;
            }
            SetDetail(detail, detail_size, "write");
            return Status::SyscallFailed;
        }
        *out_written += static_cast<size_t>(n);
    }

    return Status::Ok;
}

Status ReapChild(pid_t pid, bool *out_reaped, int32_t *out_exit_code, char *detail,
                 size_t detail_size) {
    if (pid <= 0 || out_reaped == nullptr || out_exit_code == nullptr) {
        SetMessage(detail, detail_size, "invalid argument");
        return Status::InvalidArgument;
    }

    *out_reaped = false;
    *out_exit_code = 0;

    int status = 0;
    pid_t reaped = -1;
    do {
        reaped = waitpid(pid, &status, WNOHANG);
    } while (reaped < 0 && errno == EINTR);

    if (reaped < 0) {
        if (errno == ECHILD) {
            // Already reaped by someone else; treat as done.
            *out_reaped = true;
            return Status::Ok;
        }
        SetDetail(detail, detail_size, "waitpid");
        return Status::SyscallFailed;
    }

    if (reaped == 0) {
        return Status::Ok; // Still running.
    }

    *out_reaped = true;
    if (WIFEXITED(status)) {
        *out_exit_code = WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        // Encode a signal death as a negative code so the caller can tell the
        // difference from a normal exit.
        *out_exit_code = -WTERMSIG(status);
    }

    return Status::Ok;
}

Status KillChild(pid_t pid, char *detail, size_t detail_size) {
    if (pid <= 0) {
        SetMessage(detail, detail_size, "invalid pid");
        return Status::InvalidArgument;
    }

    // ESRCH means it already exited; that is success for our purposes.
    if (kill(pid, SIGKILL) != 0 && errno != ESRCH) {
        SetDetail(detail, detail_size, "kill");
        return Status::SyscallFailed;
    }

    int status = 0;
    pid_t reaped = -1;
    do {
        reaped = waitpid(pid, &status, 0);
    } while (reaped < 0 && errno == EINTR);

    if (reaped < 0 && errno != ECHILD) {
        SetDetail(detail, detail_size, "waitpid after kill");
        return Status::SyscallFailed;
    }

    return Status::Ok;
}

} // namespace vyzorix::pty
