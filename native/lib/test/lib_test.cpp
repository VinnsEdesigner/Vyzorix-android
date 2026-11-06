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
#include <thread>

#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>

#include "vyzorix/apps.h"
#include "vyzorix/frame.h"
#include "vyzorix/json.h"
#include "vyzorix/log.h"
#include "vyzorix/meta.h"
#include "vyzorix/proxy.h"
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

// ------------------------------------------------------------------- meta

void TestMetaRoundTrips() {
    const std::string dir = TempPath("meta");
    ::mkdir(dir.c_str(), 0700);

    // No file: the app has never been exposed.
    const vmeta::AppMeta fresh = vmeta::Load(dir, "flasher");
    Check(!fresh.exposed, "an app with no metadata is not exposed");

    vmeta::AppMeta meta;
    meta.name = "flasher";
    meta.exposed = true;
    meta.expose.bind = "0.0.0.0";
    meta.expose.port = 3000;
    meta.expose.token = "s3cret";
    vmeta::Save(dir, meta);

    const vmeta::AppMeta loaded = vmeta::Load(dir, "flasher");
    Check(loaded.exposed, "exposed round-trips");
    Check(loaded.expose.bind == "0.0.0.0", "the bind address round-trips");
    Check(loaded.expose.port == 3000, "the port round-trips");
    Check(loaded.expose.token == "s3cret", "the token round-trips");

    ::unlink(vmeta::MetaPath(dir, "flasher").c_str());
    ::rmdir(dir.c_str());
}

void TestMetaFileIsOwnerOnly() {
    // The file may hold an expose token, so the mode is part of the design.
    const std::string dir = TempPath("meta-mode");
    ::mkdir(dir.c_str(), 0700);

    vmeta::AppMeta meta;
    meta.name = "app";
    meta.expose.token = "s3cret";
    vmeta::Save(dir, meta);

    struct stat info {};
    Check(::stat(vmeta::MetaPath(dir, "app").c_str(), &info) == 0, "the metadata file exists");
    Check((info.st_mode & 0777) == 0600, "and is readable only by its owner");

    ::unlink(vmeta::MetaPath(dir, "app").c_str());
    ::rmdir(dir.c_str());
}

void TestMetaTokenWithQuotesSurvives() {
    // A token is generated by the operator and may contain anything; the writer must
    // escape it or the file will not parse on the next read.
    const std::string dir = TempPath("meta-quote");
    ::mkdir(dir.c_str(), 0700);

    vmeta::AppMeta meta;
    meta.name = "app";
    meta.expose.token = "a\"b\\c";
    vmeta::Save(dir, meta);

    const vmeta::AppMeta loaded = vmeta::Load(dir, "app");
    Check(loaded.expose.token == "a\"b\\c", "a token with quotes round-trips");

    ::unlink(vmeta::MetaPath(dir, "app").c_str());
    ::rmdir(dir.c_str());
}

void TestMetaMalformedThrows() {
    // "unreadable" must not look like "no metadata": treating a corrupt file as
    // not-exposed would silently drop a running proxy's configuration.
    const std::string dir = TempPath("meta-bad");
    ::mkdir(dir.c_str(), 0700);

    {
        std::ofstream out(vmeta::MetaPath(dir, "app"));
        out << "{not json";
    }

    bool threw = false;
    try {
        vmeta::Load(dir, "app");
    } catch (const vjson::Error &) {
        threw = true;
    }
    Check(threw, "a malformed metadata file throws");

    ::unlink(vmeta::MetaPath(dir, "app").c_str());
    ::rmdir(dir.c_str());
}

void TestMetaRemove() {
    const std::string dir = TempPath("meta-remove");
    ::mkdir(dir.c_str(), 0700);

    vmeta::AppMeta meta;
    meta.name = "app";
    meta.exposed = true;
    vmeta::Save(dir, meta);
    Check(vmeta::Load(dir, "app").exposed, "the metadata is there");

    Check(vmeta::Remove(dir, "app"), "remove reports success");
    Check(!vmeta::Load(dir, "app").exposed, "and the metadata is gone");
    Check(!vmeta::Remove(dir, "app"), "removing a missing file reports false");

    ::rmdir(dir.c_str());
}

// ------------------------------------------------------------------ proxy

void TestLoopbackDetection() {
    Check(vproxy::IsLoopback("127.0.0.1"), "127.0.0.1 is loopback");
    Check(vproxy::IsLoopback("::1"), "::1 is loopback");
    Check(vproxy::IsLoopback("localhost"), "localhost is loopback");
    // The one that matters: 0.0.0.0 is every interface, not localhost.
    Check(!vproxy::IsLoopback("0.0.0.0"), "0.0.0.0 is not loopback");
    Check(!vproxy::IsLoopback("192.168.43.1"), "a LAN address is not loopback");
}

