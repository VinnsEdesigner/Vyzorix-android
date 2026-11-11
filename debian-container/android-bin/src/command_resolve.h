// Maps an invocation's argv onto the wire API name.
//
// Split out of `main` so the mapping is testable on the host: the bug it guards against
// (treating a `key=value` argument as a subcommand) is invisible to a compile and only
// shows on a device, which is exactly the kind of thing a host test should catch.

#ifndef VYZORIX_ANDROID_BIN_COMMAND_RESOLVE_H
#define VYZORIX_ANDROID_BIN_COMMAND_RESOLVE_H

#include <string>

#include "command_catalog.h"

namespace android_bin {

/** How an invocation maps onto the wire API. */
struct Invocation {
    /** The wire API name to call. */
    std::string api;
    /** The argv index of the first argument: 1, or 2 when a subcommand was consumed. */
    int first_arg;
    /** True when the command was not in the catalog and the raw name is used unchanged. */
    bool raw;
};

/**
 * Maps `command` (argv[0]'s basename) and `first_word` (argv[1], or nullptr) to the wire
 * API.
 *
 * A subcommand is consumed only when `command first_word` resolves to a real catalog
 * entry. A leading-dash test is not enough: `enable=true` has no dash, and consuming it
 * made every top-level command with arguments fail with "unknown api <command>", because
 * the guess resolved to nullptr and the fallback then used the *command* name as the API.
 *
 * When the command is not in the catalog the raw name is used unchanged and no argument
 * is consumed, so an old symlink or a direct `android-bin <api> key=value` call still
 * works.
 */
inline Invocation ResolveInvocation(const char *command, const char *first_word) {
    const std::string name = command != nullptr ? command : "";

    const char *sub = nullptr;
    if (first_word != nullptr && first_word[0] != '\0' && first_word[0] != '-' &&
        ResolveCommand(name.c_str(), first_word) != nullptr) {
        sub = first_word;
    }

    const char *resolved =
        sub != nullptr ? ResolveCommand(name.c_str(), sub) : ResolveCommandTop(name.c_str());
    if (resolved != nullptr) {
        return Invocation{resolved, sub != nullptr ? 2 : 1, false};
    }

    return Invocation{name, 1, true};
}

} // namespace android_bin

#endif // VYZORIX_ANDROID_BIN_COMMAND_RESOLVE_H
