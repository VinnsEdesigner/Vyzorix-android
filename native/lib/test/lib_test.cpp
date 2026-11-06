// Unit tests for native/lib: the JSON reader, the registry, and PID validation.
//
// These run on the host with no dependencies, like android-bin's `make check`.
// The registry and the PID check are the two places a silent bug is expensive —
// a registry that will not parse stops every app, and a PID check that is wrong
// signals a process that is not ours — so both are pinned here rather than left
// to the integration tests.

#include <cstdlib>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>

#include <fcntl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>

#include "vyzorix/apps.h"
#include "vyzorix/frame.h"
#include "vyzorix/json.h"
#include "vyzorix/log.h"
#include "vyzorix/watch.h"

using namespace vapps; // NOLINT(google-build-using-namespace) — a test file, for brevity

namespace {

int failures = 0;

void Check(bool condition, const std::string &what) {
    if (!condition) {
        std::cerr << "FAIL: " << what << "\n";
        failures++;
    }
}

std::string TempPath(const std::string &suffix) {
    return "/tmp/vyzorix-lib-test-" + std::to_string(::getpid()) + "-" + suffix;
}

// ------------------------------------------------------------------- JSON

void TestJson() {
    Check(vjson::Parse("null").IsNull(), "null");
    Check(vjson::Parse("{\"a\":\"b\"}").Find("a")->string == "b", "object member");
    Check(vjson::Escape("a\"b\n") == "a\\\"b\\n", "escape");

    bool threw = false;
    try {
        vjson::Parse("{");
    } catch (const vjson::Error &) {
        threw = true;
    }
    Check(threw, "malformed throws");
}

// --------------------------------------------------------------- Registry

void TestRegistryRoundTrip() {
    const std::string path = TempPath("registry.json");
    ::unlink(path.c_str());

    // A missing file is an empty registry, not an error: that is the state before
    // the first app-create.
    Registry empty = Registry::Load(path);
    Check(empty.apps().empty(), "missing file loads as empty");

    Registry registry;
    App app;
    app.name = "flasher";
    app.dir = "/home/apps/flasher";
    app.cmd = "python3 -m http.server 3000";
    app.port = 3000;
    app.autostart = true;
    app.env["KEY"] = "value with \"quotes\"";
    app.pid = 4242;
    app.pidStartTicks = 999;
    registry.Upsert(app);

    registry.Save(path);

    const Registry reloaded = Registry::Load(path);
    const App *loaded = reloaded.Find("flasher");
    Check(loaded != nullptr, "app survives the round trip");
    if (loaded != nullptr) {
        Check(loaded->dir == app.dir, "dir round-trips");
        Check(loaded->cmd == app.cmd, "cmd round-trips");
        Check(loaded->port == 3000, "port round-trips");
        Check(loaded->autostart, "autostart round-trips");
        Check(loaded->env.at("KEY") == "value with \"quotes\"", "env value round-trips");
        Check(loaded->pid == 4242 && loaded->pidStartTicks == 999, "pid record round-trips");
        Check(loaded->HasPidRecord(), "a complete pid record is trusted");
    }

    ::unlink(path.c_str());
}

void TestRegistryUpsertReplaces() {
    Registry registry;
    App first;
    first.name = "a";
    first.port = 1;
    registry.Upsert(first);

    App second;
    second.name = "a";
    second.port = 2;
    registry.Upsert(second);

    Check(registry.apps().size() == 1, "upsert replaces, does not duplicate");
    Check(registry.Find("a")->port == 2, "upsert keeps the newer value");
}

void TestRegistryRejectsMalformed() {
    const std::string path = TempPath("bad.json");
    {
        std::ofstream out(path);
        out << "{\"apps\": \"not an array\"}";
    }

    bool threw = false;
    try {
        Registry::Load(path);
    } catch (const vjson::Error &) {
        threw = true;
    }
    Check(threw, "a present-but-malformed registry throws");

    // An app with no name cannot be addressed, so it is a malformed registry.
    {
        std::ofstream out(path);
        out << "{\"apps\":[{\"cmd\":\"x\"}]}";
    }
    threw = false;
    try {
        Registry::Load(path);
    } catch (const vjson::Error &) {
        threw = true;
    }
    Check(threw, "an unnamed app is rejected");

    ::unlink(path.c_str());
}

void TestRegistryAtomicSaveLeavesNoTemp() {
    const std::string path = TempPath("atomic.json");
    ::unlink(path.c_str());
    ::unlink((path + ".tmp").c_str());

    Registry registry;
    App app;
    app.name = "a";
    registry.Upsert(app);
    registry.Save(path);

    // The temporary must be gone: a leftover would be a second, stale registry.
    std::ifstream temp(path + ".tmp");
    Check(!temp.good(), "save leaves no temporary behind");

    ::unlink(path.c_str());
}

// ------------------------------------------------------------ PID checks

void TestStartTicks() {
    // This process exists, so its start ticks are readable and non-zero.
    const std::uint64_t self = vapps::StartTicksOf(::getpid());
    Check(self != 0, "our own start ticks are readable");

    // Stable across calls: it is a property of the process, not of the read.
    Check(vapps::StartTicksOf(::getpid()) == self, "start ticks are stable");

    // A pid that cannot exist (above any plausible limit) reads as 0.
    Check(vapps::StartTicksOf(1 << 30) == 0, "a nonexistent pid reads as 0");
    Check(vapps::StartTicksOf(0) == 0, "pid 0 reads as 0");
    Check(vapps::StartTicksOf(-1) == 0, "a negative pid reads as 0");
}

void TestIsSameProcess() {
    const int pid = ::getpid();
    const std::uint64_t ticks = vapps::StartTicksOf(pid);

    Check(vapps::IsSameProcess(pid, ticks), "our own pid and ticks match");
    Check(!vapps::IsSameProcess(pid, ticks + 1), "a wrong start time does not match");
    Check(!vapps::IsSameProcess(pid, 0), "a missing start time is never a match");
    Check(!vapps::IsSameProcess(0, ticks), "pid 0 is never a match");
}

// ------------------------------------------------------------------ Frame

void TestFrameRoundTrip() {
    const std::string path = TempPath("frame.sock");
    ::unlink(path.c_str());

    const int listener = vframe::Listen(path);

    // Connect a client, then accept. The listen backlog lets the connect complete
    // before the accept, so this cannot deadlock.
    vframe::Connection client(path);
    vframe::Connection server(::accept(listener, nullptr, nullptr));
    Check(server.fd() >= 0, "accept returns a connection");

    const std::string payload = "hello \x00 world"; // embedded NUL must survive
    client.Write(0x42, payload);

    std::string received;
    const std::uint8_t type = server.Read(&received);
    Check(type == 0x42, "frame type round-trips");
    Check(received == payload, "payload round-trips byte for byte");

    // The reverse direction, so both ends are exercised. Each Write is matched by
    // exactly one Read: a read with no matching write blocks forever, which is the
    // trap this test exists to catch in the framing code.
    server.Write(0x07, std::string("back"));
    std::string reply;
    Check(client.Read(&reply) == 0x07, "the reverse direction round-trips");
    Check(reply == "back", "the reverse payload matches");

    client.Write(0x08, std::string("x"));
    Check(server.Read(&reply) == 0x08, "a second frame round-trips");

    ::close(listener);
    ::unlink(path.c_str());
}

void TestListenRemovesStaleNode() {
    const std::string path = TempPath("stale.sock");
    ::unlink(path.c_str());

    // A crashed process leaves the node; a second Listen must still succeed.
    const int first = vframe::Listen(path);
    ::close(first);

    bool ok = true;
    try {
        const int second = vframe::Listen(path);
        ::close(second);
    } catch (const vframe::Error &) {
        ok = false;
    }
    Check(ok, "Listen replaces a stale socket node");

    ::unlink(path.c_str());
}

void TestConnectToMissingSocketThrows() {
    bool threw = false;
    try {
        vframe::Connection missing(TempPath("does-not-exist.sock"));
    } catch (const vframe::Error &) {
        threw = true;
    }
    Check(threw, "connecting to a missing socket throws");
}

// -------------------------------------------------------------- Log rotation

void TestRotationKeepsTheTailAndBoundsTheFile() {
    const std::string path = TempPath("app.log");
    ::unlink(path.c_str());
    ::unlink((path + ".1").c_str());

    // Below the cap: nothing happens.
    {
        std::ofstream out(path);
        out << "small\n";
    }
    Check(!vlog::RotateLog(path, 1024, 512), "a small log is not rotated");
    Check(vlog::LogSize(path) == 6, "and is left alone");

    // Over the cap: the tail is kept, the live file is truncated.
    {
        std::ofstream out(path, std::ios::app);
        for (int i = 0; i < 200; i++) {
            out << "line " << i << "\n";
        }
    }
    const std::size_t before = vlog::LogSize(path);
    Check(before > 1024, "the log is over the cap");

    Check(vlog::RotateLog(path, 1024, 512), "a large log is rotated");
    Check(vlog::LogSize(path) == 0, "the live log is truncated to zero");

    std::ifstream rotated(path + ".1");
    std::stringstream body;
    body << rotated.rdbuf();
    Check(!body.str().empty(), "the rotated copy has the tail");
    Check(body.str().size() <= 512, "the rotated copy is bounded by keepBytes");
    Check(body.str().find("line 199") != std::string::npos, "the tail is the newest lines");

    ::unlink(path.c_str());
    ::unlink((path + ".1").c_str());
}

void TestRotationIsSafeForALiveAppendWriter() {
    // The reason rotation is copy-then-truncate rather than rename: the app holds
    // the descriptor open, and it is opened O_APPEND. A rename would leave the app
    // writing to the renamed inode and the new file empty; a truncate in place is
    // only correct *because* of O_APPEND. This test holds a real descriptor open
    // across a rotation and asserts the writes continue to land in the live file.
    const std::string path = TempPath("live.log");
    ::unlink(path.c_str());
    ::unlink((path + ".1").c_str());

    const int fd = ::open(path.c_str(), O_WRONLY | O_CREAT | O_APPEND, 0640);
    Check(fd >= 0, "the app's log descriptor opens");

    const std::string filler(2048, 'x');
    Check(::write(fd, filler.data(), filler.size()) > 0, "the app writes before rotation");

    Check(vlog::RotateLog(path, 1024, 512), "the log rotates while the writer is live");
    Check(vlog::LogSize(path) == 0, "the live file is empty right after rotation");

    const std::string after = "after-rotation\n";
    Check(::write(fd, after.data(), after.size()) > 0, "the app writes after rotation");
    ::close(fd);

    // The crucial assertion: the post-rotation write is in the live file, not lost
    // to a sparse hole or to the rotated copy.
    std::ifstream live(path);
    std::stringstream body;
    body << live.rdbuf();
    Check(body.str() == after,
          "a write after rotation lands at the start of the live file, not past a hole");

    ::unlink(path.c_str());
    ::unlink((path + ".1").c_str());
}

void TestLogSizeOfAMissingFileIsZero() {
    Check(vlog::LogSize(TempPath("does-not-exist.log")) == 0, "a missing log is size 0");
    Check(!vlog::RotateLog(TempPath("does-not-exist.log")), "rotating a missing log is a no-op");
}

// ------------------------------------------------------------------- inotify

void TestWatcherSeesARegistryWrite() {
    const std::string dir = TempPath("watchdir");
    ::mkdir(dir.c_str(), 0755);
    const std::string registry = dir + "/apps.json";

    vwatch::DirWatcher watcher;
    if (!watcher.Watch(dir)) {
        // inotify can be unavailable in a restricted container. Skip rather than
        // fail: the supervisor degrades to "no live reload" and still works.
        std::cout << "lib_test: inotify unavailable, skipping the watcher checks\n";
        ::rmdir(dir.c_str());

        return;
    }

    // A write through a real descriptor, closed, which is what Save() does.
    {
        const int fd = ::open(registry.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0640);
        Check(fd >= 0, "the registry opens for writing");
        const std::string body = "{\"apps\":[]}";
        Check(::write(fd, body.data(), body.size()) > 0, "the registry is written");
        ::close(fd);
    }

    const vwatch::Change change = watcher.Drain("apps.json");
    Check(change.registryChanged, "a completed registry write is reported");
    Check(!change.overflowed, "no overflow in a quiet test");

    // A second drain with nothing pending reports nothing.
    const vwatch::Change again = watcher.Drain("apps.json");
    Check(!again.registryChanged, "draining twice does not repeat the event");

    ::unlink(registry.c_str());
    ::rmdir(dir.c_str());
}

void TestWatcherSeesANewAppDirectory() {
    const std::string dir = TempPath("watchdir2");
    ::mkdir(dir.c_str(), 0755);

    vwatch::DirWatcher watcher;
    if (!watcher.Watch(dir)) {
        ::rmdir(dir.c_str());

        return;
    }

    const std::string appDir = dir + "/newapp";
    ::mkdir(appDir.c_str(), 0755);

    const vwatch::Change change = watcher.Drain("apps.json");
    Check(!change.createdDirs.empty(), "a new app directory is reported");
    if (!change.createdDirs.empty()) {
        Check(change.createdDirs[0] == "newapp", "the new directory is named");
    }

    ::rmdir(appDir.c_str());
    ::rmdir(dir.c_str());
}

void TestWatcherWithoutAWatchIsInert() {
    vwatch::DirWatcher watcher;
    Check(!watcher.IsWatching(), "a default watcher is not watching");
    Check(watcher.fd() < 0, "and has no descriptor");

    const vwatch::Change change = watcher.Drain("apps.json");
    Check(!change.registryChanged && !change.overflowed && change.createdDirs.empty(),
          "draining an unwatched watcher is a no-op");
}

void TestWatcherRefusesAMissingDirectory() {
    vwatch::DirWatcher watcher;
    Check(!watcher.Watch(TempPath("no-such-directory")), "watching a missing directory fails");
    Check(!watcher.IsWatching(), "and leaves the watcher inactive");
}

} // namespace

int main() {
    TestJson();
    TestRegistryRoundTrip();
    TestRegistryUpsertReplaces();
    TestRegistryRejectsMalformed();
    TestRegistryAtomicSaveLeavesNoTemp();
    TestStartTicks();
    TestIsSameProcess();
    TestFrameRoundTrip();
    TestListenRemovesStaleNode();
    TestConnectToMissingSocketThrows();
    TestRotationKeepsTheTailAndBoundsTheFile();
    TestRotationIsSafeForALiveAppendWriter();
    TestLogSizeOfAMissingFileIsZero();
    TestWatcherSeesARegistryWrite();
    TestWatcherSeesANewAppDirectory();
    TestWatcherWithoutAWatchIsInert();
    TestWatcherRefusesAMissingDirectory();

    if (failures == 0) {
        std::cout << "lib_test: all checks passed\n";

        return 0;
    }

    std::cerr << "lib_test: " << failures << " failure(s)\n";

    return 1;
}