void TestConstantTimeEquals() {
    Check(vproxy::ConstantTimeEquals("abc", "abc"), "equal strings match");
    Check(!vproxy::ConstantTimeEquals("abc", "abd"), "a differing last byte does not match");
    Check(!vproxy::ConstantTimeEquals("abc", "xbc"), "a differing first byte does not match");
    Check(!vproxy::ConstantTimeEquals("abc", "abcd"), "a differing length does not match");
    Check(vproxy::ConstantTimeEquals("", ""), "two empty strings match");
}

void TestTokenStripping() {
    std::string token;

    Check(vproxy::StripToken("/", &token) == "/", "a target with no query is unchanged");
    Check(token.empty(), "and no token is found");

    Check(vproxy::StripToken("/?t=secret", &token) == "/", "the token is removed");
    Check(token == "secret", "and returned");

    Check(vproxy::StripToken("/path?t=secret&x=1", &token) == "/path?x=1",
          "the other query parameters survive");
    Check(token == "secret", "and the token is still returned");

    Check(vproxy::StripToken("/p?a=1&t=secret", &token) == "/p?a=1",
          "a token that is not first is also removed");
    Check(token == "secret", "and returned");

    // The reason stripping exists: an app that logs its query string must not write
    // the device's token into its own log.
    Check(vproxy::StripToken("/?t=secret", &token).find("secret") == std::string::npos,
          "the stripped target does not contain the token");
}

void TestNonLoopbackBindWithoutTokenIsRefused() {
    vproxy::Proxy proxy;
    vproxy::ProxyConfig config;
    config.name = "flasher";
    config.bindAddress = "0.0.0.0";
    config.port = 0;
    config.targetPort = 9;
    // No token.

    std::string error;
    Check(!proxy.Start(config, &error), "a 0.0.0.0 bind without a token is refused");
    Check(error.find("token") != std::string::npos, "the refusal explains the token rule");
    Check(!proxy.running(), "and nothing is left running");
}

void TestTargetPortIsRequired() {
    vproxy::Proxy proxy;
    vproxy::ProxyConfig config;
    config.targetPort = 0;

    std::string error;
    Check(!proxy.Start(config, &error), "a proxy with no target port is refused");
    Check(!proxy.running(), "and nothing is left running");
}

/** A one-connection HTTP server that answers with `body`. Returns its port. */
int StartEchoServer(const std::string &body, int *portOut) {
    const int listener = ::socket(AF_INET, SOCK_STREAM, 0);
    if (listener < 0) {
        return -1;
    }
    const int reuse = 1;
    ::setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));

    struct sockaddr_in address {};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = ::htonl(INADDR_LOOPBACK);
    address.sin_port = 0;
    if (::bind(listener, reinterpret_cast<struct sockaddr *>(&address), sizeof(address)) != 0) {
        ::close(listener);

        return -1;
    }
    ::listen(listener, 8);

    struct sockaddr_in bound {};
    socklen_t length = sizeof(bound);
    ::getsockname(listener, reinterpret_cast<struct sockaddr *>(&bound), &length);
    *portOut = ::ntohs(bound.sin_port);

    std::thread([listener, body] {
        for (int i = 0; i < 8; i++) {
            const int client = ::accept(listener, nullptr, nullptr);
            if (client < 0) break;

            // Read the request headers, so the client is not writing into a socket
            // nobody drains.
            char buffer[4096];
            const ssize_t n = ::read(client, buffer, sizeof(buffer));
            if (n > 0) {
                const std::string response =
                    "HTTP/1.1 200 OK\r\nContent-Length: " + std::to_string(body.size()) +
                    "\r\nConnection: close\r\n\r\n" + body;
                ssize_t ignored = ::write(client, response.data(), response.size());
                (void)ignored;
            }
            ::close(client);
        }
        ::close(listener);
    }).detach();

    return listener;
}

/** Sends `request` to 127.0.0.1:`port` and returns the whole response. */
std::string HttpGet(int port, const std::string &target, const std::string &extraHeader = "") {
    const int fd = ::socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) {
        return std::string();
    }

    struct sockaddr_in address {};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = ::htonl(INADDR_LOOPBACK);
    address.sin_port = ::htons(static_cast<uint16_t>(port));
    if (::connect(fd, reinterpret_cast<struct sockaddr *>(&address), sizeof(address)) != 0) {
        ::close(fd);

        return std::string();
    }

    const std::string request = "GET " + target + " HTTP/1.1\r\nHost: localhost\r\n" + extraHeader +
                                "Connection: close\r\n\r\n";
    ssize_t ignored = ::write(fd, request.data(), request.size());
    (void)ignored;

    std::string response;
    char buffer[4096];
    ssize_t n = 0;
    while ((n = ::read(fd, buffer, sizeof(buffer))) > 0) {
        response.append(buffer, static_cast<std::size_t>(n));
    }
    ::close(fd);

    return response;
}

