#!/usr/bin/env python3
"""Writes `debian-container/android-bin/apis.txt` from the bridge's API catalogue.

## Why this exists

`apis.txt` decides which commands exist inside the container — one symlink per name.
The authority is the set of handlers the router registers. Those are two files in two
languages, and with fifty APIs a hand-maintained list is wrong within a week: a name
in `apis.txt` the router does not serve is a command that exists and fails at call
time with `unknown_api`, and a handler missing from `apis.txt` is one a user
simply cannot reach. Both are invisible until someone uses the feature.

`ApiCatalogTest` proves the two agree; this script is how they are made to agree,
so the test is a check rather than a chore.

## How it reads the catalogue

It parses `ApiCatalogue.kt` rather than running Kotlin, because the alternative is a
Gradle task that boots a JVM to print a list of strings. The parse is narrow on
purpose: it takes the `NAME` of every API whose `to` target appears in the `handlers`
map, plus the separately-registered `session`, and it fails loudly if it finds none —
a silent empty result would rewrite `apis.txt` to nothing.
"""

import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CATALOGUE = os.path.join(
    REPO, "android-core/src/main/kotlin/com/vyzorix/bridge/ApiCatalogue.kt"
)
APIS_TXT = os.path.join(REPO, "debian-container/android-bin/apis.txt")

HEADER = """# The Android APIs exposed to the container as commands.
#
# **Generated — do not edit by hand.** Written by `scripts/sync-apis-txt.py` from
# `android-core/.../bridge/ApiCatalogue.kt`, which is the single source of truth.
# `ApiCatalogTest` fails the gate if this file and the catalogue disagree, so a
# hand-edit here is caught rather than shipped.
#
# Each name becomes:
#   * a symlink to the `android-bin` binary, so `battery` runs it, and
#   * an entry in the generated api_catalog.h, which is what `android-bin --list`
#     prints and what the unknown-name check reads.
#
# Regenerate with:
#   python3 scripts/sync-apis-txt.py
#
# `auth` is deliberately absent: it is the reserved name the bridge serves *before*
# authentication, not a callable API, and the router refuses to register it.
"""


def main() -> int:
    source = open(CATALOGUE).read()

    # The `handlers` map names the *classes* (`AccessibilityApi.NAME to AccessibilityApi`).
    # The wire name is each class's own `const val NAME`, in its own file — the
    # catalogue deliberately does not repeat the strings, so they are read from where
    # they are declared rather than duplicated here.
    # Both sources of class names: the `handlers` map (singletons) and the
    # `serviceApiNames` list (handlers the service builds with its own state — the
    # session table, the USB broker). Reading only the map dropped every
    # service-registered API from `apis.txt`, which is exactly the silent
    # missing-command failure this script exists to prevent.
    classes = re.findall(r"^\s*(\w+Api)\.NAME(?: to \1)?,$", source, re.MULTILINE)

    apis_dir = os.path.join(REPO, "android-core/src/main/kotlin/com/vyzorix/bridge/apis")
    names = []
    for cls in classes:
        path = os.path.join(apis_dir, cls + ".kt")
        if not os.path.exists(path):
            print(f"sync-apis-txt: {cls} is in the catalogue but {path} does not exist",
                  file=sys.stderr)

            return 1

        declared = re.search(r'const val NAME = "(\w+)"', open(path).read())
        if not declared:
            print(f"sync-apis-txt: {cls} has no `const val NAME`", file=sys.stderr)

            return 1

        names.append(declared.group(1))

    # `session` is registered by the service, not in the map, because its handler
    # needs the service's session table. It is still a real command.
    session = re.search(r'SESSION_API_NAME\s*=\s*"(\w+)"', source)
    if session:
        names.append(session.group(1))

    if len(names) < 10:
        print(
            f"sync-apis-txt: only found {len(names)} API names in {CATALOGUE}.\n"
            "  The parse has probably gone stale — check the `handlers` map's shape\n"
            "  before rewriting apis.txt, or this will delete the container's commands.",
            file=sys.stderr,
        )

        return 1

    unique = sorted(set(names))

    body = "\n".join(unique) + "\n"
    content = HEADER + "\n" + body

    previous = open(APIS_TXT).read() if os.path.exists(APIS_TXT) else ""
    if previous == content:
        print(f"sync-apis-txt: apis.txt is up to date ({len(unique)} APIs)")

        return 0

    open(APIS_TXT, "w").write(content)
    print(f"sync-apis-txt: wrote {len(unique)} APIs to {APIS_TXT}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
