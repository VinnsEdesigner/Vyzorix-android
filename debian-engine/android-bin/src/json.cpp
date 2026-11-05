// JSON reader/writer — see json.h for why this is hand-rolled.

#include "json.h"

#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstdlib>

namespace abjson {
namespace {

class Parser {
public:
    explicit Parser(const std::string &text) : text_(text) {}

    Value ParseDocument() {
        SkipWhitespace();
        Value value = ParseValue();

        SkipWhitespace();
        if (position_ != text_.size()) {
            Fail("trailing content after the document");
        }

        return value;
    }

private:
    [[noreturn]] void Fail(const std::string &why) const {
        char where[64];
        std::snprintf(where, sizeof(where), " at byte %zu", position_);

        throw Error("json: " + why + where);
    }

    bool AtEnd() const {
        return position_ >= text_.size();
    }

    char Peek() const {
        if (AtEnd()) {
            Fail("unexpected end of input");
        }

        return text_[position_];
    }

    char Take() {
        const char c = Peek();
        position_++;

        return c;
    }

    void Expect(char c) {
        if (Take() != c) {
            Fail(std::string("expected '") + c + "'");
        }
    }

    void SkipWhitespace() {
        while (!AtEnd()) {
            const char c = text_[position_];
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                position_++;
                continue;
            }
            break;
        }
    }

    Value ParseValue() {
        switch (Peek()) {
        case '{':
            return ParseObject();
        case '[':
            return ParseArray();
        case '"': {
            Value v;
            v.type = Value::Type::String;
            v.string = ParseString();
            return v;
        }
        case 't':
        case 'f':
            return ParseBool();
        case 'n':
            return ParseNull();
        default:
            return ParseNumber();
        }
    }

    Value ParseObject() {
        Value v;
        v.type = Value::Type::Object;

        Expect('{');
        SkipWhitespace();
        if (Peek() == '}') {
            Take();
            return v;
        }

        while (true) {
            SkipWhitespace();
            const std::string key = ParseString();
            SkipWhitespace();
            Expect(':');
            SkipWhitespace();
            v.object[key] = ParseValue();
            SkipWhitespace();

            const char c = Take();
            if (c == '}') {
                break;
            }
            if (c != ',') {
                Fail("expected ',' or '}' in object");
            }
        }

        return v;
    }

    Value ParseArray() {
        Value v;
        v.type = Value::Type::Array;

        Expect('[');
        SkipWhitespace();
        if (Peek() == ']') {
            Take();
            return v;
        }

        while (true) {
            SkipWhitespace();
            v.array.push_back(ParseValue());
            SkipWhitespace();

            const char c = Take();
            if (c == ']') {
                break;
            }
            if (c != ',') {
                Fail("expected ',' or ']' in array");
            }
        }

        return v;
    }

    std::string ParseString() {
        Expect('"');
        std::string out;

        while (true) {
            if (AtEnd()) {
                Fail("unterminated string");
            }

            const char c = Take();
            if (c == '"') {
                break;
            }
            if (c != '\\') {
                out.push_back(c);
                continue;
            }

            const char escape = Take();
            switch (escape) {
            case '"':
                out.push_back('"');
                break;
            case '\\':
                out.push_back('\\');
                break;
            case '/':
                out.push_back('/');
                break;
            case 'b':
                out.push_back('\b');
                break;
            case 'f':
                out.push_back('\f');
                break;
            case 'n':
                out.push_back('\n');
                break;
            case 'r':
                out.push_back('\r');
                break;
            case 't':
                out.push_back('\t');
                break;
            case 'u': {
                // \uXXXX. Only the BMP is handled: the bridge's JSON is ASCII
                // (api names, tokens, small payloads), and a surrogate pair here
                // would be a document this reader does not understand, so it is
                // rejected rather than silently mangled.
                const unsigned code = ParseHex4();
                if (code >= 0xD800 && code <= 0xDFFF) {
                    Fail("surrogate pairs are not supported");
                }
                AppendUtf8(out, code);
                break;
            }
            default:
                Fail("unknown escape sequence");
            }
        }

        return out;
    }

    unsigned ParseHex4() {
        unsigned value = 0;
        for (int i = 0; i < 4; i++) {
            const char c = Take();
            value <<= 4;
            if (c >= '0' && c <= '9') {
                value |= static_cast<unsigned>(c - '0');
            } else if (c >= 'a' && c <= 'f') {
                value |= static_cast<unsigned>(c - 'a' + 10);
            } else if (c >= 'A' && c <= 'F') {
                value |= static_cast<unsigned>(c - 'A' + 10);
            } else {
                Fail("bad \\u escape");
            }
        }

        return value;
    }

    static void AppendUtf8(std::string &out, unsigned code) {
        if (code < 0x80) {
            out.push_back(static_cast<char>(code));
        } else if (code < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (code >> 6)));
            out.push_back(static_cast<char>(0x80 | (code & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xE0 | (code >> 12)));
            out.push_back(static_cast<char>(0x80 | ((code >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (code & 0x3F)));
        }
    }

    Value ParseBool() {
        Value v;
        v.type = Value::Type::Bool;

        if (text_.compare(position_, 4, "true") == 0) {
            v.boolean = true;
            position_ += 4;
        } else if (text_.compare(position_, 5, "false") == 0) {
            v.boolean = false;
            position_ += 5;
        } else {
            Fail("expected true or false");
        }

        return v;
    }

    Value ParseNull() {
        if (text_.compare(position_, 4, "null") != 0) {
            Fail("expected null");
        }
        position_ += 4;

        return Value{};
    }

    Value ParseNumber() {
        const char *start = text_.c_str() + position_;
        char *end = nullptr;

        errno = 0;
        const double parsed = std::strtod(start, &end);
        if (end == start || errno == ERANGE || !std::isfinite(parsed)) {
            Fail("bad number");
        }
        position_ += static_cast<std::size_t>(end - start);

        Value v;
        v.type = Value::Type::Number;
        v.number = parsed;

        return v;
    }

    const std::string &text_;
    std::size_t position_ = 0;
};

} // namespace

const Value *Value::Find(const std::string &key) const {
    if (type != Type::Object) {
        return nullptr;
    }

    const auto it = object.find(key);

    return it == object.end() ? nullptr : &it->second;
}

Value Parse(const std::string &text) {
    Parser parser(text);

    return parser.ParseDocument();
}

std::string Escape(const std::string &raw) {
    std::string out;
    out.reserve(raw.size() + 8);

    for (const char c : raw) {
        switch (c) {
        case '"':
            out += "\\\"";
            break;
        case '\\':
            out += "\\\\";
            break;
        case '\b':
            out += "\\b";
            break;
        case '\f':
            out += "\\f";
            break;
        case '\n':
            out += "\\n";
            break;
        case '\r':
            out += "\\r";
            break;
        case '\t':
            out += "\\t";
            break;
        default:
            if (static_cast<unsigned char>(c) < 0x20) {
                char buf[8];
                std::snprintf(buf, sizeof(buf), "\\u%04x", static_cast<unsigned char>(c));
                out += buf;
            } else {
                out.push_back(c);
            }
        }
    }

    return out;
}

} // namespace abjson
