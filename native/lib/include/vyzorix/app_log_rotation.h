// Bounded, rotated app logs.
//
// An app's stdout and stderr are redirected to `logs/<name>.log` when it is
// spawned, and the supervisor never sees those writes — the child holds the
// descriptor. So rotation is done from the outside, and that constrains how it can
// work.
//
// ## Why copy-then-truncate, and why O_APPEND is what makes it correct
//
// The child's descriptor stays open across a rotation, so renaming the file would
// leave the child writing to the renamed inode and the new file would stay empty.
// Truncating in place keeps the descriptor valid — but a descriptor with a
// non-append offset would then seek past the new end and produce a sparse file of
// zeroes.
//
// The supervisor opens the log with `O_APPEND`, so every write goes to the current
// end of file whatever the offset is. Truncating to zero therefore means the next
// write lands at zero. That is the property that makes copy-then-truncate correct
// here, and it is why the spawn opens the log with O_APPEND.

#ifndef VYZORIX_APP_LOG_ROTATION_H
#define VYZORIX_APP_LOG_ROTATION_H

#include <cstddef>
#include <string>

namespace vlog {

/** How much of a log to keep when it is rotated. */
constexpr std::size_t kDefaultMaxBytes = 1024 * 1024;

/** How much of the tail to preserve in the rotated copy. */
constexpr std::size_t kDefaultKeepBytes = 256 * 1024;

/**
 * Rotates `path` when it exceeds `maxBytes`.
 *
 * Keeps the last `keepBytes` in `path.1` (replacing any previous rotation) and
 * truncates `path` to zero. Returns true when a rotation happened.
 *
 * A missing file is not an error — an app that has never written has no log — and
 * returns false. A failure to rotate is also not an error the caller must handle:
 * it returns false and the log simply keeps growing, which is better than stopping
 * the app.
 */
bool RotateLog(const std::string &path, std::size_t maxBytes = kDefaultMaxBytes,
               std::size_t keepBytes = kDefaultKeepBytes);

/** The size of `path` in bytes, or 0 when it does not exist. */
std::size_t LogSize(const std::string &path);

} // namespace vlog

#endif // VYZORIX_APP_LOG_ROTATION_H
