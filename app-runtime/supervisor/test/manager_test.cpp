// Unit tests for the app manager, against a fake host.
//
// The manager is where the failures that hurt live: a crash loop that never gives
// up, a restart that resets the backoff and spins, a stop that signals a recycled
// pid. None of those need a real process to test — they need control of time, of
// exit, and of what StartTicks reports — so the fake host provides exactly that and
// the tests drive the policy directly.

#include <algorithm>
#include <csignal>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

#include <unistd.h>

#include "vyzorix/app_registry.h"
#include "vyzorix/supervisor.h"

using namespace vsup;

namespace {

int failures = 0;

void Check(bool condition, const std::string &what) {
    if (!condition) {
        std::cerr << "FAIL: " << what << "\n";
        failures++;
    }
}

/**
 * A host that runs no processes: spawn returns a synthetic pid, exit is decided by
 * the test, and the clock only moves when the test moves it.
 */
class FakeHost : public Host {
public:
    struct Proc {
        std::string cmd;
        std::string dir;
        std::map<std::string, std::string> env;
        std::uint64_t startTicks;
        bool exited = false;
        ExitKind kind = ExitKind::Exited;
        int code = 0;
    };

    int Spawn(const std::string &cmd, const std::string &dir,
              const std::map<std::string, std::string> &env, const std::string &logPath) override {
        const int pid = nextPid_++;
        procs_[pid] = Proc{cmd, dir, env, /*startTicks=*/static_cast<std::uint64_t>(pid * 1000)};
        spawned_.push_back(pid);
        lastLogPath_ = logPath;
        if (spawnFails_) {
            return -1;
        }

        return pid;
    }

    bool Exited(int pid, ExitKind *kind, int *code) override {
        const auto it = procs_.find(pid);
        if (it == procs_.end()) {
            *kind = ExitKind::NotRunning;
            *code = 0;

            return true;
        }
        if (!it->second.exited) {
            return false;
        }
        *kind = it->second.kind;
        *code = it->second.code;

        return true;
    }

    void Terminate(int pid) override {
        terminated_.push_back(pid);
        // A well-behaved app exits on SIGTERM.
        if (auto it = procs_.find(pid); it != procs_.end()) {
            it->second.exited = true;
            it->second.kind = ExitKind::Signaled;
            it->second.code = SIGTERM;
        }
    }

    void Kill(int pid) override {
        killed_.push_back(pid);
        if (auto it = procs_.find(pid); it != procs_.end()) {
            it->second.exited = true;
            it->second.kind = ExitKind::Signaled;
            it->second.code = SIGKILL;
        }
    }

    std::uint64_t StartTicks(int pid) override {
        const auto it = procs_.find(pid);
        if (it == procs_.end() || it->second.exited) {
            return 0;
        }

        return it->second.startTicks;
    }

    std::int64_t NowMs() override {
        return now_;
    }
    void SleepMs(std::int64_t ms) override {
        now_ += ms;
    }

    // ---- test controls ----

    void Crash(int pid, int code = 1) {
        procs_[pid].exited = true;
        procs_[pid].kind = ExitKind::Exited;
        procs_[pid].code = code;
    }

    /** Makes the pid's start token change, as a recycled pid would. */
    void Recycle(int pid) {
        procs_[pid].startTicks += 1;
    }

    void Advance(std::int64_t ms) {
        now_ += ms;
    }
    int lastPid() const {
        return spawned_.empty() ? 0 : spawned_.back();
    }
    std::size_t spawnCount() const {
        return spawned_.size();
    }

    void SetSpawnFails(bool fail) {
        spawnFails_ = fail;
    }

    /** Registers a process this host did not spawn, as a restart would find. */
    void PretendAlive(int pid, std::uint64_t startTicks) {
        Proc p;
        p.startTicks = startTicks;
        procs_[pid] = p;
    }

    bool IsExited(int pid) const {
        const auto it = procs_.find(pid);

        return it == procs_.end() || it->second.exited;
    }

