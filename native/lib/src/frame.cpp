// Framed messages over a unix socket — see frame.h.

#include "vyzorix/frame.h"

#include <cerrno>
#include <cstring>
#include <string>

#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

namespace vframe {
namespace {

/** Frame header: 4-byte big-endian length, then the 1-byte type. */
constexpr std::size_t kHeaderSize = 5;

/** listen(2) backlog. The control socket serves a handful of local clients. */
constexpr int kBacklog = 16;

std::string ErrnoText(const std::string &what) {
    return what + ": " + std::strerror(errno);
}

void WriteAll(int fd, const void *data, std::size_t size) {
    const auto *bytes = static_cast<const char *>(data);
    std::size_t written = 0;

    while (written < size) {
        const ssize_t n = ::write(fd, bytes + written, size - written);
        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }
            throw Error(ErrnoText("write"));
        }
        written += static_cast<std::size_t>(n);
    }
}

void ReadAll(int fd, void *data, std::size_t size) {
    auto *bytes = static_cast<char *>(data);
    std::size_t read = 0;

    while (read < size) {
        const ssize_t n = ::read(fd, bytes + read, size - read);
        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }
            throw Error(ErrnoText("read"));
        }
        if (n == 0) {
            throw Error("connection closed");
        }
        read += static_cast<std::size_t>(n);
    }
}

/** Fills a sockaddr_un for `path`, rejecting a path the struct cannot hold. */
sockaddr_un AddressFor(const std::string &path) {
    sockaddr_un address{};
    address.sun_family = AF_UNIX;

    if (path.size() >= sizeof(address.sun_path)) {
        throw Error("socket path is too long: " + path);
    }
    std::memcpy(address.sun_path, path.c_str(), path.size() + 1);

    return address;
}

} // namespace

Connection::Connection(const std::string &path) {
    fd_ = ::socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd_ < 0) {
        throw Error(ErrnoText("socket(AF_UNIX)"));
    }

    const sockaddr_un address = AddressFor(path);
    if (::connect(fd_, reinterpret_cast<const sockaddr *>(&address), sizeof(address)) != 0) {
        const std::string why = ErrnoText("connect(" + path + ")");
        ::close(fd_);
        fd_ = -1;
        throw Error(why);
    }
}

Connection::~Connection() {
    if (fd_ >= 0) {
        ::close(fd_);
    }
}

Connection &Connection::operator=(Connection &&other) noexcept {
    if (this != &other) {
        if (fd_ >= 0) {
            ::close(fd_);
        }
        fd_ = other.fd_;
        other.fd_ = -1;
    }

    return *this;
}

void Connection::Write(std::uint8_t type, const std::string &payload) {
    Write(type, payload.data(), payload.size());
}

void Connection::Write(std::uint8_t type, const void *data, std::size_t size) {
    if (size > kMaxPayload) {
        throw Error("frame payload exceeds the cap");
    }

    unsigned char header[kHeaderSize];
    header[0] = static_cast<unsigned char>((size >> 24) & 0xFF);
    header[1] = static_cast<unsigned char>((size >> 16) & 0xFF);
    header[2] = static_cast<unsigned char>((size >> 8) & 0xFF);
    header[3] = static_cast<unsigned char>(size & 0xFF);
    header[4] = type;

    WriteAll(fd_, header, kHeaderSize);
    if (size > 0) {
        WriteAll(fd_, data, size);
    }
}

std::uint8_t Connection::Read(std::string *payload) {
    unsigned char header[kHeaderSize];
    ReadAll(fd_, header, kHeaderSize);

    const std::uint32_t length = (static_cast<std::uint32_t>(header[0]) << 24) |
                                 (static_cast<std::uint32_t>(header[1]) << 16) |
                                 (static_cast<std::uint32_t>(header[2]) << 8) |
                                 static_cast<std::uint32_t>(header[3]);

    if (length > kMaxPayload) {
        throw Error("peer sent an oversized frame");
    }

    payload->resize(length);
    if (length > 0) {
        ReadAll(fd_, payload->data(), length);
    }

    return header[4];
}

int Listen(const std::string &path) {
    const int fd = ::socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        throw Error(ErrnoText("socket(AF_UNIX)"));
    }

    const sockaddr_un address = AddressFor(path);

    // A crashed process leaves the node behind and bind(2) then fails with
    // EADDRINUSE. Unlinking first is what makes a restart work; ENOENT is fine.
    if (::unlink(path.c_str()) != 0 && errno != ENOENT) {
        const std::string why = ErrnoText("unlink(" + path + ")");
        ::close(fd);
        throw Error(why);
    }

    // 0600: a filesystem socket is reachable by path, so the mode is access control.
    const mode_t previous = ::umask(0077);
    const int bound = ::bind(fd, reinterpret_cast<const sockaddr *>(&address), sizeof(address));
    ::umask(previous);

    if (bound != 0) {
        const std::string why = ErrnoText("bind(" + path + ")");
        ::close(fd);
        throw Error(why);
    }

    if (::listen(fd, kBacklog) != 0) {
        const std::string why = ErrnoText("listen(" + path + ")");
        ::close(fd);
        throw Error(why);
    }

    return fd;
}

} // namespace vframe
