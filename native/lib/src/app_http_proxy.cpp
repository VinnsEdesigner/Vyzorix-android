// The HTTP reverse proxy — see proxy.h for the design and its scope.

#include "vyzorix/app_http_proxy.h"

#include <algorithm>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <map>
#include <string>
#include <thread>
#include <vector>

#include <arpa/inet.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

namespace vproxy {
namespace {

/** Read buffer for headers and for streaming bodies. */
constexpr std::size_t kChunkBytes = 16 * 1024;

/** How long `poll` waits before checking the stop flag. */
constexpr int kPollTickMs = 200;

std::string ErrnoText(const std::string &what) {
    return what + ": " + std::strerror(errno);
}

/** Reads until the header terminator, or the cap, or EOF. */
bool ReadHeaders(int fd, std::string *out, int idleTimeoutMs) {
    out->clear();

    while (out->size() < Proxy::kMaxHeaderBytes) {
        const std::size_t terminator = out->find("\r\n\r\n");
        if (terminator != std::string::npos) {
            return true;
        }

        struct pollfd pfd {};
        pfd.fd = fd;
        pfd.events = POLLIN;

        const int ready = ::poll(&pfd, 1, idleTimeoutMs);
        if (ready <= 0) {
            return false;
        }

        char buffer[kChunkBytes];
        const ssize_t n = ::read(fd, buffer, sizeof(buffer));
        if (n <= 0) {
            return false;
        }
        out->append(buffer, static_cast<std::size_t>(n));
    }

    // The cap was hit without a terminator: a request header block larger than any
    // real client sends, so this is a client to refuse rather than buffer for.
    return false;
}

/** Writes every byte, or fails. */
bool WriteAll(int fd, const std::string &data) {
    std::size_t written = 0;
    while (written < data.size()) {
        const ssize_t n = ::write(fd, data.data() + written, data.size() - written);
        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }

            return false;
        }
        written += static_cast<std::size_t>(n);
    }

    return true;
}

/**
 * Streams `from` to `to` until EOF, or until the idle timeout with nothing arriving.
 *
 * Returns false when the connection should be torn down. The idle timeout is a
 * **read** timeout, not a total one: a large download that keeps producing bytes is
 * never cut off, which is what makes streaming a large file work over a slow link.
 */
bool Pump(int from, int to, int idleTimeoutMs) {
    char buffer[kChunkBytes];

    while (true) {
        struct pollfd pfd {};
        pfd.fd = from;
        pfd.events = POLLIN;

        const int ready = ::poll(&pfd, 1, idleTimeoutMs);
        if (ready < 0) {
            if (errno == EINTR) {
                continue;
            }

            return false;
        }
        if (ready == 0) {
            // Nothing arrived for the whole window. A request that never ends is a
            // connection to drop, not to hold.
            return false;
        }

        const ssize_t n = ::read(from, buffer, sizeof(buffer));
        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }

            return false;
        }
        if (n == 0) {
            return true; // clean EOF
        }

        std::size_t written = 0;
        while (written < static_cast<std::size_t>(n)) {
            const ssize_t w = ::write(to, buffer + written, static_cast<std::size_t>(n) - written);
            if (w < 0) {
                if (errno == EINTR) {
                    continue;
                }

                return false;
            }
            written += static_cast<std::size_t>(w);
        }
    }
}

/** The value of a header, case-insensitively, or empty. */
std::string HeaderValue(const std::map<std::string, std::string> &headers,
                        const std::string &name) {
    for (const auto &entry : headers) {
        if (entry.first == name) {
            return entry.second;
        }
    }

    return std::string();
}

/** Lowercases ASCII, for header-name comparison. */
std::string Lowercase(const std::string &raw) {
    std::string out = raw;
    for (char &c : out) {
        if (c >= 'A' && c <= 'Z') {
            c = static_cast<char>(c - 'A' + 'a');
        }
    }

    return out;
}

