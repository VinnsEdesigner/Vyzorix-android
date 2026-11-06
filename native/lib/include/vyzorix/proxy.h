// An HTTP reverse proxy for one exposed app.
//
// An app listens on a port inside the container. Exposing it means making that port
// reachable from outside, and the interesting part is **who may reach it**:
//
//   * bound to `127.0.0.1`, only this phone can reach it (architecture doc §11.1);
//   * bound to `0.0.0.0`, everyone on the network can — a hotspot, in practice — so
//     a **token is required** (§7.2). `0.0.0.0` means every interface; it does not
//     mean localhost.
//
// The token check is why this exists as a proxy rather than as advice to the app:
// requiring every app to implement authentication is a rule that will be broken, and
// the device is the one place it can be enforced.
//
// ## Scope, and what is deliberately not here
//
// This is the **local** proxy: same-phone and hotspot access (§11 modes 1 and 2). The
// cloud tunnel (§11.3) is a separate project — wildcard DNS and TLS, org-scoped auth
// at the server — and is not attempted here.
//
// Each connection is one request, because the proxy forces `Connection: close`. That
// is a real simplification and it is deliberate: keep-alive brings request pipelining
// and chunked-request framing into the proxy, and a browser opening six connections
// to a flasher UI does not care that they are not reused. Bodies are **streamed**,
// never buffered whole, so a large download or a chunked response passes through
// without the proxy holding it in memory.

#ifndef VYZORIX_PROXY_H
#define VYZORIX_PROXY_H

#include <atomic>
#include <cstdint>
#include <string>

namespace vproxy {

/** Everything one exposed app needs. */
struct ProxyConfig {
    /** The app's name, for logging only. */
    std::string name;

    /** `127.0.0.1` for this phone only; `0.0.0.0` to share. */
    std::string bindAddress = "127.0.0.1";

    /** The port to listen on. 0 asks the kernel for a free one. */
    int port = 0;

    /** Where the app actually listens, inside the container. */
    std::string targetHost = "127.0.0.1";
    int targetPort = 0;

    /**
     * The token a non-loopback bind requires.
     *
     * Empty means no check, which is only allowed for a loopback bind. A non-loopback
     * bind with an empty token is refused by [Proxy::Start] rather than warned about:
     * see the class comment.
     */
    std::string token;

    /** How long to wait for the app to accept a connection. */
    int connectTimeoutMs = 5000;

    /** How long a single read may idle before the connection is dropped. */
    int idleTimeoutMs = 30'000;
};

/**
 * A running proxy.
 *
 * [Start] binds and returns; the accepting happens on its own thread, so the caller's
 * loop is never blocked. Each connection is then handled on its own thread, bounded
 * by [kMaxConnections] — a browser opens several at once, so serving them serially
 * would stall a page load.
 */
class Proxy {
public:
    Proxy() = default;
    ~Proxy();

    Proxy(const Proxy &) = delete;
    Proxy &operator=(const Proxy &) = delete;

    /**
     * Binds and starts accepting.
     *
     * Returns false — and sets [error] — when the configuration is refused (a
     * non-loopback bind with no token) or the port cannot be bound. It never throws,
     * because the caller is a supervisor whose job is to keep going.
     */
    bool Start(const ProxyConfig &config, std::string *error);

    /** Stops accepting, closes the listener, and waits for connections to finish. */
    void Stop();

    /** True once started and before stopped. */
    [[nodiscard]] bool running() const {
        return running_;
    }

    /** The port actually bound. Meaningful after a successful [Start]. */
    [[nodiscard]] int port() const {
        return boundPort_;
    }

    /** The bind address, as configured. */
    [[nodiscard]] const std::string &bindAddress() const {
        return config_.bindAddress;
    }

    /** True when a token is required for this proxy. */
    [[nodiscard]] bool requiresToken() const {
        return !config_.token.empty();
    }

    /** The largest request header block accepted, in bytes. */
    static constexpr std::size_t kMaxHeaderBytes = 64 * 1024;

    /** The most connections served at once. */
    static constexpr int kMaxConnections = 32;

private:
    void AcceptLoop();
    void ServeConnection(int clientFd);

    ProxyConfig config_;
    int listenFd_ = -1;
    int boundPort_ = 0;
    std::atomic<bool> running_{false};
    std::atomic<bool> stopping_{false};
    std::atomic<int> liveConnections_{0};
};

/**
 * True when `address` is a loopback address that needs no token.
 *
 * `127.0.0.1`, `::1`, and `localhost`. Anything else — including `0.0.0.0` — is a
 * bind others can reach.
 */
bool IsLoopback(const std::string &address);

/**
 * Compares two strings without leaking their contents through timing.
 *
 * A token check with `==` returns as soon as two bytes differ, which leaks how much
 * of a guess was right. This is the standard fix, and it is here rather than inline
 * so a test can pin it.
 */
bool ConstantTimeEquals(const std::string &a, const std::string &b);

/**
 * Extracts the token from a request target, and returns the target with it removed.
 *
 * The token travels as `?t=<token>` (§11.2 shows `?t=…`). It is **stripped before the
 * request is forwarded**, so the app never sees it — an app that logs its query
 * string would otherwise write the device's access token into its own log.
 *
 * A target with no token leaves `token` empty and the target unchanged.
 */
std::string StripToken(const std::string &target, std::string *token);

} // namespace vproxy

#endif // VYZORIX_PROXY_H
