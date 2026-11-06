// The app registry — see apps.h.

#include "vyzorix/app_registry.h"

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <sstream>

#include <fcntl.h>
#include <unistd.h>

#include "vyzorix/vyzorix_json.h"

namespace vapps {
namespace {

/** The object member holding the app array. */
constexpr const char *kAppsKey = "apps";

/** App field names, in one place so read and write cannot drift. */
constexpr const char *kName = "name";
constexpr const char *kDir = "dir";
constexpr const char *kCmd = "cmd";
constexpr const char *kPort = "port";
constexpr const char *kEnv = "env";
constexpr const char *kAutostart = "autostart";
constexpr const char *kPid = "pid";
constexpr const char *kPidStartTicks = "pidStartTicks";

std::string StringField(const vjson::Value &object, const char *key) {
    const vjson::Value *value = object.Find(key);

    return value != nullptr && value->IsString() ? value->string : std::string();
}

int IntField(const vjson::Value &object, const char *key) {
    const vjson::Value *value = object.Find(key);
    if (value == nullptr || value->type != vjson::Value::Type::Number) {
        return 0;
    }

    return static_cast<int>(value->number);
}

bool BoolField(const vjson::Value &object, const char *key) {
    const vjson::Value *value = object.Find(key);

    return value != nullptr && value->type == vjson::Value::Type::Bool && value->boolean;
}

App AppFromJson(const vjson::Value &object) {
    App app;
    app.name = StringField(object, kName);
    app.dir = StringField(object, kDir);
    app.cmd = StringField(object, kCmd);
    app.port = IntField(object, kPort);
    app.autostart = BoolField(object, kAutostart);
    app.pid = IntField(object, kPid);
    app.pidStartTicks = static_cast<std::uint64_t>(IntField(object, kPidStartTicks));

    const vjson::Value *env = object.Find(kEnv);
    if (env != nullptr && env->type == vjson::Value::Type::Object) {
        for (const auto &entry : env->object) {
            if (entry.second.IsString()) {
                app.env[entry.first] = entry.second.string;
            }
        }
    }

    return app;
}

void AppendJsonString(std::string *out, const std::string &value) {
    out->push_back('"');
    *out += vjson::Escape(value);
    out->push_back('"');
}

} // namespace

Registry Registry::Load(const std::string &path) {
    Registry registry;

    std::ifstream in(path);
    if (!in) {
        // No file yet: the state before the first app-create. Not an error.
        return registry;
    }

    std::stringstream buffer;
    buffer << in.rdbuf();

    const vjson::Value document = vjson::Parse(buffer.str());
    const vjson::Value *apps = document.Find(kAppsKey);
    if (apps == nullptr) {
        // An empty or hand-written `{}` is a registry with no apps.
        return registry;
    }
    if (apps->type != vjson::Value::Type::Array) {
        throw vjson::Error("apps.json: 'apps' is not an array");
    }

    for (const vjson::Value &entry : apps->array) {
        if (entry.type != vjson::Value::Type::Object) {
            throw vjson::Error("apps.json: an entry is not an object");
        }
        App app = AppFromJson(entry);
        if (app.name.empty()) {
            throw vjson::Error("apps.json: an entry has no name");
        }
        registry.Upsert(app);
    }

    return registry;
}

std::string Registry::ToJson() const {
    std::string out = "{\"apps\":[";

    for (std::size_t i = 0; i < apps_.size(); i++) {
        const App &app = apps_[i];
        if (i > 0) {
            out.push_back(',');
        }

        out += "{\"name\":";
        AppendJsonString(&out, app.name);
        out += ",\"dir\":";
        AppendJsonString(&out, app.dir);
        out += ",\"cmd\":";
        AppendJsonString(&out, app.cmd);
        out += ",\"port\":" + std::to_string(app.port);
        out += ",\"autostart\":";
        out += app.autostart ? "true" : "false";

        out += ",\"env\":{";
        bool first = true;
        for (const auto &entry : app.env) {
            if (!first) {
                out.push_back(',');
            }
            first = false;
            AppendJsonString(&out, entry.first);
            out.push_back(':');
            AppendJsonString(&out, entry.second);
        }
        out += "}";

        out += ",\"pid\":" + std::to_string(app.pid);
        out += ",\"pidStartTicks\":" + std::to_string(app.pidStartTicks);
        out.push_back('}');
    }

    out += "]}";

    return out;
}

void Registry::Save(const std::string &path) const {
    // Write-temp-then-rename in the same directory, so rename(2) is atomic (it is
    // only atomic within a filesystem). A crash leaves the old file or the new one.
    const std::string temp = path + ".tmp";

    const int fd = ::open(temp.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd < 0) {
        throw vjson::Error("cannot write " + temp + ": " + std::strerror(errno));
    }

    const std::string body = ToJson();
    std::size_t written = 0;
    while (written < body.size()) {
        const ssize_t n = ::write(fd, body.data() + written, body.size() - written);
        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }
            const std::string why = std::strerror(errno);
            ::close(fd);
            ::unlink(temp.c_str());
            throw vjson::Error("cannot write " + temp + ": " + why);
        }
        written += static_cast<std::size_t>(n);
    }

    // fsync before rename: without it a power loss can leave the renamed file with
    // no contents on some filesystems.
    ::fsync(fd);
    ::close(fd);

    if (::rename(temp.c_str(), path.c_str()) != 0) {
        const std::string why = std::strerror(errno);
        ::unlink(temp.c_str());
        throw vjson::Error("cannot replace " + path + ": " + why);
    }
}

App *Registry::Find(const std::string &name) {
    for (App &app : apps_) {
        if (app.name == name) {
            return &app;
        }
    }

    return nullptr;
}

const App *Registry::Find(const std::string &name) const {
    for (const App &app : apps_) {
        if (app.name == name) {
            return &app;
        }
    }

    return nullptr;
}

void Registry::Upsert(const App &app) {
    if (App *existing = Find(app.name)) {
        *existing = app;

        return;
    }

    apps_.push_back(app);
}

bool Registry::Remove(const std::string &name) {
    for (auto it = apps_.begin(); it != apps_.end(); ++it) {
        if (it->name == name) {
            apps_.erase(it);

            return true;
        }
    }

    return false;
}

std::uint64_t StartTicksOf(int pid) {
    if (pid <= 0) {
        return 0;
    }

    const std::string path = "/proc/" + std::to_string(pid) + "/stat";
    std::ifstream in(path);
    if (!in) {
        return 0;
    }

    std::string line;
    std::getline(in, line);

    // The 2nd field is the executable name in parentheses and may itself contain
    // spaces and parentheses, so field splitting must start after the LAST ')'.
    const std::size_t close = line.rfind(')');
    if (close == std::string::npos) {
        return 0;
    }

    // Fields after the name: state is field 3, so startticks (field 22) is the
    // 20th token after the ')'. Count them.
    std::istringstream rest(line.substr(close + 1));
    std::string token;
    int field = 2; // the name we just skipped is field 2
    while (rest >> token) {
        field++;
        if (field == 22) {
            errno = 0;
            const unsigned long long ticks = std::strtoull(token.c_str(), nullptr, 10);

            return errno == 0 ? ticks : 0;
        }
    }

    return 0;
}

bool IsSameProcess(int pid, std::uint64_t startTicks) {
    if (pid <= 0 || startTicks == 0) {
        return false;
    }

    return StartTicksOf(pid) == startTicks;
}

} // namespace vapps
