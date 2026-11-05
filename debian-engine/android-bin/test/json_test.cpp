// Unit tests for the JSON reader and writer.
//
// The bridge client cannot be tested off-device — it needs a live android.sock —
// but the JSON is pure logic and the one place a wire contract can silently rot
// (a response that parses on the device but not here, or vice versa). This runs on
// the host, with no dependencies, via `make check`.
//
// It deliberately tests the shapes the bridge actually sends: the auth response,
// a success response, and an error response.

#include <iostream>
#include <string>

#include "json.h"

namespace {

int failures = 0;

void Check(bool condition, const std::string &what) {
    if (!condition) {
        std::cerr << "FAIL: " << what << "\n";
        failures++;
    }
}

void TestScalars() {
    Check(abjson::Parse("null").IsNull(), "null parses to null");
    Check(abjson::Parse("true").boolean, "true parses to true");
    Check(!abjson::Parse("false").boolean, "false parses to false");
    Check(abjson::Parse("42").number == 42.0, "integer");
    Check(abjson::Parse("-3.5e2").number == -350.0, "float with exponent");
    Check(abjson::Parse("\"hi\"").string == "hi", "plain string");
}

void TestStrings() {
    Check(abjson::Parse("\"a\\nb\"").string == "a\nb", "\\n escape");
    Check(abjson::Parse("\"q\\\"q\"").string == "q\"q", "escaped quote");
    Check(abjson::Parse("\"\\u0041\"").string == "A", "\\u escape");
    Check(abjson::Parse("\"\\u00e9\"").string == "\xc3\xa9", "\\u escape to UTF-8");
    Check(abjson::Escape("a\"b\\c\nd") == "a\\\"b\\\\c\\nd", "Escape round-trips");
}

void TestObjects() {
    const abjson::Value v = abjson::Parse("{\"id\":\"7\",\"ok\":true,\"n\":3}");
    Check(v.type == abjson::Value::Type::Object, "object type");
    Check(v.Find("id") != nullptr && v.Find("id")->string == "7", "object member");
    Check(v.Find("ok") != nullptr && v.Find("ok")->boolean, "object bool member");
    Check(v.Find("absent") == nullptr, "absent member is nullptr");

    const abjson::Value nested = abjson::Parse("{\"a\":{\"b\":[1,2,3]}}");
    const abjson::Value *a = nested.Find("a");
    Check(a != nullptr, "nested object");
    const abjson::Value *b = a->Find("b");
    Check(b != nullptr && b->type == abjson::Value::Type::Array && b->array.size() == 3,
          "nested array");
}

// The three response shapes the bridge sends, verbatim from BridgeProtocol.kt.
void TestBridgeShapes() {
    const abjson::Value auth =
        abjson::Parse("{\"id\":\"auth\",\"ok\":true,\"result\":\"{\\\"session\\\":\\\"s-1\\\"}\"}");
    Check(auth.Find("ok") != nullptr && auth.Find("ok")->boolean, "auth ok");
    const abjson::Value *result = auth.Find("result");
    Check(result != nullptr && result->IsString(), "auth result is a string");
    const abjson::Value inner = abjson::Parse(result->string);
    Check(inner.Find("session") != nullptr && inner.Find("session")->string == "s-1",
          "auth result carries the session");

    const abjson::Value ok =
        abjson::Parse("{\"id\":\"1\",\"ok\":true,\"result\":\"{\\\"v\\\":1}\"}");
    Check(ok.Find("result") != nullptr, "success carries a result");

    const abjson::Value err = abjson::Parse(
        "{\"id\":\"2\",\"ok\":false,\"error\":{\"kind\":\"unknown_api\",\"detail\":\"no api\"}}");
    Check(err.Find("error") != nullptr, "failure carries an error");
    Check(err.Find("error")->Find("kind")->string == "unknown_api", "error kind");
    Check(err.Find("error")->Find("detail")->string == "no api", "error detail");
}

void TestMalformed() {
    const char *bad[] = {
        "{", "{\"a\":}", "[1,", "\"unterminated", "tru", "{\"a\":1,}", "{\"a\" 1}", "",
    };

    for (const char *text : bad) {
        bool threw = false;
        try {
            abjson::Parse(text);
        } catch (const abjson::Error &) {
            threw = true;
        }
        Check(threw, std::string("rejects malformed: ") + text);
    }
}

} // namespace

int main() {
    TestScalars();
    TestStrings();
    TestObjects();
    TestBridgeShapes();
    TestMalformed();

    if (failures == 0) {
        std::cout << "json_test: all checks passed\n";

        return 0;
    }

    std::cerr << "json_test: " << failures << " failure(s)\n";

    return 1;
}
