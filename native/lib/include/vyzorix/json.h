// A small JSON reader and writer.
//
// The bridge speaks JSON on the wire (android-core's BridgeProtocol), so the
// `android-bin` client needs to build a request and read a response. It is
// deliberately not a general library: it covers the JSON that bridge frames carry —
// objects, strings, numbers, booleans, null, arrays — and rejects anything it does
// not understand rather than guessing.
//
// Why hand-rolled rather than a dependency: this binary is cross-compiled into the
// Debian userspace, and every dependency is a package that must exist in the rootfs
// for every architecture. A few hundred lines that build anywhere is cheaper than
// that, and the surface is small enough to keep correct.

#ifndef VYZORIX_JSON_H
#define VYZORIX_JSON_H

#include <cstdint>
#include <map>
#include <stdexcept>
#include <string>
#include <vector>

namespace vjson {

/** Thrown when a document is not valid JSON this reader understands. */
class Error : public std::runtime_error {
public:
    explicit Error(const std::string &what) : std::runtime_error(what) {}
};

/** A parsed JSON value. One active member, selected by `type`. */
struct Value {
    enum class Type : std::uint8_t { Null, Bool, Number, String, Array, Object };

    Type type = Type::Null;
    bool boolean = false;
    double number = 0.0;
    std::string string;
    std::vector<Value> array;
    std::map<std::string, Value> object;

    bool IsNull() const {
        return type == Type::Null;
    }
    bool IsString() const {
        return type == Type::String;
    }

    /**
     * The member named `key`, or nullptr. Only valid on an object.
     *
     * Returning nullptr rather than a default lets a caller distinguish "absent"
     * from "present but null", which the response shape relies on: `result` and
     * `error` are each optional.
     */
    const Value *Find(const std::string &key) const;
};

/** Parses `text`. Throws [Error] on anything malformed. */
Value Parse(const std::string &text);

/** Escapes `raw` as a JSON string body (without the surrounding quotes). */
std::string Escape(const std::string &raw);

} // namespace vjson

#endif // VYZORIX_JSON_H
