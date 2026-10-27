// Spawning a process on the slave end of a PTY.
//
// This is the exec half of doc/ANDROID_SERVICE_ARCHITECTURE.md §4.1. The child
// becomes a session leader and takes the PTY slave as its controlling terminal,
// so the process it execs sees a normal tty on fd 0/1/2.
//
// Why this lives in native code rather than Kotlin: ProcessBuilder gives no way
// to make the child a session leader with a specific controlling terminal, and
// the PTY handoff requires the fd to survive the fork. Both are only expressible
// here.

#include "include/pty_broker.h"

#include <cerrno>
#include <cstdio>
#include <cstring>

#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

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

// A minimal environment for the child. Deliberately small: the caller supplies
// anything else. TERM matters because the shell reads it to pick line-editing
// behaviour.
std::vector<std::string> DefaultEnvironment() {
    return {
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "HOME=/root",
    };
}

// Builds a NULL-terminated argv from the request. Caller frees the array (not
// the strings, which point into the request).
char **BuildArgv(const ExecRequest &request) {
    size_t count = request.argv.empty() ? 1 : request.argv.size();
    char **argv = static_cast<char **>(calloc(count + 1, sizeof(char *)));
    if (argv == nullptr) {
        return nullptr;
    }

    if (request.argv.empty()) {
        argv[0] = const_cast<char *>(request.path.c_str());
    } else {
        for (size_t i = 0; i < count; i++) {
            argv[i] = const_cast<char *>(request.argv[i].c_str());
        }
    }

    return argv;
}

char **BuildEnvp(const std::vector<std::string> &env) {
    char **envp = static_cast<char **>(calloc(env.size() + 1, sizeof(char *)));
    if (envp == nullptr) {
        return nullptr;
    }

    for (size_t i = 0; i < env.size(); i++) {
        envp[i] = const_cast<char *>(env[i].c_str());
    }

    return envp;
}

} // namespace

Status SpawnOnPty(const PtyPair &pair, const ExecRequest &request, pid_t *out_pid, char *detail,
                  size_t detail_size) {
    if (pair.master < 0 || pair.slave < 0 || out_pid == nullptr || request.path.empty()) {
        SetMessage(detail, detail_size, "invalid argument");
        return Status::InvalidArgument;
    }

    // Apply the requested geometry before the fork, so the child's first prompt
    // is already the right size rather than resizing a moment later.
    if (request.window.rows > 0 && request.window.cols > 0) {
        Status s = SetWindowSize(pair.master, request.window, detail, detail_size);
        if (s != Status::Ok) {
            return s;
        }
    }

    std::vector<std::string> env = request.env.empty() ? DefaultEnvironment() : request.env;

    // argv/envp are built before the fork: after the fork, in the child, only
    // async-signal-safe calls are permitted before execve, and allocation is not
    // one of them.
    char **argv = BuildArgv(request);
    if (argv == nullptr) {
        SetMessage(detail, detail_size, "out of memory building argv");
        return Status::Internal;
    }

    char **envp = BuildEnvp(env);
    if (envp == nullptr) {
        free(argv);
        SetMessage(detail, detail_size, "out of memory building envp");
        return Status::Internal;
    }

    pid_t pid = fork();
    if (pid < 0) {
        SetDetail(detail, detail_size, "fork");
        free(argv);
        free(envp);
        return Status::SyscallFailed;
    }

    if (pid == 0) {
        // ---- Child. Only async-signal-safe calls from here to execve. ----

        // A new session with no controlling terminal yet.
        if (setsid() < 0) {
            _exit(kExecFailedExitCode + 1);
        }

        // Take the PTY slave as the controlling terminal for that session. This
        // is what makes the shell behave as an interactive tty.
        if (ioctl(pair.slave, TIOCSCTTY, 0) != 0) {
            _exit(kExecFailedExitCode + 2);
        }

        if (dup2(pair.slave, STDIN_FILENO) < 0 || dup2(pair.slave, STDOUT_FILENO) < 0 ||
            dup2(pair.slave, STDERR_FILENO) < 0) {
            _exit(kExecFailedExitCode + 3);
        }

        // The child has no use for the master or the original slave fd.
        if (pair.slave > STDERR_FILENO) {
            close(pair.slave);
        }
        close(pair.master);

        if (!request.working_dir.empty()) {
            if (chdir(request.working_dir.c_str()) != 0) {
                _exit(kExecFailedExitCode + 4);
            }
        }

        execve(request.path.c_str(), argv, envp);

        // execve only returns on failure. Encode the errno so the parent can
        // distinguish "binary missing" from "permission denied" from a shell
        // that simply exited.
        _exit(kExecFailedExitCode);
    }

    // ---- Parent ----
    free(argv);
    free(envp);

    // The parent must not keep the slave open: while any process holds it, the
    // master will not report EOF when the child dies.
    close(pair.slave);

    *out_pid = pid;
    return Status::Ok;
}

} // namespace vyzorix::pty
