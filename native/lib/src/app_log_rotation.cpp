// Bounded, rotated app logs — see log.h for why the strategy is copy-then-truncate.

#include "vyzorix/app_log_rotation.h"

#include <cstdio>
#include <fstream>
#include <vector>

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace vlog {
namespace {

/** Reads the last `keep` bytes of `path`, or an empty string. */
std::string ReadTail(const std::string &path, std::size_t keep) {
    std::ifstream in(path, std::ios::binary);
    if (!in) {
        return std::string();
    }

    in.seekg(0, std::ios::end);
    const std::streamoff size = in.tellg();
    if (size <= 0) {
        return std::string();
    }

    const std::streamoff start =
        size > static_cast<std::streamoff>(keep) ? size - static_cast<std::streamoff>(keep) : 0;
    in.seekg(start);

    std::string body;
    body.resize(static_cast<std::size_t>(size - start));
    in.read(body.data(), static_cast<std::streamsize>(body.size()));

    return body;
}

bool WriteAll(const std::string &path, const std::string &body) {
    const int fd = ::open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0640);
    if (fd < 0) {
        return false;
    }

    std::size_t written = 0;
    while (written < body.size()) {
        const ssize_t n = ::write(fd, body.data() + written, body.size() - written);
        if (n < 0) {
            ::close(fd);

            return false;
        }
        written += static_cast<std::size_t>(n);
    }

    ::close(fd);

    return true;
}

} // namespace

std::size_t LogSize(const std::string &path) {
    struct stat info {};
    if (::stat(path.c_str(), &info) != 0) {
        return 0;
    }

    return static_cast<std::size_t>(info.st_size);
}

bool RotateLog(const std::string &path, std::size_t maxBytes, std::size_t keepBytes) {
    const std::size_t size = LogSize(path);
    if (size <= maxBytes) {
        return false;
    }

    // Copy the tail out before truncating: after the truncate it is gone.
    const std::string tail = ReadTail(path, keepBytes);

    // The rotated copy. A failure here is not fatal — the next step still bounds the
    // live log, which is the point of rotating at all.
    if (!tail.empty()) {
        WriteAll(path + ".1", tail);
    }

    // Truncate in place, never unlink or rename: the app holds this file open, and
    // it is opened O_APPEND, so the next write lands at the new end of file.
    if (::truncate(path.c_str(), 0) != 0) {
        return false;
    }

    return true;
}

} // namespace vlog
