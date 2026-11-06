// The app registry: what runs, and how to tell whether it still does.
//
// `apps.json` (architecture doc §3) is the supervisor's source of truth. Its shape:
//
//   {
//     "apps": [
//       {
//         "name": "flasher",
//         "dir": "/home/apps/flasher",
//         "cmd": "python3 -m http.server 3000",
//         "port": 3000,
//         "env": {"KEY": "value"},
//         "autostart": true,
//         "pid": 1234,
//         "pidStartTicks": 56789
//       }
//     ]
//   }
//
// `pid` and `pidStartTicks` are written by the supervisor and are **only
// meaningful together** (§7.1): a PID is reused after a reboot, so a bare pid
// would let the supervisor signal a process that is not ours. `pidStartTicks` is
// field 22 of `/proc/<pid>/stat`, which does not repeat for a recycled pid within
// a boot.

#ifndef VYZORIX_APP_REGISTRY_H
#define VYZORIX_APP_REGISTRY_H

#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace vapps {

/** One application, as the registry records it. */
struct App {
    std::string name;
    std::string dir;
    std::string cmd;
    int port = 0;
    std::map<std::string, std::string> env;
    bool autostart = false;

    /** The supervisor's record of the running process. 0 means "not running". */
    int pid = 0;

    /**
     * `/proc/<pid>/stat` field 22 at the moment the process was started.
     *
     * Together with [pid] this identifies the process across PID reuse. 0 with a
     * non-zero pid means the record is incomplete and must not be trusted.
     */
    std::uint64_t pidStartTicks = 0;

    /** True when the registry claims this app has a running process. */
    bool HasPidRecord() const {
        return pid > 0 && pidStartTicks != 0;
    }
};

/**
 * The registry: the apps, and the file they came from.
 *
 * Reading is tolerant and writing is atomic, which is the pair that matters. A
 * registry that will not parse must not stop the device: [Load] returns an empty
 * registry for a missing file, and throws only for a file that exists and is
 * malformed, so the caller can decide (the supervisor logs and starts nothing
 * rather than silently starting the wrong thing).
 */
class Registry {
public:
    /** An empty registry. */
    Registry() = default;

    /**
     * Loads from `path`.
     *
     * A missing file is an empty registry, not an error — that is the state before
     * the first `app-create`. A present-but-malformed file throws, because
     * "empty" and "unreadable" must not be the same outcome.
     */
    static Registry Load(const std::string &path);

    /**
     * Writes to `path` atomically.
     *
     * Write to a temporary in the same directory, fsync, rename. A crash mid-write
     * then leaves either the old file or the new one, never a half-written one that
     * will not parse on the next boot.
     */
    void Save(const std::string &path) const;

    std::vector<App> &apps() {
        return apps_;
    }
    const std::vector<App> &apps() const {
        return apps_;
    }

    /** The app named `name`, or nullptr. */
    [[nodiscard]] App *Find(const std::string &name);
    [[nodiscard]] const App *Find(const std::string &name) const;

    /**
     * Adds or replaces an app by name.
     *
     * Replace, not append: `app-create` on an existing name updates it, and two
     * entries with one name would make "which one is running" unanswerable.
     */
    void Upsert(const App &app);

    /** Removes the app named `name`. Returns whether one was removed. */
    bool Remove(const std::string &name);

    /** Serialises to JSON. Exposed for tests and for `app-list --json`. */
    [[nodiscard]] std::string ToJson() const;

private:
    std::vector<App> apps_;
};

/**
 * Reads `/proc/<pid>/stat` field 22 — the process start time in clock ticks.
 *
 * Returns 0 when the process does not exist or the file cannot be read, which the
 * caller reads as "not our process". 0 is not a valid start time for a live
 * process, so it is unambiguous.
 *
 * Parsing note: the second field is the executable name in parentheses and may
 * contain spaces and parentheses, so the fields are counted from the **last** `)`
 * rather than by splitting on whitespace. Splitting naively is the classic bug
 * here and it breaks exactly on the processes you most want to identify.
 */
std::uint64_t StartTicksOf(int pid);

/**
 * True when `pid` is still the process whose start time was `startTicks`.
 *
 * This is the §7.1 check, and it is the only safe way to act on a recorded pid.
 */
bool IsSameProcess(int pid, std::uint64_t startTicks);

} // namespace vapps

#endif // VYZORIX_APP_REGISTRY_H
