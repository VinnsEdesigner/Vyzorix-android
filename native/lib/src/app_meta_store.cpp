// Per-app metadata — see meta.h.

#include "vyzorix/app_meta_store.h"

#include <cerrno>
#include <cstring>
#include <fstream>
#include <sstream>

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include "vyzorix/vyzorix_json.h"

namespace vmeta {
namespace {

constexpr const char *kExposedKey = "exposed";
constexpr const char *kExposeKey = "expose";
constexpr const char *kBindKey = "bind";
constexpr const char *kPortKey = "port";
constexpr const char *kTokenKey = "token";

std::string StringField(const vjson::Value &object, const char *key) {
    const vjson::Value *value = object.Find(key);

    return value != nullptr && value->IsString() ? value->string : std::string();
}

/** JSON string escaping, so a token containing a quote cannot break the file. */
void AppendString(std::string *out, const std::string &value) {
    out->push_back('"');
    *out += vjson::Escape(value);
    out->push_back('"');
}

} // namespace

std::string MetaPath(const std::string &metaDir, const std::string &name) {
    return metaDir + "/" + name + ".json";
}

bool EnsureDir(const std::string &metaDir) {
    if (metaDir.empty()) {
        return true;
    }
    if (::mkdir(metaDir.c_str(), 0700) == 0 || errno == EEXIST) {
        return true;
    }

    return false;
}

AppMeta Load(const std::string &metaDir, const std::string &name) {
    AppMeta meta;
    meta.name = name;

    std::ifstream in(MetaPath(metaDir, name));
    if (!in) {
        // No file: the app has never been exposed. Not an error.
        return meta;
    }

    std::stringstream buffer;
    buffer << in.rdbuf();

    const vjson::Value document = vjson::Parse(buffer.str());

    const vjson::Value *exposed = document.Find(kExposedKey);
    meta.exposed =
        exposed != nullptr && exposed->type == vjson::Value::Type::Bool && exposed->boolean;

    const vjson::Value *expose = document.Find(kExposeKey);
    if (expose != nullptr && expose->type == vjson::Value::Type::Object) {
        const std::string bind = StringField(*expose, kBindKey);
        if (!bind.empty()) {
            meta.expose.bind = bind;
        }

        const vjson::Value *port = expose->Find(kPortKey);
        if (port != nullptr && port->type == vjson::Value::Type::Number) {
            meta.expose.port = static_cast<int>(port->number);
        }

        meta.expose.token = StringField(*expose, kTokenKey);
    }

    return meta;
}

void Save(const std::string &metaDir, const AppMeta &meta) {
    EnsureDir(metaDir);

    std::string body = "{\"name\":";
    AppendString(&body, meta.name);
    body += ",\"exposed\":";
    body += meta.exposed ? "true" : "false";
    body += ",\"expose\":{\"bind\":";
    AppendString(&body, meta.expose.bind);
    body += ",\"port\":" + std::to_string(meta.expose.port);
    body += ",\"token\":";
    AppendString(&body, meta.expose.token);
    body += "}}";

    const std::string path = MetaPath(metaDir, meta.name);
    const std::string temp = path + ".tmp";

    // 0600: the file may hold an expose token. Nothing outside the app's uid can
    // read app-private storage anyway, but the mode is what says the intent.
    const int fd = ::open(temp.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd < 0) {
        throw vjson::Error("cannot write " + temp + ": " + std::strerror(errno));
    }

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

    ::fsync(fd);
    ::close(fd);

    if (::rename(temp.c_str(), path.c_str()) != 0) {
        const std::string why = std::strerror(errno);
        ::unlink(temp.c_str());
        throw vjson::Error("cannot replace " + path + ": " + why);
    }
}

bool Remove(const std::string &metaDir, const std::string &name) {
    return ::unlink(MetaPath(metaDir, name).c_str()) == 0;
}

} // namespace vmeta
