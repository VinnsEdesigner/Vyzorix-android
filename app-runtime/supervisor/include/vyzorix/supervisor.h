// The app manager: the part of the supervisor that starts, watches and restarts
// apps, with no socket and no event loop of its own.
//
// Separated from `main` so it can be tested on the host. Everything that talks to
// the outside world — spawning, killing, reading the clock, sleeping — goes through
// [Host], so a test drives a fake host and the backoff/lifecycle logic is exercised
// without forking anything.
//
// The rules it implements are architecture doc §7: fork/exec, autostart, crash
// restart with backoff, and PID validation by start-time token.

#ifndef VYZORIX_SUPERVISOR_H
#define VYZORIX_SUPERVISOR_H

#include <cstdint>
#include <map>
#include <string>
#include <vector>

#include "vyzorix/apps.h"

namespace vsup {

/** How an app exited, as far as the manager cares. */
enum class ExitKind {
    /** Exited on its own with this code. */
    Exited,
    /** Killed by a signal. */
    Signaled,
    /** Not running: no pid record, or the recorded process is gone. */
    NotRunning,
};

/** A running app's process, as the manager tracks it. */
struct Running {
    int pid = 0;
    std::uint64_t startTicks = 0;

    /** Monotonic time the process was started, for backoff bookkeeping. */
    std::int64_t startedAtMs = 0;
};

/**
 * The outside world, injected.
 *
 * Every method a test needs to control is here. Production uses the system
 * implementation in `host_system.cpp`; tests use a fake. This is the seam that
 * makes "restart with backoff" testable without waiting on real time or forking
 * real processes.
 */
class Host {
public:
    virtual ~Host() = default;

    /**
     * Spawns `cmd` in `dir` with `env` added to the environment, in its own process
     * group, with stdout/stderr appended to `logPath`.
     *
     * Returns the child's pid, or -1 on failure. The child is **not** waited for;
     * the manager polls [Exited].
     */
    virtual int Spawn(const std::string &cmd, const std::string &dir,
                      const std::map<std::string, std::string> &env,
                      const std::string &logPath) = 0;

    /** True when `pid` has exited; on true, fills `kind` and `code`. */
    virtual bool Exited(int pid, ExitKind *kind, int *code) = 0;

    /** Sends SIGTERM to `pid`'s process group. */
    virtual void Terminate(int pid) = 0;

    /** Kills `pid`'s process group outright. */
    virtual void Kill(int pid) = 0;

    /** The start-time token for `pid`, or 0 when it does not exist. */
    virtual std::uint64_t StartTicks(int pid) = 0;

    /** Monotonic milliseconds. Used for backoff, never for wall-clock time. */
    virtual std::int64_t NowMs() = 0;

    /** Sleeps, interruptibly enough for a supervisor loop. */
    virtual void SleepMs(std::int64_t ms) = 0;
};

/**
 * The lifecycle policy: which apps run, when to restart, when to give up.
 *
 * The manager holds no lock of its own and is not thread-safe: the caller
 * serialises access (the supervisor's loop is single-threaded, and the control
 * socket hands work to it rather than acting directly).
 */
class Manager {
public:
    Manager(vapps::Registry registry, std::string registryPath, std::string logDir,
            Host *host);

    /** Starts every app marked `autostart`, skipping ones already running. */
    void StartAutostart();

    /**
     * Starts `name`. Returns false when the app is unknown or the spawn failed.
     *
     * A start clears the app's failure count, because an explicit start is a fresh
     * attempt, not a retry.
     */
    bool Start(const std::string &name);

    /** Stops `name`: SIGTERM, then SIGKILL if it does not go. Idempotent. */
    bool Stop(const std::string &name);

    /** Stops then starts. */
    bool Restart(const std::string &name);

    /**
     * Reaps exited apps and schedules restarts.
     *
     * Called from the supervisor's loop. For each app whose process has exited it
     * records the exit, decides whether the app should come back, and if so records
     * the time its next start may run.
     */
    void Poll();

    /** True when the app has a live process (checked, not assumed). */
    bool IsRunning(const std::string &name);

    /** The names of the running apps, sorted. */
    std::vector<std::string> RunningNames();

    const vapps::Registry &registry() const { return registry_; }

    /**
     * Mutable access, for the control ops that edit the registry (`create`,
     * `remove`). The caller must call [Persist] afterwards: this does not, because a
     * batch of edits should be one write.
     */
    vapps::Registry &mutableRegistry() { return registry_; }

    const std::string &registryPath() const { return registryPath_; }

    /** The log file for `name`. */
    std::string LogPathFor(const std::string &name) const;

    /** The registry as JSON, for `app-list --json`. */
    std::string RegistryJson() const { return registry_.ToJson(); }

    /** Writes the registry. Called after any change to a pid record. */
    void Persist() const;

    /**
     * Re-reads the registry from disk, keeping running state.
     *
     * Used by the inotify path: a manifest changed, so the set of apps may have.
     * Running processes are left alone — a registry edit must not kill an app.
     */
    void Reload();

private:
    vapps::Registry registry_;
    std::string registryPath_;
    std::string logDir_;
    Host *host_;

    /** Live processes, keyed by app name. */
    std::map<std::string, Running> running_;

    /**
     * Consecutive crash count per app, independent of whether it is running.
     *
     * Separate from [running_] on purpose: the count has to survive the moment a
     * process is reaped, which is exactly when `running_` loses the entry. Keeping
     * it here is what makes the backoff actually grow instead of resetting to the
     * first step on every restart.
     */
    std::map<std::string, int> failures_;

    /** Apps waiting out a backoff, keyed by name: the time the next start may run. */
    std::map<std::string, std::int64_t> backoffUntil_;
};

/** The backoff schedule, exposed so the test and the doc agree on it. */
std::int64_t BackoffMsFor(int failures);

/** The number of consecutive failures after which an app is left stopped. */
constexpr int kMaxFailures = 5;

/** How long a process must run to count as "started cleanly" and reset backoff. */
constexpr std::int64_t kHealthyRunMs = 10'000;

} // namespace vsup

#endif // VYZORIX_SUPERVISOR_H
