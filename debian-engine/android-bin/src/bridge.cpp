// The android.sock client — see bridge.h.

#include "bridge.h"

#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <string>

#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include "vyzorix/vyzorix_json.h"

namespace android_bin {
namespace {

/** The largest payload a frame may carry, matching BridgeProtocol.MAX_PAYLOAD. */
constexpr std::uint32_t kMaxPayload = 1u << 20;

/** Frame header: 4-byte big-endian length, then the 1-byte type. */
constexpr std::size_t kHeaderSize = 5;

std::string ErrnoText(const std::string &what) {
    return what + ": " + std::strerror(errno);
}

/** Writes every byte, retrying on a short write. */
void WriteAll(int fd, const void *data, std::size_t size) {
    const auto *bytes = static_cast<const char *>(data);
    std::size_t written = 0;

    while (written < size) {
        const ssize_t n = ::write(fd, bytes + written, size - written);
        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }
            throw BridgeError(ErrnoText("write to bridge"));
        }
        written += static_cast<std::size_t>(n);
    }
}

/** Reads exactly `size` bytes, or throws. */
void ReadAll(int fd, void *data, std::size_t size) {
    auto *bytes = static_cast<char *>(data);
    std::size_t read = 0;

    while (read < size) {
        const ssize_t n = ::read(fd, bytes + read, size - read);
        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }
            throw BridgeError(ErrnoText("read from bridge"));
        }
        if (n == 0) {
            throw BridgeError("bridge closed the connection");
        }
        read += static_cast<std::size_t>(n);
    }
}

const char *TypeName(FrameType type) {
    switch (type) {
    case FrameType::Request:
        return "request";
    case FrameType::Response:
        return "response";
    case FrameType::PtyOutput:
        return "pty_output";
    case FrameType::PtyInput:
        return "pty_input";
    case FrameType::PtyCredit:
        return "pty_credit";
    case FrameType::Event:
        return "event";
    }

    return "unknown";
}

} // namespace

BridgeConfig ConfigFromEnvironment() {
    BridgeConfig config;

    const char *socket_path = std::getenv("VYZORIX_BRIDGE_SOCKET");
    const char *token = std::getenv("VYZORIX_BRIDGE_TOKEN");

    if (socket_path == nullptr || *socket_path == '\0') {
        throw BridgeError("VYZORIX_BRIDGE_SOCKET is not set");
    }
    if (token == nullptr || *token == '\0') {
        throw BridgeError("VYZORIX_BRIDGE_TOKEN is not set");
    }

    config.socket_path = socket_path;
    config.token = token;

    return config;
}

BridgeClient::BridgeClient(const BridgeConfig &config) {
    fd_ = ::socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd_ < 0) {
        throw BridgeError(ErrnoText("socket(AF_UNIX)"));
    }

    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    if (config.socket_path.size() >= sizeof(address.sun_path)) {
        ::close(fd_);
        fd_ = -1;
        throw BridgeError("bridge socket path is too long: " + config.socket_path);
    }
    std::memcpy(address.sun_path, config.socket_path.c_str(), config.socket_path.size() + 1);

    if (::connect(fd_, reinterpret_cast<sockaddr *>(&address), sizeof(address)) != 0) {
        const std::string why = ErrnoText("connect(" + config.socket_path + ")");
        ::close(fd_);
        fd_ = -1;
        throw BridgeError(why);
    }

    Authenticate();
}

BridgeClient::~BridgeClient() {
    if (fd_ >= 0) {
        ::close(fd_);
    }
}

void BridgeClient::WriteFrame(FrameType type, const std::string &payload) {
    WriteFrame(type, payload.data(), payload.size());
}

void BridgeClient::WriteFrame(FrameType type, const void *data, std::size_t size) {
    if (size > kMaxPayload) {
        throw BridgeError("frame payload exceeds the cap");
    }

    unsigned char header[kHeaderSize];
    header[0] = static_cast<unsigned char>((size >> 24) & 0xFF);
    header[1] = static_cast<unsigned char>((size >> 16) & 0xFF);
    header[2] = static_cast<unsigned char>((size >> 8) & 0xFF);
    header[3] = static_cast<unsigned char>(size & 0xFF);
    header[4] = static_cast<unsigned char>(type);

    WriteAll(fd_, header, kHeaderSize);
    if (size > 0) {
        WriteAll(fd_, data, size);
    }
}

