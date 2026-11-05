// app-* — the operator's handle on the supervisor, inside the container.
//
// One binary, invoked under many names, exactly like android-bin: the program's
// basename selects the op. `app-start web` and `app-start` are the same call.
//
//   app-start NAME       start an app
//   app-stop NAME        stop it
//   app-restart NAME     stop then start
//   app-list             list apps and their state
//   app-logs NAME        the tail of an app's log
//   app-env NAME         the environment an app runs with
//   app-create NAME CMD  declare an app
//   app-remove NAME      forget an app
//   app-expose NAME      how to reach an app (bind + token rule)
//
// Each prints machine-readable output and exits non-zero on failure, so it works
// from a terminal and from a script. The supervisor's socket comes from
// VYZORIX_APPS_SOCKET, defaulting to the layout the architecture fixes.
//
// Why C++ and not a bash wrapper: the reply is JSON and the framing is binary. A
// shell script would need `head -c`, `dd`, and a JSON parser — three ways to be
// subtly wrong — for something the shared lib already does correctly.

#include <cstdlib>
#include <exception>
#include <iostream>
#include <string>
#include <vector>

#include "vyzorix/frame.h"
#include "vyzorix/json.h"

namespace {

constexpr const char *kDefaultSocket = "/home/apps/supervisor.sock";

/**
 * The request frame type, from the control protocol.
 *
 * Duplicated from the supervisor's header on purpose: the CLI is a *client* of the
 * protocol, and including the server's header would pull the whole lifecycle in for
 * one byte. The value is part of the wire contract, so it is stated here as such.
 */
constexpr std::uint8_t kControlRequest = 0x01;

std::string ProgramName(const char *argv0) {
    const std::string path = argv0 != nullptr ? argv0 : "";
    const std::size_t slash = path.find_last_of('/');

    return slash == std::string::npos ? path : path.substr(slash + 1);
}

std::string SocketPath() {
    const char *env = std::getenv("VYZORIX_APPS_SOCKET");

    return env != nullptr && *env != '\0' ? env : kDefaultSocket;
}

void PrintUsage(const std::string &name) {
    std::cerr << "usage: " << name << " [args]\n"
              << "\n"
              << "  app-start NAME        start an app\n"
              << "  app-stop NAME         stop an app\n"
              << "  app-restart NAME      restart an app\n"
              << "  app-list              list apps\n"
              << "  app-logs NAME         show an app's log\n"
              << "  app-env NAME          show an app's environment\n"
              << "  app-create NAME CMD   declare an app\n"
              << "  app-remove NAME       forget an app\n"
              << "  app-expose NAME       show how to reach an app\n"
              << "\n"
              << "The supervisor socket is VYZORIX_APPS_SOCKET (default "
              << kDefaultSocket << ").\n";
}

/** Escapes a string as a JSON string body. */
std::string Quote(const std::string &raw) {
    return "\"" + vjson::Escape(raw) + "\"";
}

/** A reply: the parsed value, and the raw text for a caller that prints it. */
struct Reply {
    vjson::Value value;
    std::string raw;
};

/** Sends one request and returns the reply. Throws on a transport failure. */
Reply Ask(const std::string &op, const std::vector<std::pair<std::string, std::string>> &args) {
    std::string request = "{\"op\":" + Quote(op);
    for (const auto &arg : args) {
        request += "," + Quote(arg.first) + ":" + arg.second;
    }
    request += "}";

    vframe::Connection connection(SocketPath());
    connection.Write(kControlRequest, request);

    Reply reply;
    connection.Read(&reply.raw);
    reply.value = vjson::Parse(reply.raw);

    return reply;
}

/** The value of `result` re-encoded as JSON, for `app-list`. */
std::string ResultJson(const std::string &raw_reply) {
    // The reply is {"ok":true,"result":<json>}. The result is a value, not a string,
    // so it is sliced out of the raw text rather than unescaped from a string field —
    // re-encoding a parsed value would be a second serialiser to keep in step.
    const std::size_t key = raw_reply.find("\"result\":");
    if (key == std::string::npos) {
        return "{}";
    }

    return raw_reply.substr(key + 9, raw_reply.size() - (key + 9) - 1);
}

/** Runs the op and prints the result. Returns the process exit code. */
int Run(const std::string &name, const std::vector<std::string> &args) {
    // The op is the basename minus the `app-` prefix; a bare `android-bin`-style
    // invocation via `app-tool` is not a thing, so a name without the prefix is
    // treated as the op itself (which is what a symlink named `app-list` gives).
    std::string op = name;
    if (op.rfind("app-", 0) == 0) {
        op = op.substr(4);
    }

    std::vector<std::pair<std::string, std::string>> request;

    if (op == "list") {
        // no arguments
    } else if (op == "create") {
        if (args.size() < 2) {
            std::cerr << name << ": needs a NAME and a CMD\n";

            return 2;
        }
        request.emplace_back("name", Quote(args[0]));
        request.emplace_back("cmd", Quote(args[1]));

        // Optional flags after the command, as key=value, so a create is one line
        // and needs no option parser.
        for (std::size_t i = 2; i < args.size(); i++) {
            const std::string &flag = args[i];
            const std::size_t equals = flag.find('=');
            if (equals == std::string::npos) {
                continue;
            }
            const std::string key = flag.substr(0, equals);
            const std::string value = flag.substr(equals + 1);

            if (key == "port") {
                request.emplace_back("port", value);
            } else if (key == "autostart") {
                request.emplace_back("autostart", value == "true" ? "true" : "false");
            } else if (key == "dir") {
                request.emplace_back("dir", Quote(value));
            }
        }
    } else {
        if (args.empty()) {
            std::cerr << name << ": needs a NAME\n";

            return 2;
        }
        request.emplace_back("name", Quote(args[0]));

        // app-expose takes the bind and token the supervisor enforces the rule on.
        if (op == "expose") {
            for (std::size_t i = 1; i < args.size(); i++) {
                const std::size_t equals = args[i].find('=');
                if (equals == std::string::npos) {
                    continue;
                }
                const std::string key = args[i].substr(0, equals);
                const std::string value = args[i].substr(equals + 1);
                if (key == "bind") {
                    request.emplace_back("bind", Quote(value));
                } else if (key == "token") {
                    request.emplace_back("token", Quote(value));
                }
            }
        }
    }

    const Reply reply = Ask(op, request);
    const vjson::Value *ok = reply.value.Find("ok");
    const bool succeeded = ok != nullptr && ok->boolean;

    if (!succeeded) {
        const vjson::Value *error = reply.value.Find("error");
        std::cerr << name << ": "
                  << (error != nullptr && error->IsString() ? error->string : "unknown error")
                  << "\n";

        return 1;
    }

    // `list` returns the registry object and prints it as JSON, so `app-list` is
    // pipeable and `app-list | jq` works. Every other op returns a string, printed
    // as-is (a log body is text, not JSON).
    if (op == "list") {
        std::cout << ResultJson(reply.raw) << "\n";

        return 0;
    }

    const vjson::Value *result = reply.value.Find("result");
    if (result == nullptr) {
        return 0;
    }

    if (result->IsString()) {
        std::cout << result->string;
        if (result->string.empty() || result->string.back() != '\n') {
            std::cout << "\n";
        }
    } else {
        // A non-string result (env is an object): print it as JSON.
        std::cout << ResultJson(reply.raw) << "\n";
    }

    return 0;
}

} // namespace

int main(int argc, char **argv) {
    const std::string name = ProgramName(argc > 0 ? argv[0] : "");

    if (argc > 1) {
        const std::string first = argv[1];
        if (first == "--help" || first == "-h") {
            PrintUsage(name);

            return 0;
        }
    }

    std::vector<std::string> args;
    for (int i = 1; i < argc; i++) {
        args.emplace_back(argv[i]);
    }

    try {
        return Run(name, args);
    } catch (const std::exception &e) {
        std::cerr << name << ": " << e.what() << "\n";

        return 1;
    }
}
