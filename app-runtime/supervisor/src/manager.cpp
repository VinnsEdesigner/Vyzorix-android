// The app manager — see supervisor.h.

#include "vyzorix/supervisor.h"

#include "vyzorix/app_log_rotation.h"
#include "vyzorix/app_meta_store.h"

#include <algorithm>
#include <cstdio>

namespace vsup {
namespace {

/** The backoff base, doubling per consecutive failure. */
constexpr std::int64_t kBackoffBaseMs = 1000;

/** The backoff ceiling. Past this a restart is not a restart, it is a loop. */
constexpr std::int64_t kBackoffMaxMs = 60'000;

/** How long Stop waits for SIGTERM to be honoured before SIGKILL. */
constexpr std::int64_t kTermGraceMs = 3'000;

/** Poll interval while waiting for a process to die during Stop. */
constexpr std::int64_t kStopPollMs = 50;

} // namespace

std::int64_t BackoffMsFor(int failures) {
    if (failures <= 1) {
        return kBackoffBaseMs;
    }

    // Shift by (failures - 1) and clamp. The shift is capped before it is applied,
    // because shifting by 64 or more is undefined behaviour, not a large number.
    const int shift = std::min(failures - 1, 20);
    const std::int64_t delay = kBackoffBaseMs << shift;

    return std::min(delay, kBackoffMaxMs);
}

Manager::Manager(vapps::Registry registry, std::string registryPath, std::string logDir,
                 std::string metaDir, Host *host)
    : registry_(std::move(registry)), registryPath_(std::move(registryPath)),
      logDir_(std::move(logDir)), metaDir_(std::move(metaDir)), host_(host) {
    // The metadata directory holds the expose config, so it is created here rather
    // than on the first expose: an operator looking at the layout should see `meta/`
    // beside `logs/` from the start.
    vmeta::EnsureDir(metaDir_);
}

std::string Manager::LogPathFor(const std::string &name) const {
    return logDir_ + "/" + name + ".log";
}

void Manager::Persist() const {
    registry_.Save(registryPath_);
}

bool Manager::RegistryOnDiskDiffers() const {
    // True only when the file holds something other than what this manager holds in
    // memory. After the manager's own write the two match, so the inotify reload can
    // skip it; an operator's edit makes them differ. Without this a control op that
    // writes the registry (a stop, a create) is seen by the watcher, reloaded, and
    // StartAutostart restarts the app the operator just stopped — the stop reports
    // success and the app keeps running.
    try {
        return vapps::Registry::Load(registryPath_).ToJson() != registry_.ToJson();
    } catch (const std::exception &) {
        // Unreadable or malformed: treat it as "differs" so the reload path runs and
        // reports the problem, rather than silently ignoring a broken file.
        return true;
    }
}

void Manager::Reload() {
    // Running state is keyed by name and kept; only the app definitions change. An
    // edit to apps.json must not stop a running app.
    vapps::Registry fresh = vapps::Registry::Load(registryPath_);
    registry_ = std::move(fresh);
}

bool Manager::IsRunning(const std::string &name) {
    const auto it = running_.find(name);
    if (it == running_.end()) {
        return false;
    }

    ExitKind kind = ExitKind::NotRunning;
    int code = 0;
    if (host_->Exited(it->second.pid, &kind, &code)) {
        return false;
    }

    // The process is alive — but is it still *ours*? A pid can be recycled, and
    // §7.1 is explicit that a bare pid must not be trusted.
    return host_->StartTicks(it->second.pid) == it->second.startTicks;
}

std::vector<std::string> Manager::RunningNames() {
    std::vector<std::string> names;
    for (const auto &entry : running_) {
        if (IsRunning(entry.first)) {
            names.push_back(entry.first);
        }
    }
    std::sort(names.begin(), names.end());

    return names;
}

void Manager::StartAutostart() {
    for (const vapps::App &app : registry_.apps()) {
        if (app.autostart) {
            Start(app.name);
        }
    }
}

bool Manager::Start(const std::string &name) {
    vapps::App *app = registry_.Find(name);
    if (app == nullptr) {
        return false;
    }

    if (IsRunning(name)) {
        return true;
    }

    if (app->cmd.empty()) {
        return false;
    }

    const int pid = host_->Spawn(app->cmd, app->dir, app->env, LogPathFor(name));
    if (pid <= 0) {
        return false;
    }

    const std::uint64_t ticks = host_->StartTicks(pid);

    Running running;
    running.pid = pid;
    running.startTicks = ticks;
    running.startedAtMs = host_->NowMs();
    running_[name] = running;

    app->pid = pid;
    app->pidStartTicks = ticks;
    Persist();

    return true;
}

void Manager::AdoptRunning() {
    // A process the previous supervisor started is still alive and still ours if its
    // start ticks match the recorded value (§7.1). Adopt it so this supervisor reaps
    // it, restarts it on a crash, and does not spawn a duplicate on StartAutostart.
    // A record whose ticks no longer match is stale: clear it so the next start is a
    // fresh spawn rather than a signal to a recycled pid.
    for (vapps::App &app : registry_.apps()) {
        if (!app.HasPidRecord()) continue;

        if (host_->StartTicks(app.pid) == app.pidStartTicks) {
            Running adopted;
            adopted.pid = app.pid;
            adopted.startTicks = app.pidStartTicks;
            adopted.startedAtMs = host_->NowMs();
            running_[app.name] = adopted;
        } else {
            app.pid = 0;
            app.pidStartTicks = 0;
        }
    }

    Persist();
}

bool Manager::Stop(const std::string &name) {
    auto it = running_.find(name);

    if (it == running_.end()) {
        // Not tracked in this process. That happens after a supervisor restart: the
        // running table is in memory, so an app the *previous* supervisor started is
        // unknown here even though its process is still alive. The registry keeps the
        // pid record, so adopt it rather than leaving the process orphaned — a
        // removed app whose process is never killed keeps its port bound and its
        // data dir in use, which is exactly the failure §9.3 exists to avoid.
        const vapps::App *recorded = registry_.Find(name);
        if (recorded != nullptr && recorded->HasPidRecord()) {
            Running adopted;
            adopted.pid = recorded->pid;
            adopted.startTicks = recorded->pidStartTicks;
            it = running_.emplace(name, adopted).first;
        } else {
            // Genuinely not running. Make sure no stale record survives, then report
            // success: stopping something that is not running is not a failure.
            if (vapps::App *app = registry_.Find(name); app != nullptr) {
                app->pid = 0;
                app->pidStartTicks = 0;
                Persist();
            }

            return true;
        }
    }

    const int pid = it->second.pid;
    const std::uint64_t ticks = it->second.startTicks;

    // Only signal it if it is still the process we started. Signalling a recycled
    // pid would hit an unrelated process, which is the failure §7.1 exists to stop.
    if (host_->StartTicks(pid) == ticks) {
        host_->Terminate(pid);

        const std::int64_t deadline = host_->NowMs() + kTermGraceMs;
        ExitKind kind = ExitKind::NotRunning;
        int code = 0;
        while (host_->NowMs() < deadline && !host_->Exited(pid, &kind, &code)) {
            host_->SleepMs(kStopPollMs);
        }

        if (!host_->Exited(pid, &kind, &code)) {
            host_->Kill(pid);
        }
    }

    running_.erase(it);
    backoffUntil_.erase(name);
    // A deliberate stop is not a crash, so the crash history ends here.
    failures_.erase(name);

    if (vapps::App *app = registry_.Find(name); app != nullptr) {
        app->pid = 0;
        app->pidStartTicks = 0;
        Persist();
    }

    return true;
}

bool Manager::Restart(const std::string &name) {
    if (registry_.Find(name) == nullptr) {
        return false;
    }

    Stop(name);

    // An explicit restart is a fresh attempt: clear the crash history the loop left.
    backoffUntil_.erase(name);
    failures_.erase(name);
    if (vapps::App *app = registry_.Find(name); app != nullptr) {
        app->pid = 0;
        app->pidStartTicks = 0;
    }

    return Start(name);
}

bool Manager::Expose(const std::string &name, const std::string &bind, const std::string &token,
                     std::string *error) {
    const vapps::App *app = registry_.Find(name);
    if (app == nullptr) {
        *error = "no app named " + name;

        return false;
    }

    // An app with no port has nothing to proxy. Refusing here is better than
    // starting a proxy that 502s every request.
    if (app->port <= 0) {
        *error = "app " + name + " does not declare a port to expose";

        return false;
    }

    // Replace any existing proxy: an expose on an already-exposed app is a change of
    // bind address or token, not a second listener on the same port.
    Unexpose(name);

    auto proxy = std::make_unique<vproxy::Proxy>();

    vproxy::ProxyConfig config;
    config.name = name;
    config.bindAddress = bind;
    config.targetHost = "127.0.0.1";
    config.targetPort = app->port;
    config.token = token;

    // **The listener port is not the app's port**, and this was found by running it:
    // proot shares the host's network namespace, so a proxy on `0.0.0.0:<app port>`
    // collides with the app already listening on `127.0.0.1:<app port>` — the app
    // cannot be reached through a proxy that cannot bind.
    //
    // So the app's port is what the proxy *forwards to*, and the listener takes the
    // same number only if it is free. A taken port means the app is there (the normal
    // case), and the kernel then picks a free one. The bound port is what
    // [ExposedAddress] reports, so the operator is told the real URL rather than the
    // one that was hoped for.
    config.port = app->port;

    if (!proxy->Start(config, error)) {
        // The one refusal worth retrying: the port is the app's own. A token refusal
        // or a bad address must not be retried.
        if (error->find("Address already in use") == std::string::npos) {
            return false;
        }

        config.port = 0;
        error->clear();
        if (!proxy->Start(config, error)) {
            return false;
        }
    }

    proxies_[name] = std::move(proxy);

    // The metadata is what makes the expose survive a restart, so it is written only
    // once the proxy is actually up: recording an expose that failed to bind would
    // make the next boot try to start it again and fail the same way.
    vmeta::AppMeta meta;
    meta.name = name;
    meta.exposed = true;
    meta.expose.bind = bind;
    // The port actually bound, not the one requested: a proxy that fell back to a
    // kernel-assigned port must record that, or a restart would try the taken one.
    meta.expose.port = proxies_[name]->port();
    meta.expose.token = token;
    vmeta::Save(metaDir_, meta);

    return true;
}

bool Manager::Unexpose(const std::string &name) {
    const auto it = proxies_.find(name);
    if (it != proxies_.end()) {
        it->second->Stop();
        proxies_.erase(it);
    }

    vmeta::AppMeta meta;
    meta.name = name;
    meta.exposed = false;
    vmeta::Save(metaDir_, meta);

    return it != proxies_.end();
}

std::string Manager::ExposedAddress(const std::string &name) const {
    const auto it = proxies_.find(name);
    if (it == proxies_.end() || !it->second->running()) {
        return std::string();
    }

    return it->second->bindAddress() + ":" + std::to_string(it->second->port());
}

void Manager::StartExposed() {
    // Every app whose metadata says it is exposed is brought back up. This is what
    // makes an expose outlive a supervisor restart: the state is on disk, and without
    // this a reboot would silently drop it.
    for (const vapps::App &app : registry_.apps()) {
        vmeta::AppMeta meta;
        try {
            meta = vmeta::Load(metaDir_, app.name);
        } catch (const std::exception &e) {
            // A corrupt metadata file must not stop the supervisor; it costs a
            // re-expose, which the operator can see is missing.
            std::fprintf(stderr, "supervisor: %s metadata unreadable: %s\n", app.name.c_str(),
                         e.what());

            continue;
        }

        if (!meta.exposed) {
            continue;
        }

        std::string error;
        if (!Expose(app.name, meta.expose.bind, meta.expose.token, &error)) {
            std::fprintf(stderr, "supervisor: could not re-expose %s: %s\n", app.name.c_str(),
                         error.c_str());
        }
    }
}

void Manager::RotateLogs() {
    // Every app with a log, not just the running ones: a stopped app's log is still
    // on disk and still counts against the storage budget.
    for (const vapps::App &app : registry_.apps()) {
        vlog::RotateLog(LogPathFor(app.name));
    }
}

void Manager::Poll() {
    const std::int64_t now = host_->NowMs();

    RotateLogs();

    // Reap anything that has exited and decide what comes back.
    for (auto it = running_.begin(); it != running_.end();) {
        const std::string name = it->first;
        Running &running = it->second;

        ExitKind kind = ExitKind::NotRunning;
        int code = 0;
        if (!host_->Exited(running.pid, &kind, &code)) {
            ++it;
            continue;
        }

        // It ran long enough to be considered healthy, so the next failure starts
        // the backoff over. Without this a process that crashes once a day would
        // still climb the schedule.
        const bool healthy = (now - running.startedAtMs) >= kHealthyRunMs;

        vapps::App *app = registry_.Find(name);
        const bool shouldRestart = app != nullptr && app->autostart;

        const int failures = healthy ? 0 : failures_[name] + 1;
        failures_[name] = failures;

        // Clear the pid record: the process is gone, and a stale pid in the
        // registry is exactly the thing that must never be acted on.
        if (app != nullptr) {
            app->pid = 0;
            app->pidStartTicks = 0;
        }

        it = running_.erase(it);
        Persist();

        if (!shouldRestart) {
            continue;
        }

        if (failures > kMaxFailures) {
            // Give up rather than spin. The operator sees it stopped in app-list,
            // which is honest; a silent infinite restart loop is not.
            backoffUntil_.erase(name);
            continue;
        }

        backoffUntil_[name] = now + BackoffMsFor(failures);
    }

    // Start anything whose backoff has elapsed.
    for (auto it = backoffUntil_.begin(); it != backoffUntil_.end();) {
        if (it->second > now) {
            ++it;
            continue;
        }

        const std::string name = it->first;
        it = backoffUntil_.erase(it);

        // The count lives in failures_, which the reap above already advanced, so
        // the restart does not have to carry anything: the next crash reads it back.
        Start(name);
    }
}

} // namespace vsup
