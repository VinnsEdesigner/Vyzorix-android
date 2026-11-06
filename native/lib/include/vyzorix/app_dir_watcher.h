// Watching the app directory for changes.
//
// The supervisor must notice a new app directory or an edited `apps.json` without a
// restart: an operator creates an app over the terminal, and the registry the
// supervisor is running must pick it up.
//
// ## Why a directory watch and not a file watch
//
// A watch on `apps.json` itself dies the moment the file is replaced, and it is
// replaced on every save: `Registry::Save` writes a temporary and renames it, so the
// inode changes. Watching the **directory** and filtering for the names we care about
// survives that, and it is the shape every editor-safe watcher uses.
//
// ## Why the queue is drained, not read once
//
// inotify coalesces nothing: a burst of writes produces a burst of events, and the
// kernel queue has a fixed size. A reader that takes one event per iteration falls
// behind and eventually gets `IN_Q_OVERFLOW` — at which point the only correct
// response is "re-read everything", because events have been lost. That is what
// [Drain] returning overflow reports.

#ifndef VYZORIX_APP_DIR_WATCHER_H
#define VYZORIX_APP_DIR_WATCHER_H

#include <cstdint>
#include <string>
#include <vector>

namespace vwatch {

/** What a batch of events amounts to, after filtering. */
struct Change {
    /** True when the app registry may have changed and must be re-read. */
    bool registryChanged = false;

    /** True when the watcher lost events and everything must be re-read. */
    bool overflowed = false;

    /** App directories that were created, by name. */
    std::vector<std::string> createdDirs;
};

/**
 * An inotify watch on one directory.
 *
 * Non-copyable: it owns a descriptor.
 */
class DirWatcher {
public:
    DirWatcher() = default;

    /**
     * Watches `directory`.
     *
     * Returns false when inotify is unavailable or the directory cannot be watched.
     * That is **not** fatal: the supervisor still works, it just does not notice
     * registry edits until it restarts, which is the behaviour before this existed.
     * The caller logs and carries on.
     */
    bool Watch(const std::string &directory);

    ~DirWatcher();

    DirWatcher(const DirWatcher &) = delete;
    DirWatcher &operator=(const DirWatcher &) = delete;

    /** True when a watch is active. */
    [[nodiscard]] bool IsWatching() const {
        return fd_ >= 0;
    }

    /** The descriptor, for `select(2)`. Negative when not watching. */
    [[nodiscard]] int fd() const {
        return fd_;
    }

    /**
     * Drains every pending event and summarises them.
     *
     * Call it when `fd()` is readable. It never blocks: the descriptor is read
     * non-blockingly, so a spurious wake-up costs nothing.
     *
     * `registryName` is the file whose modification means "re-read the registry";
     * `apps.json` in practice.
     */
    Change Drain(const std::string &registryName);

private:
    int fd_ = -1;
    std::string directory_;
};

} // namespace vwatch

#endif // VYZORIX_APP_DIR_WATCHER_H