/** Connects to the app, or returns -1. */
int ConnectToTarget(const std::string &host, int port, int timeoutMs) {
    struct addrinfo hints {};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;

    struct addrinfo *results = nullptr;
    const std::string portText = std::to_string(port);
    if (::getaddrinfo(host.c_str(), portText.c_str(), &hints, &results) != 0) {
        return -1;
    }

    int fd = -1;
    for (struct addrinfo *entry = results; entry != nullptr; entry = entry->ai_next) {
        fd = ::socket(entry->ai_family, entry->ai_socktype, entry->ai_protocol);
        if (fd < 0) {
            continue;
        }

        // A non-blocking connect with a deadline: a blocking one would hang the
        // connection thread for the kernel's whole TCP timeout if the app is wedged.
        const int flags = ::fcntl(fd, F_GETFL, 0);
        ::fcntl(fd, F_SETFL, flags | O_NONBLOCK);

        if (::connect(fd, entry->ai_addr, entry->ai_addrlen) == 0) {
            ::fcntl(fd, F_SETFL, flags);
            break;
        }
        if (errno == EINPROGRESS) {
            struct pollfd pfd {};
            pfd.fd = fd;
            pfd.events = POLLOUT;

            if (::poll(&pfd, 1, timeoutMs) > 0) {
                int error = 0;
                socklen_t length = sizeof(error);
                ::getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &length);
                if (error == 0) {
                    ::fcntl(fd, F_SETFL, flags);
                    break;
                }
            }
        }

        ::close(fd);
        fd = -1;
    }

    ::freeaddrinfo(results);

    return fd;
}

} // namespace

bool IsLoopback(const std::string &address) {
    return address == "127.0.0.1" || address == "::1" || address == "localhost";
}

bool ConstantTimeEquals(const std::string &a, const std::string &b) {
    // Length is compared first and leaks only the length, which is not the secret.
    // The bytes are always all compared, so the time taken does not depend on where
    // the first difference is.
    if (a.size() != b.size()) {
        return false;
    }

    unsigned char difference = 0;
    for (std::size_t i = 0; i < a.size(); i++) {
        difference |= static_cast<unsigned char>(a[i]) ^ static_cast<unsigned char>(b[i]);
    }

    return difference == 0;
}

std::string StripToken(const std::string &target, std::string *token) {
    token->clear();

    const std::size_t query = target.find('?');
    if (query == std::string::npos) {
        return target;
    }

    const std::string path = target.substr(0, query);
    const std::string queryString = target.substr(query + 1);

    std::string kept;
    std::size_t start = 0;
    while (start <= queryString.size()) {
        const std::size_t ampersand = queryString.find('&', start);
        const std::string pair = queryString.substr(
            start, ampersand == std::string::npos ? std::string::npos : ampersand - start);

        if (!pair.empty()) {
            const std::size_t equals = pair.find('=');
            const std::string key = equals == std::string::npos ? pair : pair.substr(0, equals);
            if (key == "t") {
                // The token is removed from what is forwarded, so the app cannot log
                // it, and the rest of the query string is preserved.
                *token = equals == std::string::npos ? "" : pair.substr(equals + 1);
            } else {
                if (!kept.empty()) {
                    kept.push_back('&');
                }
                kept += pair;
            }
        }

        if (ampersand == std::string::npos) {
            break;
        }
        start = ampersand + 1;
    }

    return kept.empty() ? path : path + "?" + kept;
}

Proxy::~Proxy() {
    Stop();
}