void BridgeClient::ReadFrame(FrameType *type, std::string *payload) {
    unsigned char header[kHeaderSize];
    ReadAll(fd_, header, kHeaderSize);

    const std::uint32_t length = (static_cast<std::uint32_t>(header[0]) << 24) |
                                 (static_cast<std::uint32_t>(header[1]) << 16) |
                                 (static_cast<std::uint32_t>(header[2]) << 8) |
                                 static_cast<std::uint32_t>(header[3]);

    if (length > kMaxPayload) {
        throw BridgeError("bridge sent an oversized frame");
    }

    *type = static_cast<FrameType>(header[4]);
    payload->resize(length);
    if (length > 0) {
        ReadAll(fd_, payload->data(), length);
    }
}

void BridgeClient::Authenticate() {
    // The auth request is the one frame served before a token is validated. The
    // token is an argument, not a header, matching BridgeProtocol's Request shape.
    // It is escaped so a token containing a quote cannot break the JSON.
    const char *token = std::getenv("VYZORIX_BRIDGE_TOKEN");
    const std::string auth_request =
        std::string("{\"id\":\"auth\",\"api\":\"auth\",\"args\":{\"token\":\"") +
        vjson::Escape(token != nullptr ? token : "") + "\"}}";

    WriteFrame(FrameType::Request, auth_request);

    FrameType type = FrameType::Response;
    std::string payload;
    ReadFrame(&type, &payload);

    if (type != FrameType::Response) {
        throw BridgeError(std::string("expected a response to auth, got ") + TypeName(type));
    }

    const vjson::Value response = vjson::Parse(payload);
    const vjson::Value *ok = response.Find("ok");
    if (ok == nullptr || ok->type != vjson::Value::Type::Bool || !ok->boolean) {
        const vjson::Value *error = response.Find("error");
        std::string detail = "authentication refused";
        if (error != nullptr) {
            const vjson::Value *d = error->Find("detail");
            if (d != nullptr && d->IsString()) {
                detail = d->string;
            }
        }
        throw BridgeError(detail);
    }

    // The result is itself a JSON document: {"session":"..."}. Read it, but do not
    // require it — a future bridge could omit it and the client would still work.
    const vjson::Value *result = response.Find("result");
    if (result != nullptr && result->IsString()) {
        const vjson::Value inner = vjson::Parse(result->string);
        const vjson::Value *session = inner.Find("session");
        if (session != nullptr && session->IsString()) {
            session_ = session->string;
        }
    }
}

std::string BridgeClient::Call(const std::string &api, const std::string &args_json) {
    const std::string id = std::to_string(next_id_++);
    const std::string request = std::string("{\"id\":\"") + id + "\",\"api\":\"" +
                                vjson::Escape(api) + "\",\"args\":" + args_json + "}";

    WriteFrame(FrameType::Request, request);

    // Read until the response to this request. A bridge never pushes an unsolicited
    // frame to a non-PTY client, but skipping unknown frames is cheap insurance
    // against a future event frame arriving here.
    while (true) {
        FrameType type = FrameType::Response;
        std::string payload;
        ReadFrame(&type, &payload);

        if (type != FrameType::Response) {
            continue;
        }

        const vjson::Value response = vjson::Parse(payload);
        const vjson::Value *response_id = response.Find("id");
        if (response_id != nullptr && response_id->IsString() && response_id->string != id) {
            continue;
        }

        const vjson::Value *ok = response.Find("ok");
        if (ok != nullptr && ok->type == vjson::Value::Type::Bool && ok->boolean) {
            const vjson::Value *result = response.Find("result");

            return result != nullptr && result->IsString() ? result->string : std::string();
        }

        const vjson::Value *error = response.Find("error");
        if (error != nullptr) {
            const vjson::Value *kind = error->Find("kind");
            const vjson::Value *detail = error->Find("detail");
            throw BridgeError(
                (kind != nullptr && kind->IsString() ? kind->string : std::string("error")) + ": " +
                (detail != nullptr && detail->IsString() ? detail->string : std::string()));
        }

        throw BridgeError("bridge returned neither a result nor an error");
    }
}

} // namespace android_bin
