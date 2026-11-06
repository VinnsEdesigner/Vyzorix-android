// The supervisor's control protocol.
//
// The wire is the repo's framed shape (`native/lib/frame.h`):
//
//     [4B length][1B type][payload]
//
// One request per connection: a client connects, sends one frame, reads one reply,
// and closes. That is enough for a CLI, it needs no session state, and it makes the
// supervisor's handling of a client a single function with no lifecycle to leak.
//
// The payload is JSON in both directions, reusing the repo's reader:
//
//   request : {"op": "start", "name": "flasher"}
//   reply   : {"ok": true, "result": {...}}  |  {"ok": false, "error": "..."}
//
// The `op` set is deliberately closed. An unknown op is refused, not ignored, so a
// CLI and a supervisor from different builds fail loudly rather than silently doing
// nothing.

#ifndef VYZORIX_CONTROL_H
#define VYZORIX_CONTROL_H

#include <cstdint>
#include <string>

#include "vyzorix/supervisor.h"
#include "vyzorix/watch.h"

namespace vsup {

/** Frame types on the control socket. One request, one reply. */
enum class ControlType : std::uint8_t {
    Request = 0x01,
    Reply = 0x02,
};

/** The ops a client may ask for. */
namespace op {
constexpr const char *kStart = "start";
constexpr const char *kStop = "stop";
constexpr const char *kRestart = "restart";
constexpr const char *kList = "list";
constexpr const char *kLogs = "logs";
constexpr const char *kEnv = "env";
constexpr const char *kCreate = "create";
constexpr const char *kRemove = "remove";
constexpr const char *kPing = "ping";
constexpr const char *kExpose = "expose";
constexpr const char *kUnexpose = "unexpose";
} // namespace op

/**
 * Handles one request and returns the reply payload.
 *
 * Pure with respect to the socket — it takes a JSON request and returns a JSON
 * reply — so it can be tested without a socket at all, which is where the op
 * semantics are pinned.
 */
std::string HandleRequest(Manager *manager, const std::string &request_json);

/**
 * Serves the control socket until `stop` is set.
 *
 * Runs in its own thread; `manager` is only touched from here, and the caller must
 * not touch it concurrently. `stop` is polled with a short accept timeout so a
 * shutdown does not wait for a client.
 */
class ControlServer {
public:
    ControlServer(Manager *manager, std::string socketPath);

    ~ControlServer();

    /** Binds the socket. Returns false and logs on failure. */
    bool Start();

    /**
     * The accept loop. Blocks until [Stop] is called.
     *
     * Call it on its own thread. It also drives [Manager::Poll], because the manager
     * is only touched from here — that is what keeps the supervisor free of locks.
     */
    void Run();

    /** Asks the loop to stop and closes the listening socket. */
    void Stop();

    const std::string &socketPath() const {
        return socketPath_;
    }

private:
    void ServeOne(int fd);

    Manager *manager_;
    std::string socketPath_;
    int listenFd_ = -1;
    volatile bool stopping_ = false;

    /**
     * Watches the app directory, so an edited registry is picked up without a
     * restart. Inactive when inotify is unavailable; the supervisor then simply
     * does not live-reload, which is what it did before this existed.
     */
    vwatch::DirWatcher watcher_;

    /** The registry file's name, for the watcher's filter. */
    std::string registryName_;
};

} // namespace vsup

#endif // VYZORIX_CONTROL_H