bool Proxy::Start(const ProxyConfig &config, std::string *error) {
    config_ = config;

    // The refusal that matters. A non-loopback bind is reachable by everyone on the
    // network, so without a token it is not a configuration to warn about — it is one
    // to refuse. This is checked before anything is bound, so a refused expose leaves
    // nothing half-open.
    if (!IsLoopback(config_.bindAddress) && config_.token.empty()) {
        *error = "refusing to bind " + config_.bindAddress +
                 " without a token: that would expose the app to the whole network";

        return false;
    }

    if (config_.targetPort <= 0) {
        *error = "no target port: the app does not listen anywhere";

        return false;
    }

    listenFd_ = ::socket(AF_INET, SOCK_STREAM, 0);
    if (listenFd_ < 0) {
        *error = ErrnoText("socket");

        return false;
    }

    const int reuse = 1;
    ::setsockopt(listenFd_, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));

    struct sockaddr_in address {};
    address.sin_family = AF_INET;
    address.sin_port = htons(static_cast<uint16_t>(config_.port));
    if (::inet_pton(AF_INET, config_.bindAddress.c_str(), &address.sin_addr) != 1) {
        // Not a literal IPv4 address. `0.0.0.0` and `127.0.0.1` are the two that
        // matter, so anything else is a mistake rather than a name to resolve.
        *error = "not an IPv4 bind address: " + config_.bindAddress;
        ::close(listenFd_);
        listenFd_ = -1;

        return false;
    }

    if (::bind(listenFd_, reinterpret_cast<struct sockaddr *>(&address), sizeof(address)) != 0) {
        *error = ErrnoText("bind " + config_.bindAddress);
        ::close(listenFd_);
        listenFd_ = -1;

        return false;
    }

    if (::listen(listenFd_, 16) != 0) {
        *error = ErrnoText("listen");
        ::close(listenFd_);
        listenFd_ = -1;

        return false;
    }

    // The kernel may have chosen the port, so read it back rather than assume the
    // requested one — a caller that asked for 0 needs to know what it got.
    struct sockaddr_in bound {};
    socklen_t length = sizeof(bound);
    if (::getsockname(listenFd_, reinterpret_cast<struct sockaddr *>(&bound), &length) == 0) {
        boundPort_ = ntohs(bound.sin_port);
    } else {
        boundPort_ = config_.port;
    }

    running_ = true;
    stopping_ = false;

    std::thread([this] { AcceptLoop(); }).detach();

    return true;
}

void Proxy::Stop() {
    if (!running_.exchange(false)) {
        return;
    }

    stopping_ = true;
    if (listenFd_ >= 0) {
        // Shutdown rather than close: a thread blocked in accept on a closed fd is
        // undefined, and shutdown wakes it with a clean error.
        ::shutdown(listenFd_, SHUT_RDWR);
        ::close(listenFd_);
        listenFd_ = -1;
    }

    // Let in-flight connections finish rather than cutting a response off mid-body.
    for (int i = 0; i < 50 && liveConnections_.load() > 0; i++) {
        struct timespec ts {};
        ts.tv_nsec = 100 * 1000 * 1000; // 100ms
        ::nanosleep(&ts, nullptr);
    }
}

void Proxy::AcceptLoop() {
    while (!stopping_) {
        struct pollfd pfd {};
        pfd.fd = listenFd_;
        pfd.events = POLLIN;

        const int ready = ::poll(&pfd, 1, kPollTickMs);
        if (stopping_) {
            break;
        }
        if (ready <= 0) {
            continue;
        }

        const int client = ::accept(listenFd_, nullptr, nullptr);
        if (client < 0) {
            if (errno == EINTR || errno == EAGAIN) {
                continue;
            }

            break;
        }

        if (liveConnections_.load() >= kMaxConnections) {
            // Refuse rather than queue: a browser retries, and an unbounded number of
            // threads is how a device runs out of memory.
            const std::string busy =
                "HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n";
            WriteAll(client, busy);
            ::close(client);

            continue;
        }

        liveConnections_++;
        std::thread([this, client] {
            ServeConnection(client);
            ::close(client);
            liveConnections_--;
        }).detach();
    }
}

