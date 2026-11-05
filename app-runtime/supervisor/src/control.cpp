// The control protocol — see control.h.

#include "vyzorix/control.h"

#include <cerrno>
#include <cstring>
#include <fstream>
#include <sstream>

#include <poll.h>
#include <sys/socket.h>
#include <sys/stat.h>
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

    const std::streamoff start =
        size > static_cast<std::streamoff>(maxBytes) ? size - static_cast<std::streamoff>(maxBytes)
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
            const std::string tokenValue = token != nullptr && token->IsString() ? token->string : "";

            // The refusal that matters (§7.2): 0.0.0.0 is every interface, so a
            // flasher on a hotspot would be reachable by anyone on it. Without a
            // token this is refused rather than warned about.
            if (bindAddress != "127.0.0.1" && bindAddress != "::1" && tokenValue.empty()) {
                return ReplyError(
                    "refusing to bind " + bindAddress +
                    " without a token: that would expose the app to the whole network");
            }

            // The proxy itself is a separate process (app-proxy); this records the
            // intent and reports the URL an operator would use.
            const vapps::App *app = manager->registry().Find(name);
            if (app == nullptr) {
                return ReplyError("no app named " + name);
            }

            return ReplyOk(QuoteJson(bindAddress + ":" + std::to_string(app->port)));
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
        // Poll before accept so the loop can observe `stopping_` without a client:
        // a blocking accept would make shutdown wait for one.
        struct pollfd pfd {};
        pfd.fd = listenFd_;
        pfd.events = POLLIN;

        const int ready = ::poll(&pfd, 1, kAcceptPollMs);
        if (stopping_) {
            break;
        }
        if (ready <= 0) {
            // Nothing to accept. Still drive the manager: this loop is the only
            // place it is touched, so it is also where restarts are scheduled.
            manager_->Poll();

            continue;
        }

        const int client = ::accept(listenFd_, nullptr, nullptr);
        if (client < 0) {
            if (errno == EINTR || errno == EAGAIN) {
                continue;
            }
            // The listener was closed under us (Stop). Leave.
            break;
        }

        ServeOne(client);
        ::close(client);

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
