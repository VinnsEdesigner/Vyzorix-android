// The android.sock client.
//
// One connection to Layer 1's bridge, with the frame shape from the architecture
//
//     [4B length][1B type][payload]
//
// `length` is the payload length, big-endian. The connection authenticates once
// with the session token from the environment, then issues requests. This is the
// device half of the contract in android-core's BridgeProtocol.kt; the two must
// agree byte for byte.

#ifndef VYZORIX_ANDROID_BIN_BRIDGE_H
#define VYZORIX_ANDROID_BIN_BRIDGE_H

#include <cstdint>
#include <stdexcept>
#include <string>

namespace android_bin {

/** Thrown for any connection, framing or protocol failure. */
class BridgeError : public std::runtime_error {
public:
    explicit BridgeError(const std::string &what) : std::runtime_error(what) {}
};

/** The frame types, matching BridgeProtocol.FrameType. */
enum class FrameType : std::uint8_t {
    Request = 0x01,
    Response = 0x02,
    PtyOutput = 0x03,
    PtyInput = 0x04,
    PtyCredit = 0x05,
    Event = 0x06,
};

/** Where to find the bridge, and who we are. */
struct BridgeConfig {
    /** `VYZORIX_BRIDGE_SOCKET`, bound into the container at /tmp/android.sock. */
    std::string socket_path;
    /** `VYZORIX_BRIDGE_TOKEN`, the per-session capability token. */
    std::string token;
};

/**
 * Reads the bridge configuration from the environment.
 *
 * Both variables are set by the proot launcher at exec:
 * the token travels in the environment, never on disk or in argv). A missing one
 * is a hard error rather than a default: connecting without a token cannot
 * authenticate, and a wrong socket path is a silent hang.
 */
BridgeConfig ConfigFromEnvironment();

/** A live, authenticated connection to the bridge. */
class BridgeClient {
public:
    /** Connects and authenticates. Throws [BridgeError] on failure. */
    explicit BridgeClient(const BridgeConfig &config);

    ~BridgeClient();

    BridgeClient(const BridgeClient &) = delete;
    BridgeClient &operator=(const BridgeClient &) = delete;

    /**
     * Calls an API and returns its `result` string.
     *
     * `args` is a pre-encoded JSON object body (`{"k":"v",...}`), because the
     * caller is a generated shim that already knows its own arguments. Throws
     * [BridgeError] when the bridge reports an error, so a CLI exits non-zero and
     * prints the detail.
     */
    std::string Call(const std::string &api, const std::string &args_json);

    /** The session id the bridge reported at authentication. */
    const std::string &session() const {
        return session_;
    }

private:
    void WriteFrame(FrameType type, const std::string &payload);
    void WriteFrame(FrameType type, const void *data, std::size_t size);

    /** Reads one frame. Throws on EOF or a malformed header. */
    void ReadFrame(FrameType *type, std::string *payload);

    void Authenticate();

    int fd_ = -1;
    std::string session_;
    std::uint64_t next_id_ = 1;
};

} // namespace android_bin

#endif // VYZORIX_ANDROID_BIN_BRIDGE_H
