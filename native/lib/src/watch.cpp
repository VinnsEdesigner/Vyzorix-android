// Watching the app directory — see watch.h.

#include "vyzorix/watch.h"

#include <cerrno>
#include <cstring>

#include <sys/inotify.h>
#include <sys/stat.h>
#include <unistd.h>

namespace vwatch {
namespace {

/** The read buffer. One event plus a name is well under this; a batch fits too. */
constexpr std::size_t kBufferBytes = 16 * 1024;

/** Events that mean the directory's contents changed. */
constexpr std::uint32_t kDirEvents = IN_CREATE | IN_DELETE | IN_MOVED_TO | IN_MOVED_FROM;

/** Events that mean a file's contents changed. */
constexpr std::uint32_t kFileEvents = IN_CLOSE_WRITE | IN_MODIFY | IN_MOVED_TO | IN_CREATE;

/** True when `path` is a directory. */
bool IsDirectory(const std::string &path) {
    struct stat info {};
    if (::stat(path.c_str(), &info) != 0) {
        return false;
    }

    return S_ISDIR(info.st_mode);
}

} // namespace

DirWatcher::~DirWatcher() {
    if (fd_ >= 0) {
        ::close(fd_);
    }
}

bool DirWatcher::Watch(const std::string &directory) {
    if (fd_ >= 0) {
        ::close(fd_);
        fd_ = -1;
    }

    directory_ = directory;

    fd_ = ::inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
    if (fd_ < 0) {
        return false;
    }

    // IN_CLOSE_WRITE rather than IN_MODIFY for the registry: a file being written
    // produces several modify events, and only the close means "the write is done".
    // Acting on a half-written registry would fail to parse it.
    const std::uint32_t mask = kDirEvents | kFileEvents;
    if (::inotify_add_watch(fd_, directory.c_str(), mask) < 0) {
        ::close(fd_);
        fd_ = -1;

        return false;
    }

    return true;
}

Change DirWatcher::Drain(const std::string &registryName) {
    Change change;

    if (fd_ < 0) {
        return change;
    }

    char buffer[kBufferBytes];

    // Loop until the descriptor would block: a single read can return several
    // events, and the queue must be emptied or it overflows and starts dropping.
    while (true) {
        const ssize_t n = ::read(fd_, buffer, sizeof(buffer));
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                break;
            }
            if (errno == EINTR) {
                continue;
            }

            break;
        }
        if (n == 0) {
            break;
        }

        // Walk the events in this read. Each is a header followed by a
        // variable-length name, so the stride is the header plus the name.
        ssize_t offset = 0;
        while (offset < n) {
            const auto *event = reinterpret_cast<const struct inotify_event *>(buffer + offset);
            offset += static_cast<ssize_t>(sizeof(struct inotify_event) + event->len);

            if ((event->mask & IN_Q_OVERFLOW) != 0) {
                // Events were dropped, so nothing observed here can be trusted to be
                // the complete picture. The caller re-reads everything.
                change.overflowed = true;

                continue;
            }

            const std::string name =
                event->len > 0 ? std::string(event->name, ::strnlen(event->name, event->len))
                               : std::string();
            if (name.empty()) {
                continue;
            }

            if (name == registryName) {
                // Only a completed write counts; IN_CREATE fires before the content
                // is there.
                if ((event->mask & (IN_CLOSE_WRITE | IN_MOVED_TO)) != 0) {
                    change.registryChanged = true;
                }

                continue;
            }

            // A new directory in the apps directory is a new app's home. It may not
            // be registered yet — `app-create` makes the directory and then writes
            // the registry — so this is a hint, not a fact, and the caller re-reads
            // the registry either way.
            if ((event->mask & (IN_CREATE | IN_MOVED_TO)) != 0 &&
                IsDirectory(directory_ + "/" + name)) {
                change.createdDirs.push_back(name);
            }
        }
    }

    return change;
}

} // namespace vwatch
