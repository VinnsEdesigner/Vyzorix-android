// android-bin — the container-side client for the Layer 1 bridge.
//
// One binary, invoked under many names. Each name is an Android API (`camera`,
// `battery`, `network`, ...) and each invocation turns its argv into that API's
// arguments and prints the result. The per-name symlinks are created at build time
// by the generator; see apis.txt and generator.cpp.
//
// Usage:
//   <api> [key=value ...]        call the API with those arguments
//   <api> --json '{"k":"v"}'     call the API with a pre-encoded argument object
//   <api> --list                 print the API names this binary knows
//   <api> --help
//
// The API name is the program's basename, so `camera` and a symlink to this binary
// named `camera` behave identically. `--api NAME` overrides the basename, for when
// a caller cannot control argv[0].
//
// The bridge socket and the session token come from the environment, set by the
// proot launcher when it starts the shell (VYZORIX_BRIDGE_SOCKET,
// VYZORIX_BRIDGE_TOKEN). There is no configuration file and no secret on disk.

#include <cstddef>
#include <exception>
#include <iostream>
#include <set>
#include <stdexcept>
#include <string>
#include <vector>

#include "api_catalog.h"
#include "bridge.h"
#include "command_catalog.h"
#include "command_resolve.h"
#include "vyzorix/vyzorix_json.h"

namespace {

/** The program's own name, i.e. the API it is being invoked as. */
std::string ProgramName(const char *argv0) {
    std::string path = argv0 != nullptr ? argv0 : "";
    const std::size_t slash = path.find_last_of('/');

    return slash == std::string::npos ? path : path.substr(slash + 1);
}

void PrintUsage(const std::string &name) {
    std::cout << "usage: " << name << " [key=value ...]\n"
              << "       " << name << " --json '{\"k\":\"v\"}'\n"
              << "       " << name << " --api NAME [key=value ...]\n"
              << "       " << name << " --list\n"
              << "\n"
              << "Calls the Android bridge API `" << name << "` and prints its result.\n"
              << "The bridge socket and session token come from the environment\n"
              << "(VYZORIX_BRIDGE_SOCKET, VYZORIX_BRIDGE_TOKEN).\n";
}

/** Turns `key=value` arguments into a JSON object body. */
std::string ArgsFromPairs(const std::vector<std::string> &pairs) {
    std::string json = "{";
    bool first = true;

    for (const std::string &pair : pairs) {
        const std::size_t equals = pair.find('=');
        if (equals == std::string::npos || equals == 0) {
            throw std::runtime_error("argument is not key=value: " + pair);
        }

        if (!first) {
            json += ",";
        }
        first = false;

        const std::string key = pair.substr(0, equals);
        const std::string value = pair.substr(equals + 1);
        json += "\"" + vjson::Escape(key) + "\":\"" + vjson::Escape(value) + "\"";
    }

    json += "}";

    return json;
}

} // namespace

int main(int argc, char **argv) {
    // The program name is the *command* (`bt`, `admin`), not the wire API. Resolve it
    // to the API via the command catalog, consuming a subcommand argument when the
    // command has one (`bt ctrl` -> bluetooth_control). `--api NAME` still overrides
    // this entirely, so the raw wire name is always reachable. The mapping itself lives
    // in command_resolve.h so a host test can pin the subcommand-vs-argument decision.
    const std::string command = ProgramName(argc > 0 ? argv[0] : "");
    const android_bin::Invocation invocation =
        android_bin::ResolveInvocation(command.c_str(), argc > 1 ? argv[1] : nullptr);
    // Not const: `--api NAME` overrides it below.
    std::string api = invocation.api;
    const int first_arg = invocation.first_arg;
    std::string args_json;
    bool have_args = false;
    std::vector<std::string> pairs;

    for (int i = first_arg; i < argc; i++) {
        const std::string arg = argv[i];

        if (arg == "--help" || arg == "-h") {
            PrintUsage(api);

            return 0;
        }
        if (arg == "--list") {
            // The catalog is generated from apis.txt, so `--list` cannot drift from
            // the symlinks the build creates.
            for (std::size_t i = 0; i < kApiNameCount; i++) {
                std::cout << kApiNames[i] << "\n";
            }

            return 0;
        }
        if (arg == "--commands") {
            // `<command><TAB><api>` for every command, deduped by name (first wins).
            //
            // The Vyzorix CLI reads this to map a command to the API the server
            // allowlists, so it can show only the commands the operator may run. It is
            // generated from commands.txt, so it cannot drift from the symlinks.
            std::set<std::string> seen;
            for (std::size_t i = 0; i < kCommandCount; i++) {
                const std::string name = kCommands[i].name;
                if (!seen.insert(name).second) {
                    continue;
                }
                std::cout << name << "\t" << kCommands[i].api << "\n";
            }

            return 0;
        }
        if (arg == "--api") {
            if (i + 1 >= argc) {
                std::cerr << api << ": --api needs a name\n";

                return 2;
            }
            api = argv[++i];
            continue;
        }
        if (arg == "--json") {
            if (i + 1 >= argc) {
                std::cerr << api << ": --json needs an argument\n";

                return 2;
            }
            args_json = argv[++i];
            have_args = true;
            continue;
        }
        if (arg.rfind("--", 0) == 0) {
            std::cerr << api << ": unknown option " << arg << "\n";

            return 2;
        }

        pairs.push_back(arg);
    }

    if (api.empty()) {
        std::cerr << "android-bin: cannot determine the API name from argv[0]; "
                     "pass --api NAME\n";

        return 2;
    }

    // An unknown name is refused before connecting: a typo should not open a socket
    // and fail at the far end with a less useful message.
    if (!IsKnownApi(api.c_str())) {
        std::cerr << "android-bin: unknown api '" << api << "'\n";
        std::cerr << "known apis:";
        for (std::size_t i = 0; i < kApiNameCount; i++) {
            std::cerr << " " << kApiNames[i];
        }
        std::cerr << "\n";

        return 2;
    }

    try {
        if (!have_args) {
            args_json = ArgsFromPairs(pairs);
        }

        android_bin::BridgeClient client(android_bin::ConfigFromEnvironment());
        const std::string result = client.Call(api, args_json);

        std::cout << result << "\n";

        return 0;
    } catch (const std::exception &e) {
        std::cerr << api << ": " << e.what() << "\n";

        return 1;
    }
}
