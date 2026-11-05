// The app manager — see supervisor.h.

#include "vyzorix/supervisor.h"

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
                 Host *host)
    : registry_(std::move(registry)),
      registryPath_(std::move(registryPath)),
      logDir_(std::move(logDir)),
      host_(host) {}

std::string Manager::LogPathFor(const std::string &name) const {
    return logDir_ + "/" + name + ".log";
}

void Manager::Persist() const {
    registry_.Save(registryPath_);
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

bool Manager::Stop(const std::string &name) {
    const auto it = running_.find(name);
    if (it == running_.end()) {
        // Not tracked. Make sure no stale record survives in the registry, then
        // report success: stopping something that is not running is not a failure.
        if (vapps::App *app = registry_.Find(name); app != nullptr) {
            app->pid = 0;
            app->pidStartTicks = 0;
            Persist();
        }

        return true;
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

void Manager::Poll() {
    const std::int64_t now = host_->NowMs();

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
