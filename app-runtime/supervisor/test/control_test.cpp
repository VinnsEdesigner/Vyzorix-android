// Unit tests for the control protocol's request handler.
//
// `HandleRequest` is pure — a JSON string in, a JSON string out — so the op
// semantics are pinned here without a socket or a process. The one that matters
// most is `expose`: refusing a non-loopback bind without a token is the difference
// between "reachable on this phone" and "reachable by everyone on the hotspot", and
// it is a refusal, not a warning, so it is tested as one.

#include <fstream>
#include <set>
#include <sys/stat.h>
#include <iostream>
#include <string>

#include <unistd.h>

#include "vyzorix/apps.h"
#include "vyzorix/control.h"
#include "vyzorix/json.h"
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

/** A manager over a temp registry, with a host that spawns nothing. */
class NullHost : public Host {
public:
    int Spawn(const std::string &, const std::string &,
              const std::map<std::string, std::string> &, const std::string &) override {
        const int pid = next_++;
        alive_.insert(pid);

        return pid;
    }

    bool Exited(int pid, ExitKind *kind, int *code) override {
        if (alive_.count(pid) == 0) {
            *kind = ExitKind::NotRunning;
            *code = 0;

            return true;
        }
        *kind = ExitKind::Exited;
        *code = 0;

        return false;
    }

    // A well-behaved app dies on SIGTERM, so Stop() does not have to wait out its
    // grace period. Without this the stop path polls until the deadline, which in a
    // test with a frozen clock is forever.
    void Terminate(int pid) override { alive_.erase(pid); }
    void Kill(int pid) override { alive_.erase(pid); }

    std::uint64_t StartTicks(int pid) override {
        return alive_.count(pid) != 0 ? static_cast<std::uint64_t>(pid) : 0;
    }

    // Advances on every read, so any deadline in the code under test is reachable.
    std::int64_t NowMs() override { return now_++; }
    void SleepMs(std::int64_t ms) override { now_ += ms; }

private:
    int next_ = 100;
    std::set<int> alive_;
    std::int64_t now_ = 0;
};

struct Fixture {
    NullHost host;
    std::string base = "/tmp/vyzorix-control-test-" + std::to_string(::getpid());
    std::string path = base + "/apps.json";
    std::string dir = base;
    Manager manager;

    Fixture() : manager(vapps::Registry(), path, base, &host) {
        ::mkdir(base.c_str(), 0755);
        ::unlink(path.c_str());

        vapps::App app;
        app.name = "flasher";
        app.cmd = "sleep 1000";
        app.port = 3000;
        app.autostart = false;
        app.env["A"] = "1";
        manager.mutableRegistry().Upsert(app);
        manager.Persist();
    }

    ~Fixture() {
        ::unlink(path.c_str());
        ::rmdir(base.c_str());
    }

    std::string Request(const std::string &json) { return HandleRequest(&manager, json); }

    /** True when the reply says ok. */
    static bool IsOk(const std::string &reply) {
        const vjson::Value parsed = vjson::Parse(reply);
        const vjson::Value *ok = parsed.Find("ok");

        return ok != nullptr && ok->boolean;
    }

    static std::string ErrorText(const std::string &reply) {
        const vjson::Value parsed = vjson::Parse(reply);
        const vjson::Value *error = parsed.Find("error");

        return error != nullptr && error->IsString() ? error->string : std::string();
    }
};

// --------------------------------------------------------------------------

void TestPing() {
    Fixture f;

    Check(Fixture::IsOk(f.Request("{\"op\":\"ping\"}")), "ping is ok");
}

void TestUnknownOpIsRefused() {
    Fixture f;

    const std::string reply = f.Request("{\"op\":\"frobnicate\",\"name\":\"flasher\"}");
    Check(!Fixture::IsOk(reply), "an unknown op is refused, not ignored");
    Check(Fixture::ErrorText(reply).find("unknown op") != std::string::npos,
          "the error names the unknown op");
}

void TestMissingOpIsRefused() {
    Fixture f;

    Check(!Fixture::IsOk(f.Request("{}")), "a request with no op is refused");
}

void TestMalformedRequestIsRefused() {
    Fixture f;

    Check(!Fixture::IsOk(f.Request("{not json")), "malformed JSON is refused");
}

void TestNamedOpsRequireAName() {
    Fixture f;

    for (const char *op : {"start", "stop", "restart", "logs", "env", "create", "expose"}) {
        const std::string reply = f.Request(std::string("{\"op\":\"") + op + "\"}");
        Check(!Fixture::IsOk(reply), std::string(op) + " without a name is refused");
    }
}

void TestListReturnsTheRegistry() {
    Fixture f;

    const std::string reply = f.Request("{\"op\":\"list\"}");
    Check(Fixture::IsOk(reply), "list is ok");
    Check(reply.find("flasher") != std::string::npos, "list includes the app");
    Check(reply.find("\"port\":3000") != std::string::npos, "list includes the port");
}

void TestStartStopRestart() {
    Fixture f;

    Check(Fixture::IsOk(f.Request("{\"op\":\"start\",\"name\":\"flasher\"}")), "start is ok");
    Check(Fixture::IsOk(f.Request("{\"op\":\"stop\",\"name\":\"flasher\"}")), "stop is ok");
    Check(Fixture::IsOk(f.Request("{\"op\":\"restart\",\"name\":\"flasher\"}")), "restart is ok");

    const std::string unknown = f.Request("{\"op\":\"start\",\"name\":\"nope\"}");
    Check(!Fixture::IsOk(unknown), "starting an unknown app is refused");
}