    const std::string &lastLogPath() const {
        return lastLogPath_;
    }
    const std::vector<int> &terminated() const {
        return terminated_;
    }
    const std::vector<int> &killed() const {
        return killed_;
    }

private:
    std::map<int, Proc> procs_;
    std::vector<int> spawned_;
    std::vector<int> terminated_;
    std::vector<int> killed_;
    std::string lastLogPath_;
    int nextPid_ = 100;
    std::int64_t now_ = 1'000'000;
    bool spawnFails_ = false;
};

std::string TempRegistryPath() {
    return "/tmp/vyzorix-sup-test-" + std::to_string(::getpid()) + ".json";
}

vapps::App MakeApp(const std::string &name, bool autostart = true) {
    vapps::App app;
    app.name = name;
    app.dir = "/tmp";
    app.cmd = "sleep 1000";
    app.autostart = autostart;
    app.port = 3000;

    return app;
}

/** A manager over a fresh registry, with the temp file cleaned up. */
struct Fixture {
    FakeHost host;
    std::string path = TempRegistryPath();
    Manager manager;

    explicit Fixture(std::vector<vapps::App> apps)
        : manager(MakeRegistry(apps), path, "/tmp", "/tmp", &host) {
        ::unlink(path.c_str());
    }

    ~Fixture() {
        ::unlink(path.c_str());
    }