void Proxy::ServeConnection(int clientFd) {
    std::string headers;
    if (!ReadHeaders(clientFd, &headers, config_.idleTimeoutMs)) {
        return;
    }

    // Request line: "METHOD TARGET VERSION".
    const std::size_t firstSpace = headers.find(' ');
    const std::size_t secondSpace =
        firstSpace == std::string::npos ? std::string::npos : headers.find(' ', firstSpace + 1);
    if (firstSpace == std::string::npos || secondSpace == std::string::npos) {
        const std::string bad = "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n";
        WriteAll(clientFd, bad);

        return;
    }

    const std::string method = headers.substr(0, firstSpace);
    const std::string target = headers.substr(firstSpace + 1, secondSpace - firstSpace - 1);
    const std::string version = headers.substr(secondSpace + 1);
    const std::size_t lineEnd = version.find("\r\n");
    const std::string httpVersion =
        lineEnd == std::string::npos ? version : version.substr(0, lineEnd);

    // Split the headers into name/value pairs, lowercased names.
    std::map<std::string, std::string> parsed;
    std::size_t lineStart = headers.find("\r\n") + 2;
    while (lineStart < headers.size()) {
        const std::size_t lineFinish = headers.find("\r\n", lineStart);
        if (lineFinish == std::string::npos || lineFinish == lineStart) {
            break;
        }

        const std::string line = headers.substr(lineStart, lineFinish - lineStart);
        const std::size_t colon = line.find(':');
        if (colon != std::string::npos) {
            parsed[Lowercase(line.substr(0, colon))] =
                line.substr(colon + 1).substr(line.substr(colon + 1).find_first_not_of(" \t"));
        }

        lineStart = lineFinish + 2;
    }

    // The token check. It is applied to every request, not once per connection: the
    // proxy is stateless, and a connection that carried a valid token cannot vouch
    // for the next one.
    if (requiresToken()) {
        std::string presented;
        StripToken(target, &presented);

        // Also accept the header form, which a client that cannot use a query string
        // (a form POST, a WebSocket handshake from a page) will use.
        const std::string headerToken = HeaderValue(parsed, "x-vyzorix-token");
        if (presented.empty()) {
            presented = headerToken;
        }

        if (presented.empty() || !ConstantTimeEquals(presented, config_.token)) {
            // 401 with the scheme, so a browser can be told what to do; the body says
            // nothing about whether the token was close.
            const std::string denied = "HTTP/1.1 401 Unauthorized\r\n"
                                       "Connection: close\r\n"
                                       "Content-Type: text/plain\r\n"
                                       "WWW-Authenticate: Bearer realm=\"vyzorix\"\r\n"
                                       "\r\n"
                                       "a token is required: append ?t=<token>\n";
            WriteAll(clientFd, denied);

            return;
        }
    }

    // Forward the request without the token, and without the hop-by-hop headers that
    // do not survive a proxy.
    std::string forwardedTarget = target;
    std::string discardedToken;
    forwardedTarget = StripToken(forwardedTarget, &discardedToken);

    std::string forwarded = method + " " + forwardedTarget + " " + httpVersion + "\r\n";
    for (const auto &entry : parsed) {
        if (entry.first == "connection" || entry.first == "keep-alive" ||
            entry.first == "proxy-connection" || entry.first == "x-vyzorix-token" ||
            entry.first == "te" || entry.first == "trailer" || entry.first == "upgrade") {
            continue;
        }
        forwarded += entry.first + ": " + entry.second + "\r\n";
    }
    // The proxy does not reuse connections, and it says so rather than relying on the
    // client to guess. It also tells the app the real client, since the app sees the
    // proxy's address otherwise.
    forwarded += "Connection: close\r\n";
    const std::string forwardedFor = HeaderValue(parsed, "x-forwarded-for");
    forwarded += "X-Forwarded-For: " + (forwardedFor.empty() ? "127.0.0.1" : forwardedFor) + "\r\n";
    forwarded += "X-Forwarded-Proto: http\r\n";
    forwarded += "\r\n";

    const int upstream =
        ConnectToTarget(config_.targetHost, config_.targetPort, config_.connectTimeoutMs);
    if (upstream < 0) {
        const std::string unavailable = "HTTP/1.1 502 Bad Gateway\r\n"
                                        "Connection: close\r\n"
                                        "Content-Type: text/plain\r\n"
                                        "\r\n"
                                        "the app is not accepting connections\n";
        WriteAll(clientFd, unavailable);

        return;
    }

    if (!WriteAll(upstream, forwarded)) {
        ::close(upstream);

        return;
    }

    // A request body, if any, is streamed straight through. `Content-Length` and
    // chunked framing are the app's business; the proxy only moves bytes.
    if (const std::string length = HeaderValue(parsed, "content-length"); !length.empty()) {
        std::size_t remaining = static_cast<std::size_t>(::strtoul(length.c_str(), nullptr, 10));
        while (remaining > 0) {
            char buffer[kChunkBytes];
            const ssize_t n = ::read(clientFd, buffer, std::min(sizeof(buffer), remaining));
            if (n <= 0) {
                break;
            }
            if (!WriteAll(upstream, std::string(buffer, static_cast<std::size_t>(n)))) {
                break;
            }
            remaining -= static_cast<std::size_t>(n);
        }
    }

    // The response, streamed. The app closes when it is done because the request said
    // `Connection: close`.
    Pump(upstream, clientFd, config_.idleTimeoutMs);

    ::close(upstream);
}

} // namespace vproxy
