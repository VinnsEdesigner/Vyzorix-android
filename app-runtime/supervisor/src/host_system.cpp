// The real [Host]: fork/exec, waitpid, signals, the clock.
//
// Everything platform-specific lives here so [Manager] is pure policy. The one
// detail worth stating: an app is started in its **own process group**
// (`setpgid(0, 0)`), so stopping it signals the group and takes its children with
// it. Without that, `python3 -m http.server` would leave the interpreter alive
// after the supervisor stopped the shell that started it, and the port would stay
// bound.

#include "vyzorix/host_system.h"

#include <cerrno>
#include <csignal>
#include <cstring>

#include <fcntl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include "vyzorix/apps.h"

namespace vsup {
namespace {

/** Appends the environment the app declared, on top of ours. */
void ApplyEnv(const std::map<std::string, std::string> &env) {
    for (const auto &entry : env) {
        ::setenv(entry.first.c_str(), entry.second.c_str(), 1);
    }
}

} // namespace

int SystemHost::Spawn(const std::string &cmd, const std::string &dir,
                      const std::map<std::string, std::string> &env, const std::string &logPath) {
    const pid_t pid = ::fork();
    if (pid < 0) {
        return -1;
    }

    if (pid == 0) {
        // ---- Child. Only async-signal-safe calls until exec. ----

        // A new process group, so a stop can signal the whole tree.
        ::setpgid(0, 0);

        // The log is opened FIRST, before anything that can fail. A chdir into a
        // missing directory must still leave a message in the app's log; opening it
        // afterwards loses the only evidence of why the app did not start.
        const int log = ::open(logPath.c_str(), O_WRONLY | O_CREAT | O_APPEND, 0640);
        if (log >= 0) {
            ::dup2(log, STDOUT_FILENO);
            ::dup2(log, STDERR_FILENO);
            if (log > STDERR_FILENO) {
                ::close(log);
            }
        }

        if (!dir.empty() && ::chdir(dir.c_str()) != 0) {
            // _exit is async-signal-safe; stdio may not be. A plain message via
            // write(2) is what makes the failure visible in the app's log.
            const char *message = "supervisor: cannot enter the app directory\n";
            ssize_t ignored = ::write(STDERR_FILENO, message, std::strlen(message));
            (void)ignored;
            _exit(127);
        }

        ApplyEnv(env);

        // stdin from /dev/null: an app that reads stdin must not consume the
        // supervisor's, and a terminal is not available anyway.
        const int null = ::open("/dev/null", O_RDONLY);
        if (null >= 0) {
            ::dup2(null, STDIN_FILENO);
            if (null > STDERR_FILENO) {
                ::close(null);
            }
        }

        // `/bin/sh -c` so a cmd may use shell syntax (`&&`, redirection, a pipeline)
        // — the registry stores a command line, not an argv.
        ::execl("/bin/sh", "sh", "-c", cmd.c_str(), static_cast<char *>(nullptr));

        _exit(127);
    }

    // ---- Parent ----
    // Also set the group here, to close the race where the child is signalled
    // before it has called setpgid itself. Both calls are allowed.
    ::setpgid(pid, pid);

    return static_cast<int>(pid);
}

bool SystemHost::Exited(int pid, ExitKind *kind, int *code) {
    if (pid <= 0) {
        *kind = ExitKind::NotRunning;

        return true;
    }

    int status = 0;
    const pid_t reaped = ::waitpid(pid, &status, WNOHANG);

    if (reaped == 0) {
        return false; // still running
    }

    if (reaped < 0) {
        if (errno == ECHILD) {
            // Not our child (already reaped, or never was). Treat as gone.
            *kind = ExitKind::NotRunning;
            *code = 0;

            return true;
        }
        *kind = ExitKind::NotRunning;

        return true;
    }

    if (WIFEXITED(status)) {
        *kind = ExitKind::Exited;
        *code = WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        *kind = ExitKind::Signaled;
        *code = WTERMSIG(status);
    } else {
        *kind = ExitKind::NotRunning;
        *code = 0;
    }

    return true;
}

void SystemHost::Terminate(int pid) {
    if (pid > 0) {
        // Negative pid: the process group, so the app's children go too.
        ::kill(-pid, SIGTERM);
    }
}

void SystemHost::Kill(int pid) {
    if (pid > 0) {
        ::kill(-pid, SIGKILL);
    }
}

std::uint64_t SystemHost::StartTicks(int pid) {
    return vapps::StartTicksOf(pid);
}

std::int64_t SystemHost::NowMs() {
    struct timespec ts {};
    if (::clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
        return 0;
    }

    return static_cast<std::int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1'000'000;
}

void SystemHost::SleepMs(std::int64_t ms) {
    if (ms <= 0) {
        return;
    }

    struct timespec ts {};
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (ms % 1000) * 1'000'000;

    // nanosleep returns early on a signal; the caller loops, so that is fine.
    ::nanosleep(&ts, nullptr);
}

} // namespace vsup