    static vapps::Registry MakeRegistry(const std::vector<vapps::App> &apps) {
        vapps::Registry registry;
        for (const vapps::App &app : apps) {
            registry.Upsert(app);
        }

        return registry;
    }
};

// --------------------------------------------------------------------------

void TestStartAndStop() {
    Fixture f({MakeApp("a")});

    Check(f.manager.Start("a"), "start succeeds");
    Check(f.host.spawnCount() == 1, "one process spawned");
    Check(f.manager.IsRunning("a"), "the app reports running");

    Check(f.manager.Stop("a"), "stop succeeds");
    Check(!f.manager.IsRunning("a"), "the app reports stopped");
    Check(!f.host.terminated().empty(), "stop sent SIGTERM");
    Check(f.host.killed().empty(), "a well-behaved app is not SIGKILLed");
}

void TestUnknownAppIsRefused() {
    Fixture f({MakeApp("a")});

    Check(!f.manager.Start("nope"), "starting an unknown app fails");
    Check(!f.manager.Stop("nope") == false, "stopping an unknown app is not an error");
    Check(f.host.spawnCount() == 0, "nothing was spawned");
}

/** A registry app carrying a live pid record, as a restart would leave it. */
vapps::App RecordedApp(const std::string &name, int pid, std::uint64_t ticks) {
    vapps::App app = MakeApp(name);

    app.pid = pid;
    app.pidStartTicks = ticks;

    return app;
}

void TestAdoptRunningReclaimsALiveProcess() {
    // A process the previous supervisor started is still alive: its start ticks
    // match. Adoption must put it back in the running table so this supervisor reaps
    // and can stop it, and must NOT spawn a duplicate.
    vapps::App app = RecordedApp("a", 4242, 4242 * 1000);
    Fixture f({app});
    f.host.PretendAlive(4242, 4242 * 1000);

    f.manager.AdoptRunning();

    Check(f.manager.IsRunning("a"), "the live process is adopted");
    f.manager.StartAutostart();
    Check(f.host.spawnCount() == 0, "an adopted app is not spawned again");

    Check(f.manager.Stop("a"), "the adopted app can be stopped");
    Check(!f.host.terminated().empty(), "stopping the adopted app signalled it");
}

void TestAdoptRunningDropsAStaleRecord() {
    // The recorded pid is gone or recycled: its ticks do not match. The record must be
    // cleared, so autostart spawns a fresh process rather than signalling a pid that
    // is no longer ours.
    vapps::App app = RecordedApp("a", 4242, 999);
    Fixture f({app});
    f.host.PretendAlive(4242, 4242 * 1000); // ticks differ: not our process

    f.manager.AdoptRunning();
    f.manager.StartAutostart();

    Check(f.host.spawnCount() == 1, "a stale record is replaced with a fresh spawn");
}

void TestStopKillsAnOrphanLeftByAPreviousSupervisor() {
    // The failure the live run hit: the app was started by a supervisor that has since
    // exited, so this one has no running entry. Stop must adopt the recorded pid and
    // kill it, not merely clear the record.
    vapps::App app = RecordedApp("a", 4242, 4242 * 1000);
    Fixture f({app});
    f.host.PretendAlive(4242, 4242 * 1000);

    Check(f.manager.Stop("a"), "stopping an untracked but recorded app succeeds");

    Check(!f.host.terminated().empty(), "the orphan was signalled");
    Check(f.host.IsExited(4242), "the orphan is no longer running");
}

void TestAutostartOnlyStartsFlaggedApps() {
    Fixture f({MakeApp("on", true), MakeApp("off", false)});

    f.manager.StartAutostart();

    Check(f.manager.IsRunning("on"), "an autostart app starts");
    Check(!f.manager.IsRunning("off"), "a non-autostart app does not");
}

void TestStartIsIdempotent() {
    Fixture f({MakeApp("a")});

    Check(f.manager.Start("a"), "first start");
    Check(f.manager.Start("a"), "second start reports success");
    Check(f.host.spawnCount() == 1, "but does not spawn a second process");
}

void TestCrashRestartsWithBackoff() {
    Fixture f({MakeApp("a")});
    f.manager.Start("a");

    const int firstPid = f.host.lastPid();
    f.host.Crash(firstPid);
    f.manager.Poll();

    // The app is gone, and nothing restarts until the backoff elapses.
    Check(!f.manager.IsRunning("a"), "a crashed app is not running");
    Check(f.host.spawnCount() == 1, "no immediate restart: the backoff has not elapsed");

    // First backoff step is 1s.
    f.host.Advance(1000);
    f.manager.Poll();

    Check(f.host.spawnCount() == 2, "the app restarts after the first backoff");
}

void TestBackoffGrowsAndIsCapped() {
    Check(BackoffMsFor(1) == 1000, "first backoff is 1s");
    Check(BackoffMsFor(2) == 2000, "second is 2s");
    Check(BackoffMsFor(3) == 4000, "third is 4s");
    Check(BackoffMsFor(20) == 60'000, "backoff is capped at 60s");
    Check(BackoffMsFor(1000) == 60'000, "a huge count does not overflow the shift");
}

void TestRepeatedCrashesGiveUp() {
    Fixture f({MakeApp("a")});
    f.manager.Start("a");

    // Crash it repeatedly, waiting out each backoff. After kMaxFailures the manager
    // must stop trying rather than spin forever.
    for (int i = 0; i < kMaxFailures + 3; i++) {
        const int pid = f.host.lastPid();
        f.host.Crash(pid);
        f.manager.Poll();
        f.host.Advance(120'000); // past any backoff
        f.manager.Poll();
    }

    const std::size_t spawns = f.host.spawnCount();

    // One more poll with time advanced must not spawn again: the app has been given
    // up on.
    f.host.Advance(120'000);
    f.manager.Poll();
    Check(f.host.spawnCount() == spawns, "a permanently failing app is left stopped");

    // And it must be bounded: 1 initial + at most kMaxFailures restarts.
    Check(spawns <= static_cast<std::size_t>(kMaxFailures) + 1,
          "the restart count is bounded by kMaxFailures");
}

void TestHealthyRunResetsBackoff() {
    Fixture f({MakeApp("a")});
    f.manager.Start("a");

    // Crash once so the failure count is 1.
    f.host.Crash(f.host.lastPid());
    f.manager.Poll();
    f.host.Advance(1000);
    f.manager.Poll();
    Check(f.host.spawnCount() == 2, "restarted after the first backoff");

    // Let it run healthily, then crash: the count must reset, so the next backoff is
    // the first step again rather than the second.
    f.host.Advance(kHealthyRunMs + 1);
    f.host.Crash(f.host.lastPid());
    f.manager.Poll();

    f.host.Advance(1000); // the first step, not the second
    f.manager.Poll();
    Check(f.host.spawnCount() == 3, "a healthy run resets the backoff to the first step");
}

void TestNonAutostartAppIsNotRestarted() {
    Fixture f({MakeApp("a", /*autostart=*/false)});
    f.manager.Start("a");

    f.host.Crash(f.host.lastPid());
    f.manager.Poll();
    f.host.Advance(120'000);
    f.manager.Poll();

    Check(f.host.spawnCount() == 1, "a non-autostart app is not restarted after a crash");
}

void TestRecycledPidIsNotMistakenForOurs() {
    Fixture f({MakeApp("a")});
    f.manager.Start("a");

    const int pid = f.host.lastPid();

    // The process is alive as far as waitpid is concerned, but the start token has
    // changed — which is what PID reuse looks like. §7.1 says this is not our
    // process, so it must not be reported as running and must not be signalled.
    f.host.Recycle(pid);

    Check(!f.manager.IsRunning("a"), "a recycled pid is not our process");

    f.manager.Stop("a");
    Check(std::find(f.host.terminated().begin(), f.host.terminated().end(), pid) ==
              f.host.terminated().end(),
          "stop does not signal a recycled pid");
}

void TestSpawnFailureIsReported() {
    Fixture f({MakeApp("a")});
    f.host.SetSpawnFails(true);

    Check(!f.manager.Start("a"), "a failed spawn is reported");
    Check(!f.manager.IsRunning("a"), "and the app is not running");
}

void TestRestartClearsCrashHistory() {
    Fixture f({MakeApp("a")});
    f.manager.Start("a");

    // Build up a crash history.
    f.host.Crash(f.host.lastPid());
    f.manager.Poll();
    f.host.Advance(1000);
    f.manager.Poll();
    f.host.Crash(f.host.lastPid());
    f.manager.Poll();

    // An explicit restart is a fresh attempt, so it must start immediately rather
    // than wait out the backoff the crash loop had scheduled.
    const std::size_t before = f.host.spawnCount();
    Check(f.manager.Restart("a"), "restart succeeds");
    Check(f.host.spawnCount() == before + 1, "restart starts immediately");
    Check(f.manager.IsRunning("a"), "and the app is running");
}

void TestLogPathIsPerApp() {
    Fixture f({MakeApp("myapp")});
    f.manager.Start("myapp");

    Check(f.host.lastLogPath() == "/tmp/myapp.log", "the log path is derived from the name");
}

void TestReloadKeepsRunningApps() {
    Fixture f({MakeApp("a")});
    f.manager.Start("a");
    const int pid = f.host.lastPid();

    // Rewriting the registry and reloading must not stop the app.
    f.manager.registry().Save(f.manager.registryPath());
    f.manager.Reload();

    Check(f.manager.IsRunning("a"), "a reload does not stop a running app");
    Check(f.host.terminated().empty(), "and does not signal it");
    Check(pid == f.host.lastPid(), "the process is unchanged");
}

void TestRegistryOnDiskDiffersDetectsOnlyForeignEdits() {
    Fixture f({MakeApp("a")});

    // After the manager's own write the file matches memory: no reload needed.
    f.manager.Persist();
    Check(!f.manager.RegistryOnDiskDiffers(), "own write is not seen as a foreign edit");

    // An operator edit changes the file on disk, without touching the manager: a
    // reload is needed. Write a separate registry with an extra app.
    vapps::Registry foreign;
    foreign.Upsert(MakeApp("a"));
    foreign.Upsert(MakeApp("b"));
    foreign.Save(f.manager.registryPath());
    Check(f.manager.RegistryOnDiskDiffers(), "an operator edit is seen as a foreign edit");
}

} // namespace

int main() {
    TestStartAndStop();
    TestUnknownAppIsRefused();
    TestAdoptRunningReclaimsALiveProcess();
    TestAdoptRunningDropsAStaleRecord();
    TestStopKillsAnOrphanLeftByAPreviousSupervisor();
    TestAutostartOnlyStartsFlaggedApps();
    TestStartIsIdempotent();
    TestCrashRestartsWithBackoff();
    TestBackoffGrowsAndIsCapped();
    TestRepeatedCrashesGiveUp();
    TestHealthyRunResetsBackoff();
    TestNonAutostartAppIsNotRestarted();
    TestRecycledPidIsNotMistakenForOurs();
    TestSpawnFailureIsReported();
    TestRestartClearsCrashHistory();
    TestLogPathIsPerApp();
    TestReloadKeepsRunningApps();
    TestRegistryOnDiskDiffersDetectsOnlyForeignEdits();

    if (failures == 0) {
        std::cout << "manager_test: all checks passed\n";

        return 0;
    }

    std::cerr << "manager_test: " << failures << " failure(s)\n";

    return 1;
}