void TestEnvReturnsTheAppEnvironment() {
    Fixture f;

    const std::string reply = f.Request("{\"op\":\"env\",\"name\":\"flasher\"}");
    Check(Fixture::IsOk(reply), "env is ok");
    Check(reply.find("\"A\":\"1\"") != std::string::npos, "env carries the app's variables");
}

void TestCreateAndRemove() {
    Fixture f;

    const std::string created =
        f.Request("{\"op\":\"create\",\"name\":\"newapp\",\"cmd\":\"sleep 5\",\"port\":8080,"
                  "\"autostart\":true,\"dir\":\"" + f.dir + "/newapp\"}");
    Check(Fixture::IsOk(created), "create is ok");
    Check(f.manager.registry().Find("newapp") != nullptr, "create added the app");

    const vapps::App *app = f.manager.registry().Find("newapp");
    Check(app->port == 8080, "create kept the port");
    Check(app->autostart, "create kept autostart");
    Check(app->dir == f.dir + "/newapp", "create kept the directory");

    // create makes the app's directory, so a following start can chdir into it.
    Check(::access(app->dir.c_str(), F_OK) == 0, "create made the app directory");

    Check(!Fixture::IsOk(f.Request("{\"op\":\"create\",\"name\":\"x\"}")),
          "create without a cmd is refused");

    Check(Fixture::IsOk(f.Request("{\"op\":\"remove\",\"name\":\"newapp\"}")), "remove is ok");
    Check(f.manager.registry().Find("newapp") == nullptr, "remove removed the app");
    Check(!Fixture::IsOk(f.Request("{\"op\":\"remove\",\"name\":\"newapp\"}")),
          "removing a missing app is refused");
}

void TestCreatePersists() {
    Fixture f;

    f.Request("{\"op\":\"create\",\"name\":\"persisted\",\"cmd\":\"sleep 5\",\"dir\":\"" +
              f.dir + "/persisted\"}");

    // Re-read from disk: a create that only changed memory would be lost on the
    // next restart, which is the whole point of the registry being a file.
    const vapps::Registry reloaded = vapps::Registry::Load(f.path);
    Check(reloaded.Find("persisted") != nullptr, "create survived a reload from disk");
}

void TestExposeRefusesNonLoopbackWithoutToken() {
    Fixture f;

    // The security requirement (§7.2). 0.0.0.0 is every interface, so this must be a
    // refusal, not a warning.
    const std::string reply =
        f.Request("{\"op\":\"expose\",\"name\":\"flasher\",\"bind\":\"0.0.0.0\"}");
    Check(!Fixture::IsOk(reply), "expose on 0.0.0.0 without a token is refused");
    Check(Fixture::ErrorText(reply).find("token") != std::string::npos,
          "the refusal explains that a token is required");
}

void TestExposeAllowsNonLoopbackWithToken() {
    Fixture f;

    const std::string reply = f.Request(
        "{\"op\":\"expose\",\"name\":\"flasher\",\"bind\":\"0.0.0.0\",\"token\":\"s3cret\"}");
    Check(Fixture::IsOk(reply), "expose on 0.0.0.0 with a token is allowed");
}

void TestExposeAllowsLoopbackWithoutToken() {
    Fixture f;

    // The default bind needs no token: it is reachable only from the phone itself.
    const std::string reply = f.Request("{\"op\":\"expose\",\"name\":\"flasher\"}");
    Check(Fixture::IsOk(reply), "expose on the loopback needs no token");
    Check(reply.find("127.0.0.1:3000") != std::string::npos, "the reply names the URL");
}

void TestLogsReturnsTheTail() {
    Fixture f;

    // Write a log for the app, then read it back through the protocol.
    const std::string logPath = f.manager.LogPathFor("flasher");
    {
        std::ofstream out(logPath);
        out << "line one\nline two\n";
    }

    const std::string reply = f.Request("{\"op\":\"logs\",\"name\":\"flasher\"}");
    Check(Fixture::IsOk(reply), "logs is ok");
    Check(reply.find("line two") != std::string::npos, "logs returns the content");

    ::unlink(logPath.c_str());
}

void TestLogsOnAMissingLogIsEmptyNotAnError() {
    Fixture f;

    // An app that has never run has no log. That is not a failure.
    ::unlink(f.manager.LogPathFor("flasher").c_str());
    const std::string reply = f.Request("{\"op\":\"logs\",\"name\":\"flasher\"}");
    Check(Fixture::IsOk(reply), "a missing log is an empty result, not an error");
}

} // namespace

int main() {
    TestPing();
    TestUnknownOpIsRefused();
    TestMissingOpIsRefused();
    TestMalformedRequestIsRefused();
    TestNamedOpsRequireAName();
    TestListReturnsTheRegistry();
    TestStartStopRestart();
    TestEnvReturnsTheAppEnvironment();
    TestCreateAndRemove();
    TestCreatePersists();
    TestExposeRefusesNonLoopbackWithoutToken();
    TestExposeAllowsNonLoopbackWithToken();
    TestExposeAllowsLoopbackWithoutToken();
    TestLogsReturnsTheTail();
    TestLogsOnAMissingLogIsEmptyNotAnError();

    if (failures == 0) {
        std::cout << "control_test: all checks passed\n";

        return 0;
    }

    std::cerr << "control_test: " << failures << " failure(s)\n";

    return 1;
}
