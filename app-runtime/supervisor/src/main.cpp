// supervisor — the Layer 3 entry point.
//
// Started by Layer 1 inside the container, one per device. It loads the app
// registry, starts the autostart apps, serves the control socket, and restarts
// anything that crashes.
//
// Usage:
//   supervisor [--apps PATH] [--logs DIR] [--socket PATH]
//
// Defaults are the layout the architecture fixes:
//
//   --apps    /home/apps/apps.json        the registry
//   --logs    /home/apps/logs             one log per app
//   --socket  /home/apps/supervisor.sock  the control socket
//
// The defaults are the reason /home/apps is bound from app storage rather than the
// rootfs: the registry and the logs must outlive a rootfs replacement, and
// /home/apps is the one directory that does.
//
// It logs to stderr, which the proot PTY captures — the same channel the engine's
// own output uses, so `logcat` shows it without a second log path.

#include <csignal>
#include <cstdio>
#include <cstring>
#include <iostream>
#include <string>
#include <unistd.h>

#include <sys/stat.h>

#include "vyzorix/app_registry.h"
#include "vyzorix/control.h"
#include "vyzorix/host_system.h"
#include "vyzorix/supervisor.h"

namespace {

constexpr const char *kDefaultApps = "/home/apps/apps.json";
constexpr const char *kDefaultLogs = "/home/apps/logs";

/** Per-app metadata, beside the logs. Holds the expose config, which is a secret. */
constexpr const char *kDefaultMeta = "/home/apps/meta";
constexpr const char *kDefaultSocket = "/home/apps/supervisor.sock";

/** The server whose loop a signal must stop. */
vsup::ControlServer *g_server = nullptr;

void OnSignal(int /*signum*/) {
    if (g_server != nullptr) {
        g_server->Stop();
    }
}

/** Creates `path` and its parents. Returns false when it cannot. */
bool EnsureDir(const std::string &path) {
    if (path.empty()) {
        return true;
    }

    // mkdir -p, one component at a time. A single mkdir would fail on a nested path
    // whose parent does not exist yet, which is the common case here.
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

/** The directory part of a path, or "." when there is none. */
std::string DirName(const std::string &path) {
    const std::size_t slash = path.find_last_of('/');
    if (slash == std::string::npos) {
        return ".";
    }
    if (slash == 0) {
        return "/";
    }

    return path.substr(0, slash);
}

} // namespace

int main(int argc, char **argv) {
    std::string appsPath = kDefaultApps;
    std::string logsDir = kDefaultLogs;
    std::string metaDir = kDefaultMeta;
    std::string socketPath = kDefaultSocket;

    for (int i = 1; i < argc; i++) {
        const std::string arg = argv[i];
        const auto value = [&](const char *what) -> std::string {
            if (i + 1 >= argc) {
                std::cerr << "supervisor: " << what << " needs an argument\n";
                std::exit(2);
            }

            return argv[++i];
        };

        if (arg == "--apps") {
            appsPath = value("--apps");
        } else if (arg == "--logs") {
            logsDir = value("--logs");
        } else if (arg == "--meta") {
            metaDir = value("--meta");
        } else if (arg == "--socket") {
            socketPath = value("--socket");
        } else if (arg == "--help" || arg == "-h") {
            std::cout << "usage: supervisor [--apps PATH] [--logs DIR] [--meta DIR] "
                         "[--socket PATH]\n";

            return 0;
        } else {
            std::cerr << "supervisor: unknown argument " << arg << "\n";

            return 2;
        }
    }

    // The directories must exist before anything is written into them: the registry,
    // the logs, and the socket's parent.
    if (!EnsureDir(DirName(appsPath)) || !EnsureDir(logsDir) || !EnsureDir(metaDir) ||
        !EnsureDir(DirName(socketPath))) {
        std::cerr << "supervisor: cannot create the app directories\n";

        return 1;
    }

    vsup::SystemHost host;

    vapps::Registry registry;
    try {
        registry = vapps::Registry::Load(appsPath);
    } catch (const std::exception &e) {
        // A malformed registry is refused rather than replaced: starting with an
        // empty one would silently orphan every app the operator configured.
        std::cerr << "supervisor: " << appsPath << " is unreadable: " << e.what() << "\n";

        return 1;
    }

    vsup::Manager manager(std::move(registry), appsPath, logsDir, metaDir, &host);

    vsup::ControlServer server(&manager, socketPath);
    if (!server.Start()) {
        std::cerr << "supervisor: cannot bind " << socketPath << "\n";

        return 1;
    }

    // SIGTERM is how the device stops the supervisor: Layer 1 sends it on shutdown.
    // The handler only asks the loop to stop; the loop owns the manager, so nothing
    // else may touch it from a signal context.
    g_server = &server;
    std::signal(SIGTERM, OnSignal);
    std::signal(SIGINT, OnSignal);
    // A crashed app is reaped by the loop, not by a signal handler.
    std::signal(SIGCHLD, SIG_DFL);

    // A control client that disconnects mid-write must not kill the supervisor.
    // Without this, a CLI interrupted at the wrong moment terminates the server by
    // SIGPIPE — the default action — and every app it supervises is orphaned.
    std::signal(SIGPIPE, SIG_IGN);

    // Adopt anything the previous supervisor left running before starting autostart,
    // or an app that survived a supervisor restart is both orphaned and duplicated.
    manager.AdoptRunning();
    manager.StartAutostart();

    // An expose is state on disk, so it outlives a restart. Without this a reboot
    // would silently drop every exposed app while its metadata still said otherwise.
    manager.StartExposed();

    std::cerr << "supervisor: ready on " << socketPath << "\n";

    server.Run();

    // Stopping the supervisor stops the apps: they are children of it, and leaving
    // them running would orphan them with no one to restart or reap them.
    for (const std::string &name : manager.RunningNames()) {
        manager.Stop(name);
    }

    std::cerr << "supervisor: stopped\n";

    return 0;
}
