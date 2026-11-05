// Framed messages over a unix socket.
//
// One wire shape for the whole repo, from the architecture doc §2.3:
//
//     [4B length][1B type][payload]
//
// `length` is the payload length, big-endian, capped at 1 MiB. It is what
// android-core's BridgeProtocol implements on the Kotlin side and what the
// `android.sock` bridge speaks; the supervisor's control socket uses the same
// shape, so there is one framing in the codebase and one set of bugs.
//
// This module owns the socket and the framing only. What a `type` means, and what
// the payload contains, is the caller's business — that is what keeps one framing
// usable by two protocols.

#ifndef VYZORIX_FRAME_H
#define VYZORIX_FRAME_H

#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <string>

namespace vframe {

/** Largest payload a single frame may carry, matching BridgeProtocol.MAX_PAYLOAD. */
constexpr std::uint32_t kMaxPayload = 1u << 20;

/** Thrown for any connect, framing or I/O failure. */
class Error : public std::runtime_error {
public:
    explicit Error(const std::string &what) : std::runtime_error(what) {}
};

/**
 * A connected socket that reads and writes frames.
 *
 * Owns the descriptor; closes it on destruction. Not copyable, because two owners
 * of one descriptor is the classic double-close.
 */
class Connection {
public:
    /**
     * Connects to a filesystem unix socket at `path`.
     *
     * Throws [Error] when the path does not exist, is not a socket, or refuses the
     * connection — each with the errno text, because "connection refused" and "no
     * such file" are different problems and the caller prints the difference.
     */
    explicit Connection(const std::string &path);

    /**
     * Wraps an already-connected descriptor. Takes ownership.
     *
     * Used by the server side, which gets its descriptor from `accept(2)`.
     */
    explicit Connection(int fd) : fd_(fd) {}

    ~Connection();

    Connection(const Connection &) = delete;
    Connection &operator=(const Connection &) = delete;

    Connection(Connection &&other) noexcept : fd_(other.fd_) { other.fd_ = -1; }
    Connection &operator=(Connection &&other) noexcept;

    /** Writes one frame. Throws [Error] on failure or an oversized payload. */
    void Write(std::uint8_t type, const void *data, std::size_t size);
    void Write(std::uint8_t type, const std::string &payload);

    /**
     * Reads one frame into `payload`, returning its type.
     *
     * Throws [Error] on EOF, a short read, or a length above the cap — a caller
     * cannot continue after any of those, because the stream is no longer framed.
     */
    std::uint8_t Read(std::string *payload);

    /** The raw descriptor. For `poll(2)` and `accept(2)`; do not close it. */
    int fd() const { return fd_; }

private:
    int fd_ = -1;
};

/**
 * Creates, binds and listens on a filesystem unix socket at `path`.
 *
 * Returns the listening descriptor. A stale node at `path` is removed first: a
 * crashed process leaves the file behind and binding over it fails, so the cleanup
 * belongs here rather than in every caller.
 *
 * The node is created with mode 0600 — a control socket is reachable by path, so
 * the mode is part of the access control.
 */
int Listen(const std::string &path);

} // namespace vframe

#endif // VYZORIX_FRAME_H
