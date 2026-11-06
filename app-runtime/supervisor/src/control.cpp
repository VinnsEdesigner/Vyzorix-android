// The control protocol — see control.h.

#include "vyzorix/control.h"

#include <cerrno>
#include <cstring>
#include <fstream>
#include <sstream>

#include <sys/select.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <unistd.h>

#include "vyzorix/frame.h"
#include "vyzorix/json.h"

namespace vsup {
namespace {

/** How often the accept loop wakes to check the stop flag. */
constexpr int kAcceptPollMs = 200;

/** How much of a log `logs` returns. The tail is what matters, not the head. */
constexpr std::size_t kLogTailBytes = 64 * 1024;

std::string StringArg(const vjson::Value &request, const char *key) {
    const vjson::Value *value = request.Find(key);

    return value != nullptr && value->IsString() ? value->string : std::string();
}

/**
 * Creates `path` and its parents. Returns false when it cannot.
 *
 * mkdir -p, one component at a time: a single mkdir(2) fails on a nested path whose
 * parent does not exist yet, which is the common case (`/home/apps/<name>`).
 */
bool EnsureDir(const std::string &path) {
    if (path.empty()) {
        return true;
    }

    std::string built;
    std::size_t start = 0;
    if (path[0] == '/') {
        built = "/";
        start = 1;
    }

    while (start <= path.size()) {
        const std::size_t slash = path.find('/', start);
        const std::string component =
            path.substr(start, slash == std::string::npos ? std::string::npos : slash - start);

        if (!component.empty()) {
            if (!built.empty() && built.back() != '/') {
                built.push_back('/');
            }
            built += component;

            if (::mkdir(built.c_str(), 0755) != 0 && errno != EEXIST) {
                return false;
            }
        }

        if (slash == std::string::npos) {
            break;
        }
        start = slash + 1;
    }

    return true;
}

std::string ReplyOk(const std::string &resultJson) {
    return std::string("{\"ok\":true,\"result\":") + resultJson + "}";
}

std::string ReplyError(const std::string &message) {
    return std::string("{\"ok\":false,\"error\":\"") + vjson::Escape(message) + "\"}";
}

std::string QuoteJson(const std::string &raw) {
    return "\"" + vjson::Escape(raw) + "\"";
}

/** The last [kLogTailBytes] of a file, or empty when it does not exist. */
std::string TailOf(const std::string &path, std::size_t maxBytes) {
    std::ifstream in(path, std::ios::binary);
    if (!in) {
        return std::string();
    }

    in.seekg(0, std::ios::end);
    const std::streamoff size = in.tellg();
    if (size <= 0) {
        return std::string();
    }

    const std::streamoff start = size > static_cast<std::streamoff>(maxBytes)
                                     ? size - static_cast<std::streamoff>(maxBytes)
                                     : 0;
    in.seekg(start);

    std::stringstream buffer;
    buffer << in.rdbuf();

    return buffer.str();
}

} // namespace

std::string HandleRequest(Manager *manager, const std::string &request_json) {
    vjson::Value request;
    try {
        request = vjson::Parse(request_json);
    } catch (const vjson::Error &e) {
        return ReplyError(std::string("bad request: ") + e.what());
    }

    const std::string op = StringArg(request, "op");
    const std::string name = StringArg(request, "name");

    if (op.empty()) {
        return ReplyError("request has no op");
    }

    try {
        if (op == op::kPing) {
            return ReplyOk("\"pong\"");
        }

        if (op == op::kList) {
            return ReplyOk(manager->RegistryJson());
        }

        // Every op below acts on a named app, so the name is required first — one
        // check, before any dispatch, rather than in each branch.
        if (name.empty()) {
            return ReplyError("op '" + op + "' requires a name");
        }

        if (op == op::kStart) {
            return manager->Start(name) ? ReplyOk(QuoteJson("started"))
                                        : ReplyError("could not start " + name);
        }

        if (op == op::kStop) {
            return manager->Stop(name) ? ReplyOk(QuoteJson("stopped"))
                                       : ReplyError("could not stop " + name);
        }

        if (op == op::kRestart) {
            return manager->Restart(name) ? ReplyOk(QuoteJson("restarted"))
                                          : ReplyError("could not restart " + name);
        }

        if (op == op::kLogs) {
            const std::string path = manager->LogPathFor(name);
            return ReplyOk(QuoteJson(TailOf(path, kLogTailBytes)));
        }

        if (op == op::kEnv) {
            const vapps::App *app = manager->registry().Find(name);
            if (app == nullptr) {
                return ReplyError("no app named " + name);
            }

            std::string json = "{";
            bool first = true;
            for (const auto &entry : app->env) {
                if (!first) {
                    json.push_back(',');
                }
                first = false;
                json += QuoteJson(entry.first) + ":" + QuoteJson(entry.second);
            }
            json += "}";

            return ReplyOk(json);
        }

        if (op == op::kCreate) {
            const std::string cmd = StringArg(request, "cmd");
            if (cmd.empty()) {
                return ReplyError("create requires a cmd");
            }

            const vjson::Value *portValue = request.Find("port");
            const vjson::Value *autostartValue = request.Find("autostart");

            vapps::App app;
            app.name = name;
            app.cmd = cmd;
            app.dir = StringArg(request, "dir");
            if (app.dir.empty()) {
                // Default the working directory to the app's own directory, which is
                // where a created app's files go.
                app.dir = "/home/apps/" + name;
            }
            app.port = portValue != nullptr && portValue->type == vjson::Value::Type::Number
                           ? static_cast<int>(portValue->number)
                           : 0;
            app.autostart = autostartValue != nullptr &&
                            autostartValue->type == vjson::Value::Type::Bool &&
                            autostartValue->boolean;

            const vjson::Value *env = request.Find("env");
            if (env != nullptr && env->type == vjson::Value::Type::Object) {
                for (const auto &entry : env->object) {
                    if (entry.second.IsString()) {
                        app.env[entry.first] = entry.second.string;
                    }
                }
            }

            // The directory must exist before the app is started in it: the child
            // chdirs into `dir`, and a missing one is an app that exits instantly
            // with only a log line to explain it. Creating it here is what makes
            // `create` + `start` work without a manual mkdir.
            if (!EnsureDir(app.dir)) {
                return ReplyError("could not create " + app.dir);
            }

            manager->mutableRegistry().Upsert(app);
            manager->Persist();

            return ReplyOk(QuoteJson("created"));
        }

        if (op == op::kRemove) {
            if (!manager->mutableRegistry().Remove(name)) {
                return ReplyError("no app named " + name);
            }
            manager->Persist();

            return ReplyOk(QuoteJson("removed"));
        }

        if (op == op::kExpose) {
            const vjson::Value *bind = request.Find("bind");
            const vjson::Value *token = request.Find("token");
            const std::string bindAddress =
                bind != nullptr && bind->IsString() ? bind->string : "127.0.0.1";
            const std::string tokenValue =
                token != nullptr && token->IsString() ? token->string : "";

            // The refusal that matters (§7.2) lives in the manager, which is where
            // the proxy is started: checking it here as well would be two places to
            // keep the rule, and the one that matters is the one that binds.
            std::string error;
            if (!manager->Expose(name, bindAddress, tokenValue, &error)) {
                return ReplyError(error);
            }

            return ReplyOk(QuoteJson(manager->ExposedAddress(name)));
        }

        if (op == op::kUnexpose) {
            if (!manager->Unexpose(name)) {
                return ReplyError("no app named " + name);
            }

            return ReplyOk(QuoteJson("unexposed"));
        }

        return ReplyError("unknown op '" + op + "'");
    } catch (const std::exception &e) {
        return ReplyError(std::string("supervisor error: ") + e.what());
    }
}

ControlServer::ControlServer(Manager *manager, std::string socketPath)
    : manager_(manager), socketPath_(std::move(socketPath)) {}

ControlServer::~ControlServer() {
    Stop();
}

bool ControlServer::Start() {
    try {
        listenFd_ = vframe::Listen(socketPath_);
    } catch (const vframe::Error &) {
        return false;
    }

    // The watcher is armed on the apps directory, not on the registry file: the file
    // is replaced on every save (write-temp-then-rename), which would kill a watch on
    // it. A failure here is not fatal — the supervisor then does not live-reload, and
    // everything else still works.
    const std::size_t slash = manager_->registryPath().find_last_of('/');
    const std::string directory =
        slash == std::string::npos ? "." : manager_->registryPath().substr(0, slash);
    registryName_ = manager_->registryPath().substr(slash + 1);

    if (!watcher_.Watch(directory)) {
        std::fprintf(stderr, "supervisor: cannot watch %s; registry edits need a restart\n",
                     directory.c_str());
    }

    return true;
}

void ControlServer::Stop() {
    stopping_ = true;
    if (listenFd_ >= 0) {
        ::close(listenFd_);
        listenFd_ = -1;
    }
    ::unlink(socketPath_.c_str());
}

void ControlServer::Run() {
    while (!stopping_) {
        // **select(2), not poll(2)**, and that is not a style choice.
        //
        // Android's app seccomp filter blocks `poll` outright, and the conversion
        // proot must perform to rescue it (poll -> ppoll) has to build a `timespec`
        // inside the tracee's address space. That allocation, from the SIGSYS
        // context, crashed the tracee with SIGSEGV — the supervisor started, served
        // nothing, and died in under a second. `select` needs no such allocation:
        // its conversion (select -> pselect6) only NULLs the signal-mask argument.
        //
        // The timeout is kept because this loop is also the manager's only tick —
        // it is where a crashed app's restart is scheduled — so it must run even
        // when no client connects.
        fd_set readable;
        FD_ZERO(&readable);
        FD_SET(listenFd_, &readable);

        // The watcher is waited on in the same select, so a registry edit is noticed
        // within the tick rather than on the next client connect.
        int highest = listenFd_;
        if (watcher_.IsWatching()) {
            FD_SET(watcher_.fd(), &readable);
            if (watcher_.fd() > highest) {
                highest = watcher_.fd();
            }
        }

        struct timeval timeout;
        timeout.tv_sec = 0;
        timeout.tv_usec = kAcceptPollMs * 1000;

        const int ready = ::select(highest + 1, &readable, nullptr, nullptr, &timeout);
        if (stopping_) {
            break;
        }
        if (ready <= 0) {
            // Nothing to accept. Still drive the manager: this loop is the only
            // place it is touched, so it is also where restarts are scheduled.
            manager_->Poll();

            continue;
        }

        // A registry edit is a re-read, never a restart: the apps that are running
        // stay running, and the set of apps that *should* be is brought up to date.
        if (watcher_.IsWatching() && FD_ISSET(watcher_.fd(), &readable)) {
            const vwatch::Change change = watcher_.Drain(registryName_);
            if (change.registryChanged || change.overflowed || !change.createdDirs.empty()) {
                try {
                    manager_->Reload();
                    manager_->StartAutostart();
                } catch (const std::exception &e) {
                    // A half-written or malformed registry must not stop the
                    // supervisor; the next event retries.
                    std::fprintf(stderr, "supervisor: reload failed: %s\n", e.what());
                }
            }
        }

        if (!FD_ISSET(listenFd_, &readable)) {
            manager_->Poll();

            continue;
        }

        const int client = ::accept(listenFd_, nullptr, nullptr);
        if (client < 0) {
            if (errno == EINTR || errno == EAGAIN) {
                continue;
            }
            // A transient failure must not take the supervisor down: it is the only
            // thing serving the control socket, and the engine, the bridge and the
            // terminal all depend on it staying up. Only a closed listener is fatal.
            std::fprintf(stderr, "supervisor: accept failed: %s\n", std::strerror(errno));
            if (errno == EBADF || errno == EINVAL) {
                break;
            }

            continue;
        }

        // ServeOne owns `client` from here — its Connection closes it — so there is
        // deliberately no close() in this loop. A second close would close whatever
        // descriptor the number had been reused for.
        ServeOne(client);

        manager_->Poll();
    }
}

void ControlServer::ServeOne(int fd) {
    vframe::Connection connection(fd);

    std::string request;
    try {
        connection.Read(&request);
    } catch (const vframe::Error &) {
        // A client that connects and sends nothing, or a truncated frame. Nothing to
        // answer; closing is the right response.
        return;
    }

    const std::string reply = HandleRequest(manager_, request);
    try {
        connection.Write(static_cast<std::uint8_t>(ControlType::Reply), reply);
    } catch (const vframe::Error &) {
        // The client went away before reading. Not an error worth logging loudly.
    }
}

} // namespace vsup
