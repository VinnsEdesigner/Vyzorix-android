// PTY broker — native side.
//
// This header is the contract between the JNI bridge (jni_bridge.cpp), the PTY
// implementation (pty_native.cpp), the exec helper (pty_exec.cpp) and the fd
// passing code (fd_pass_native.cpp).
//
// Error model
// -----------
// Nothing here throws. Every entry point returns an explicit status and writes a
// detail message into a caller-provided buffer. C++ exceptions must never cross
// the JNI boundary into the JVM (that is undefined behaviour), so the JNI layer
// translates these statuses into a Kotlin sealed type rather than throwing.
//
// See doc/ANDROID_SERVICE_ARCHITECTURE.md §4.1 for the sequence this implements.

#ifndef VYZORIX_PTY_BROKER_H
#define VYZORIX_PTY_BROKER_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace vyzorix::pty {

// Status returned by every entry point. Success is the zero value so a caller
// can test `!= Ok` without naming the success case.
enum class Status : int32_t {
    Ok = 0,
    // A syscall failed; `detail` holds the errno text.
    SyscallFailed = 1,
    // A supplied argument was invalid (null, negative, too long).
    InvalidArgument = 2,
    // The requested session does not exist.
    NoSuchSession = 3,
    // The per-device session cap was reached.
    TooManySessions = 4,
    // The session already exists.
    SessionExists = 5,
    // The session is closed and cannot be used.
    SessionClosed = 6,
    // An operation timed out rather than blocking.
    TimedOut = 7,
    // The child process could not be executed.
    ExecFailed = 8,
    // fd passing failed (SCM_RIGHTS).
    FdPassFailed = 9,
    // An unexpected internal failure.
    Internal = 10,
};

// Human-readable name for a status, for logs and error messages.
const char *StatusName(Status status);

// A PTY pair.
//
// Ownership: the caller owns both fds and must close them. `master` is the end
// the broker reads and writes; `slave` is only needed between allocation and the
// fork, and should be closed in the parent immediately after.
struct PtyPair {
    int master = -1;
    int slave = -1;
    // Path of the slave, e.g. "/dev/pts/3". Informational.
    std::string slave_path;
};

// Terminal size, in character cells.
struct WindowSize {
    uint16_t rows = 24;
    uint16_t cols = 80;
};

// Allocates a PTY pair: posix_openpt -> grantpt -> unlockpt -> open(slave).
//
// This is the step the whole terminal feature rests on. If the app's SELinux
// domain cannot open /dev/ptmx, this fails with SyscallFailed and the errno
// detail names the reason.
Status OpenPty(PtyPair *out_pair, char *detail, size_t detail_size);

// Applies a window size to a pty fd via TIOCSWINSZ. Used both before exec (to
// give the shell the right geometry from its first prompt) and on resize.
Status SetWindowSize(int fd, WindowSize size, char *detail, size_t detail_size);

// Reads the current window size from a pty fd via TIOCGWINSZ.
Status GetWindowSize(int fd, WindowSize *out_size, char *detail, size_t detail_size);

// What to execute on the slave end of the PTY.
struct ExecRequest {
    // Absolute path of the binary. Must be executable by this app's UID.
    std::string path;
    // argv[0] and onwards. If empty, `path` is used as argv[0].
    std::vector<std::string> argv;
    // Environment for the child. If empty, a minimal sane environment is used.
    std::vector<std::string> env;
    // Working directory. Empty means "/".
    std::string working_dir;
    WindowSize window;
};

// Forks, and in the child: setsid, TIOCSCTTY on the slave, dup2 the slave onto
// 0/1/2, then execve. The parent closes its copy of the slave and returns the
// child pid.
//
// The child does not return on success. If exec fails the child exits with
// status ExecFailed (see kExecFailedExitCode) and the parent reports
// ExecFailed with the errno detail, so a bad path is distinguishable from a
// shell that simply produced no output.
constexpr int kExecFailedExitCode = 100;

Status SpawnOnPty(const PtyPair &pair, const ExecRequest &request, pid_t *out_pid,
                  char *detail, size_t detail_size);

// Result of a bounded read from the master fd.
struct ReadResult {
    // Bytes read. Zero means the read timed out, not EOF (see `timed_out`).
    size_t bytes = 0;
    // True when the timeout elapsed with no data available.
    bool timed_out = false;
    // True when the slave side closed (EOF).
    bool eof = false;
};

// Reads from the master fd, waiting at most `timeout_ms`.
//
// A PTY master must never be read with a blocking read: a shell that produces no
// output would hang the calling thread indefinitely. This polls first.
Status ReadMaster(int master_fd, uint8_t *buffer, size_t buffer_size, int timeout_ms,
                  ReadResult *out_result, char *detail, size_t detail_size);

// Writes to the master fd, retrying on partial writes.
Status WriteMaster(int master_fd, const uint8_t *data, size_t size, size_t *out_written,
                   char *detail, size_t detail_size);

// Reaps a child without blocking. Returns Ok with *out_reaped = false when the
// child is still running.
//
// A live shell does not exit on its own, so a blocking waitpid here would hang
// forever. Callers that want to end a session should call KillSession first.
Status ReapChild(pid_t pid, bool *out_reaped, int32_t *out_exit_code, char *detail,
                 size_t detail_size);

// Sends SIGKILL and reaps. Safe to call on an already-dead pid.
Status KillChild(pid_t pid, char *detail, size_t detail_size);

// fd passing over a unix socket (SCM_RIGHTS).
//
// This is how a capability crosses the process boundary: the broker holds the
// master fd, and hands a duplicate to the Debian-side process over a socket.
// The receiving process gets a working fd without ever opening a device node.
struct FdMessage {
    // The descriptors received. The caller owns them.
    std::vector<int> fds;
    // Any non-fd payload (currently unused, reserved for a small header).
    std::vector<uint8_t> payload;
};

// Sends one or more fds, plus optional payload bytes, over a connected unix
// socket.
Status SendFds(int socket_fd, const int *fds, size_t fd_count, const uint8_t *payload,
               size_t payload_size, char *detail, size_t detail_size);

// Receives fds and payload from a connected unix socket, waiting at most
// `timeout_ms`. The caller owns any descriptors returned.
Status ReceiveFds(int socket_fd, int timeout_ms, FdMessage *out_message, char *detail,
                  size_t detail_size);

}  // namespace vyzorix::pty

#endif  // VYZORIX_PTY_BROKER_H
