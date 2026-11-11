// Host test for the command resolver: argv -> wire API.
//
// The bug this pins: `dev enable=true` reported "unknown api 'dev'". The resolver
// guessed that any non-dash first argument was a subcommand, so `enable=true` was
// consumed as one, `dev enable=true` matched nothing, and the fallback used the
// *command* name as the API. Every top-level command with arguments was affected
// (31 of 38). A subcommand must be recognised by *resolving*, not by shape.

#include <cstdlib>
#include <iostream>
#include <string>

#include "command_resolve.h"

namespace {

int failures = 0;

void expect(const std::string &what, const std::string &got, const std::string &want) {
    if (got != want) {
        std::cerr << "FAIL " << what << ": got '" << got << "', want '" << want << "'\n";
        ++failures;
    } else {
        std::cout << "ok   " << what << " -> " << got << "\n";
    }
}

void expectArg(const std::string &what, int got, int want) {
    if (got != want) {
        std::cerr << "FAIL " << what << ": first_arg got " << got << ", want " << want << "\n";
        ++failures;
    } else {
        std::cout << "ok   " << what << " -> first_arg " << got << "\n";
    }
}

} // namespace

int main() {
    // The regression: a one-column command with a key=value argument.
    {
        auto inv = android_bin::ResolveInvocation("dev", "enable=true");
        expect("dev enable=true api", inv.api, "developer_options");
        expectArg("dev enable=true", inv.first_arg, 1);
    }
    {
        auto inv = android_bin::ResolveInvocation("camera", "lines=2");
        expect("camera lines=2 api", inv.api, "camera_list");
        expectArg("camera lines=2", inv.first_arg, 1);
    }

    // A one-column command with no argument still resolves.
    {
        auto inv = android_bin::ResolveInvocation("dev", nullptr);
        expect("dev (no arg) api", inv.api, "developer_options");
    }

    // A real two-column command consumes its subcommand.
    {
        auto inv = android_bin::ResolveInvocation("admin", "owner");
        expect("admin owner api", inv.api, "device_owner");
        expectArg("admin owner", inv.first_arg, 2);
    }
    {
        auto inv = android_bin::ResolveInvocation("apps", "dump");
        expect("apps dump api", inv.api, "packages_dump");
        expectArg("apps dump", inv.first_arg, 2);
    }

    // An option is never a subcommand: `dev --list` must still list.
    {
        auto inv = android_bin::ResolveInvocation("dev", "--list");
        expect("dev --list api", inv.api, "developer_options");
        expectArg("dev --list", inv.first_arg, 1);
    }

    // An unknown command falls back to the raw name, consuming nothing — so a direct
    // `android-bin <api> key=value` call and an old symlink still work.
    {
        auto inv = android_bin::ResolveInvocation("packages_dump", "limit=5");
        expect("packages_dump limit=5 api", inv.api, "packages_dump");
        expectArg("packages_dump limit=5", inv.first_arg, 1);
    }

    if (failures != 0) {
        std::cerr << failures << " failure(s)\n";

        return 1;
    }

    std::cout << "command_resolve: all passed\n";

    return 0;
}