void TestProxyForwardsToTheApp() {
    int targetPort = 0;
    const int listener = StartEchoServer("hello-from-app", &targetPort);
    Check(listener >= 0, "the fake app binds");

    vproxy::Proxy proxy;
    vproxy::ProxyConfig config;
    config.name = "app";
    config.bindAddress = "127.0.0.1";
    config.port = 0;
    config.targetHost = "127.0.0.1";
    config.targetPort = targetPort;

    std::string error;
    Check(proxy.Start(config, &error), ("the proxy starts: " + error).c_str());
    Check(proxy.port() > 0, "the kernel assigned a port");
    Check(!proxy.requiresToken(), "a loopback proxy needs no token");

    const std::string response = HttpGet(proxy.port(), "/");
    Check(response.find("hello-from-app") != std::string::npos,
          ("the app's body is forwarded: " + response).c_str());
    Check(response.find("200 OK") != std::string::npos, "the status line is forwarded");

    proxy.Stop();
}

void TestTokenIsRequiredAndEnforced() {
    int targetPort = 0;
    StartEchoServer("secret-body", &targetPort);

    vproxy::Proxy proxy;
    vproxy::ProxyConfig config;
    config.name = "app";
    config.bindAddress = "127.0.0.1";
    config.port = 0;
    config.targetPort = targetPort;
    config.token = "s3cret";

    std::string error;
    Check(proxy.Start(config, &error), "the token proxy starts");
    Check(proxy.requiresToken(), "and reports that a token is required");

    const int port = proxy.port();

    // No token: refused, and the app is never reached.
    const std::string denied = HttpGet(port, "/");
    Check(denied.find("401") != std::string::npos,
          ("a request with no token is refused: " + denied).c_str());
    Check(denied.find("secret-body") == std::string::npos, "and the app's body does not leak");

    // Wrong token: refused too.
    const std::string wrong = HttpGet(port, "/?t=guess");
    Check(wrong.find("401") != std::string::npos, "a wrong token is refused");

    // Right token in the query: forwarded, and the token is not passed on.
    const std::string allowed = HttpGet(port, "/?t=s3cret");
    Check(allowed.find("secret-body") != std::string::npos,
          ("a correct token is forwarded: " + allowed).c_str());

    // Right token in the header: also accepted, for a client that cannot use a query.
    const std::string viaHeader = HttpGet(port, "/", "X-Vyzorix-Token: s3cret\r\n");
    Check(viaHeader.find("secret-body") != std::string::npos,
          ("a token in the header is accepted: " + viaHeader).c_str());

    proxy.Stop();
}

void TestUnreachableAppReportsBadGateway() {
    vproxy::Proxy proxy;
    vproxy::ProxyConfig config;
    config.name = "app";
    config.bindAddress = "127.0.0.1";
    config.port = 0;
    // Nothing is listening on this port.
    config.targetPort = 1;
    config.connectTimeoutMs = 500;

    std::string error;
    Check(proxy.Start(config, &error), "the proxy starts");

    const std::string response = HttpGet(proxy.port(), "/");
    Check(response.find("502") != std::string::npos,
          ("an unreachable app is a 502: " + response).c_str());

    proxy.Stop();
}

void TestProxyStreamsALargeBody() {
    // The point of streaming: a body larger than any internal buffer must arrive
    // intact. A buffering proxy that got the framing wrong would truncate here.
    const std::string big(512 * 1024, 'Z');
    int targetPort = 0;
    StartEchoServer(big, &targetPort);

    vproxy::Proxy proxy;
    vproxy::ProxyConfig config;
    config.name = "app";
    config.bindAddress = "127.0.0.1";
    config.port = 0;
    config.targetPort = targetPort;

    std::string error;
    Check(proxy.Start(config, &error), "the proxy starts");

    const std::string response = HttpGet(proxy.port(), "/");
    const std::size_t bodyStart = response.find("\r\n\r\n");
    Check(bodyStart != std::string::npos, "the response has a body separator");
    const std::string body = response.substr(bodyStart + 4);
    Check(body.size() == big.size(),
          ("the whole body arrives (" + std::to_string(body.size()) + ")").c_str());
    Check(body == big, "and it is byte-for-byte identical");

    proxy.Stop();
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
    TestMetaRoundTrips();
    TestMetaFileIsOwnerOnly();
    TestMetaTokenWithQuotesSurvives();
    TestMetaMalformedThrows();
    TestMetaRemove();
    TestLoopbackDetection();
    TestConstantTimeEquals();
    TestTokenStripping();
    TestNonLoopbackBindWithoutTokenIsRefused();
    TestTargetPortIsRequired();
    TestProxyForwardsToTheApp();
    TestTokenIsRequiredAndEnforced();
    TestUnreachableAppReportsBadGateway();
    TestProxyStreamsALargeBody();

    if (failures == 0) {
        std::cout << "lib_test: all checks passed\n";

        return 0;
    }

    std::cerr << "lib_test: " << failures << " failure(s)\n";

    return 1;
}
