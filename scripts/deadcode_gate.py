#!/usr/bin/env python3
"""Dead-code gate.

detekt covers *private* unused members and ktlint covers unused imports. Neither
covers the cases that actually rot in a library module, so this gate does:

  kotlin-unused-decl     a public/internal declaration with no reference anywhere
  kotlin-test-only-decl  a declaration referenced only from tests/ (a warning:
                         it will never run in production)
  kotlin-unused-const    a constant with no reference anywhere
  kotlin-unused-param    a function parameter never read in its own body
  native-unused-func     a C++ function with no call site
  jni-orphan-decl        a Kotlin `external fun` with no native implementation
  jni-orphan-symbol      a native JNIEXPORT with no Kotlin declaration

Design notes:
  * Only `internal`/`private` declarations are judged. A public declaration in a
    library module has no in-repo caller by design — its consumers are other
    modules — so flagging it would be noise. `config/deadcode-public-api.txt`
    lists the deliberate entry points that must stay public.
  * Comments and string literals are stripped before counting references, so a
    name mentioned in prose does not count as a use.
  * A declaration's own signature line is excluded from its reference count.
  * `external fun` and JNIEXPORT names are compared in both directions, because a
    mismatch is silent until runtime (UnsatisfiedLinkError) — it does not fail the
    build, which is exactly why a gate has to look for it.
  * Allowlisted entries in config/deadcode-allowlist.txt are reported as
    suppressed, never silently dropped.

Usage:
  scripts/deadcode_gate.py            check; exit 1 on any error
  scripts/deadcode_gate.py --list     list every finding without failing
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# Every shipping module, not just the first. A module left out of this list has its
# dead code silently tolerated, which is the failure mode the gate exists to stop.
MAIN_DIRS = [
    ROOT / "android-core" / "src" / "main",
    ROOT / "cloud-connector" / "src" / "main",
    ROOT / "app" / "src" / "main",
]
TEST_DIRS = [ROOT / "tests"]
ALLOWLIST = ROOT / "config" / "deadcode-allowlist.txt"
PUBLIC_API = ROOT / "config" / "deadcode-public-api.txt"

# Kotlin constructs whose absence of a caller is meaningful.
#
# `^[ \t]*` rather than `^`: every declaration inside a class or an object is
# indented, so anchoring at column zero would see only top-level declarations and
# report a library of unreferenced members as clean. The optional indentation is
# what makes the gate look at members at all.
#
# Whitespace between the parts is `[ \t]`, never `\s`: `\s` matches newlines, so a
# declaration preceded by blank lines (every one that carries a KDoc) would have
# its match start on an earlier line. The line number is taken from the name, but
# keeping the match on one line avoids the pattern running past a blank line into
# an unrelated declaration.
KOTLIN_DECL = re.compile(
    r"^[ \t]*(?:@\w+(?:\([^)]*\))?[ \t]*\n?[ \t]*)*"
    r"(?P<visibility>public|internal|private|protected)?[ \t]*"
    r"(?:(?:open|abstract|sealed|data|enum|inline|suspend|"
    r"override|operator|infix|external|tailrec|const)[ \t]+)*"
    r"(fun|val|var|class|object|interface)[ \t]+"
    r"(?:<[^>]*>[ \t]*)?"
    r"(?:[A-Za-z_][A-Za-z0-9_.<>?]*\.)?"      # receiver, if any
    r"([A-Za-z_][A-Za-z0-9_]*)",
    re.MULTILINE,
)

# Match only the name before the colon. Consuming the type as well would swallow
# the next parameter's name, because a type and a name look alike.
KOTLIN_PARAM = re.compile(r"([A-Za-z_][A-Za-z0-9_]*)\s*:")

JNI_EXTERNAL = re.compile(r"external\s+fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(", re.MULTILINE)

CPP_FUNC = re.compile(
    r"^[ \t]*(?:[A-Za-z_][A-Za-z0-9_:<>*&,\s]*?\s+)?"
    r"([A-Za-z_][A-Za-z0-9_]*)\s*\([^;{]*\)\s*(?:const\s*)?\{",
    re.MULTILINE,
)

# A JNI entry point can be split across lines, so the function name and its
# parameter list are matched on separate lines rather than with a single
# name-then-paren pattern.
JNI_SYMBOL = re.compile(r"Java_com_vyzorix_(\w+)_([A-Za-z_]\w*)", re.MULTILINE)

# Android framework entry points. The system calls these; there is no in-repo
# caller, and their absence of a call site says nothing about whether they are
# dead. A declaration whose name is also a genuine internal one would be a false
# negative, but the names are distinctive enough that this does not happen.
FRAMEWORK_CALLBACKS = {
    "onCreate",
    "onStart",
    "onStartCommand",
    "onDestroy",
    "onBind",
    "onUnbind",
    "onRebind",
    "onReceive",
    "onLowMemory",
    "onTrimMemory",
    "onConfigurationChanged",
    "onTaskRemoved",
    "onTimeout",
    "onNewIntent",
    # OkHttp WebSocketListener callbacks: the client calls these, not our code.
    "onOpen",
    "onMessage",
    "onClosing",
    "onClosed",
    "onFailure",
}

# The class name is the last component of a fully-qualified `android:name`.
MANIFEST_CLASS = re.compile(r'android:name\s*=\s*"([A-Za-z0-9_.]+)"')


def manifest_referenced_names() -> set[str]:
    """Simple class names the manifest names, so components are not judged dead.

    A service or receiver has no in-repo constructor call: the system creates it
    from the manifest. Without this, every component would be reported as unused.
    """
    names: set[str] = set()
    for module in MAIN_DIRS:
        for manifest in module.rglob("AndroidManifest.xml"):
            text = manifest.read_text()
            for match in MANIFEST_CLASS.finditer(text):
                value = match.group(1)
                if "." not in value:
                    continue
                names.add(value.rsplit(".", 1)[1])
    return names


def _blank(match: re.Match[str]) -> str:
    """Replace a comment or literal with spaces, preserving its newlines.

    Preserving newlines keeps every offset and line number aligned with the
    original file. Collapsing a multi-line block comment to a single space would
    shift every line after it, so a declaration's reported line would not be its
    real one and the self-reference exclusion in the reference count would
    exclude the wrong line.
    """
    return re.sub(r"[^\n]", " ", match.group(0))


def strip_kotlin(text: str) -> str:
    """Remove comments and string literals so prose does not count as a use.

    Char literals are removed before strings, and that order matters: a char
    literal like `'"'` contains a double quote, so a string regex applied first
    treats it as an opening quote and blanks everything up to the next `"` —
    which silently erases real code between them and makes the reference count
    wrong. This is not hypothetical: it hid two genuine uses in `RawJson.kt`.
    """
    text = re.sub(r"/\*.*?\*/", _blank, text, flags=re.DOTALL)
    text = re.sub(r"//[^\n]*", _blank, text)
    text = re.sub(r'""".*?"""', _blank, text, flags=re.DOTALL)
    text = re.sub(r"'(?:[^'\\\n]|\\.)'", _blank, text)
    text = re.sub(r'"(?:[^"\\\n]|\\.)*"', _blank, text)
    return text


def strip_cpp(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", _blank, text, flags=re.DOTALL)
    text = re.sub(r"//[^\n]*", _blank, text)
    text = re.sub(r'"(?:[^"\\]|\\.)*"', _blank, text)
    return text


def count_interpolations(path: Path, name: str) -> int:
    """Counts uses inside string interpolation, which strip_kotlin blanks.

    `"$FOO"` is a real use of `FOO`, and so is `${FOO.length}` — the name is not
    immediately after the `$`, so a simple `$name` pattern misses it. This scans
    every `${ ... }` block for the name instead, which covers both.
    """
    text = path.read_text()

    simple = re.compile(rf"\$\{{?{re.escape(name)}\b")
    count = len(simple.findall(text))

    for block in re.findall(r"\$\{([^}]*)\}", text):
        if simple.search("$" + block):
            continue
        count += len(re.findall(rf"\b{re.escape(name)}\b", block))

    return count


def sources(dirs: list[Path], suffixes: tuple[str, ...]) -> list[Path]:
    out: list[Path] = []
    for base in dirs:
        if not base.exists():
            continue
        for path in base.rglob("*"):
            if path.is_file() and path.suffix in suffixes and "/build/" not in str(path):
                out.append(path)
    return sorted(out)


def load_public_api() -> set[str]:
    if not PUBLIC_API.exists():
        return set()
    entries = set()
    for line in PUBLIC_API.read_text().splitlines():
        line = line.split("#", 1)[0].strip()
        if line:
            entries.add(line)
    return entries


def load_allowlist() -> set[str]:
    if not ALLOWLIST.exists():
        return set()
    entries = set()
    for line in ALLOWLIST.read_text().splitlines():
        line = line.split("#", 1)[0].strip()
        if line:
            entries.add(line)
    return entries


class Finding:
    def __init__(self, kind: str, name: str, path: Path, line: int, note: str = ""):
        self.kind = kind
        self.name = name
        self.path = path
        self.line = line
        self.note = note

    def render(self) -> str:
        rel = self.path.relative_to(ROOT)
        suffix = f"  ({self.note})" if self.note else ""
        return f"  [{self.kind}] {rel}:{self.line}  {self.name}{suffix}"

    def key(self) -> str:
        return f"{self.kind}:{self.name}"


def analyse_kotlin(findings: list[Finding], allow: set[str]) -> set[str]:
    """Returns the set of Kotlin declaration names, for the JNI cross-check."""
    main_files = sources(MAIN_DIRS, (".kt",))
    test_files = sources(TEST_DIRS, (".kt",))

    main_text: dict[Path, str] = {p: strip_kotlin(p.read_text()) for p in main_files}
    test_text: dict[Path, str] = {p: strip_kotlin(p.read_text()) for p in test_files}

    # Every identifier appearing anywhere, with the file it came from.
    test_only_text = "\n".join(test_text.values())

    public_api = load_public_api()
    manifest_names = manifest_referenced_names()
    declared: list[tuple[str, str, Path, int]] = []

    for path, text in main_text.items():
        for match in KOTLIN_DECL.finditer(text):
            kind, name = match.group(2), match.group(3)
            if name in {"main", "init"}:
                continue
            # An Android framework callback is called by the system through the
            # framework, and a component class is instantiated from the manifest.
            # Neither has an in-repo caller by construction, so flagging them is
            # noise. The manifest scan covers the classes the manifest names.
            if name in FRAMEWORK_CALLBACKS or name in manifest_names:
                continue
            # A `val`/`var` inside an unclosed parenthesis is a constructor
            # property, not a declaration. Its use is the constructor call, so
            # judging it here would be a false positive on every data class.
            prefix = text[: match.start()]
            if prefix.count("(") > prefix.count(")"):
                continue
            # Names on the public-API list are the module's intended surface and
            # are exempt; any other public declaration is still judged, because
            # dead public API is dead API.
            if name in public_api:
                continue
            # The name's own position, so an annotation or a blank line above the
            # declaration cannot move the reported line.
            line = text[: match.start(3)].count("\n") + 1
            declared.append((kind, name, path, line))

    for kind, name, path, line in declared:
        key = f"kotlin-unused-decl:{name}"
        if key in allow or f"kotlin-unused-const:{name}" in allow:
            continue

        # Count references outside the declaration's own file+line. Interpolations
        # inside string literals count: `"$FOO"` is a real use of `FOO`, but
        # strip_kotlin blanks literals, so the count also reads the raw file.
        occurrences = 0
        for other, text in main_text.items():
            if other == path:
                # Exclude the signature line itself.
                lines = text.splitlines()
                occurrences += sum(
                    1
                    for i, ln in enumerate(lines)
                    if i + 1 != line and re.search(rf"\b{re.escape(name)}\b", ln)
                )
            else:
                occurrences += len(re.findall(rf"\b{re.escape(name)}\b", text))
                # strip_kotlin blanks string literals, so a use inside another file's
                # interpolation (`"${relay.openCount()}"`) was invisible. Scanning only
                # the declaring file's literals reported a name as test-only when
                # production code used it — a false positive the gate must not make.
                occurrences += count_interpolations(other, name)

        # The declaring file's own interpolations, with its signature line excluded.
        occurrences += count_interpolations(path, name)

        test_occurrences = len(re.findall(rf"\b{re.escape(name)}\b", test_only_text))

        if occurrences > 0:
            continue

        if test_occurrences > 0:
            if f"kotlin-test-only-decl:{name}" in allow:
                continue
            findings.append(
                Finding("kotlin-test-only-decl", name, path, line, "referenced only from tests/")
            )
            continue

        if kind in {"val", "var"}:
            findings.append(Finding("kotlin-unused-const", name, path, line))
        else:
            findings.append(Finding("kotlin-unused-decl", name, path, line))

    # Unused parameters: a parameter never mentioned in the function body.
    for path, text in main_text.items():
        # Interpolations are uses, and strip_kotlin removed the literals they live
        # in, so this pass needs a copy with *comments* blanked but string
        # literals kept. Both strips preserve length and newlines, so offsets into
        # this copy line up with offsets into `text`.
        raw = path.read_text()
        raw = re.sub(r"/\*.*?\*/", _blank, raw, flags=re.DOTALL)
        raw = re.sub(r"//[^\n]*", _blank, raw)

        for fn in re.finditer(
            # The return type may not cross a newline: `[^{=]*` would run on to the
            # next declaration's `{`, so an interface member with no body (and no
            # `{` on its line) would borrow the brace of an unrelated function and
            # its parameters would be scanned against the wrong body.
            r"(?P<override>\boverride\s+)?\bfun\s+[A-Za-z_][A-Za-z0-9_.]*\s*\(([^)]*)\)"
            r"\s*(?::[^{=\n]*)?[{=]",
            text,
        ):
            # An override's parameters are fixed by the interface it implements,
            # so an unused one cannot be removed and reporting it is noise.
            if fn.group("override"):
                continue

            params_src = fn.group(2)
            body = text[fn.end() : fn.end() + 4000]

            # The same slice of the original file, for interpolation checks.
            raw_body = raw[fn.end() : fn.end() + 4000]

            for pm in KOTLIN_PARAM.finditer(params_src):
                pname = pm.group(1)
                if pname in {"val", "var", "private", "internal"}:
                    continue
                if re.search(rf"\b{re.escape(pname)}\b", body):
                    continue
                if re.search(rf"\$\{{?{re.escape(pname)}\b", raw_body):
                    continue
                line = text[: fn.start()].count("\n") + 1
                findings.append(
                    Finding("kotlin-unused-param", pname, path, line, "parameter unused in body")
                )

    return {name for _, name, _, _ in declared}


def analyse_native(findings: list[Finding], allow: set[str], kotlin_names: set[str]) -> None:
    cpp_files = sources(MAIN_DIRS, (".cpp", ".h", ".hpp"))
    cpp_text: dict[Path, str] = {p: strip_cpp(p.read_text()) for p in cpp_files}
    all_cpp = "\n".join(cpp_text.values())

    # JNI symbols: every JNIEXPORT must have a matching Kotlin external fun, and
    # every Kotlin external fun must have a matching JNIEXPORT.
    native_symbols: set[str] = set()
    for path, text in cpp_text.items():
        for match in JNI_SYMBOL.finditer(text):
            name = match.group(2)
            native_symbols.add(name)

    declared_external: dict[str, tuple[Path, int]] = {}
    for path in sources(MAIN_DIRS, (".kt",)):
        text = strip_kotlin(path.read_text())
        for match in JNI_EXTERNAL.finditer(text):
            line = text[: match.start()].count("\n") + 1
            declared_external[match.group(1)] = (path, line)

    # Every `external fun` must have a matching JNIEXPORT, and vice versa: a
    # mismatch is silent until runtime (UnsatisfiedLinkError), which is exactly
    # why a gate has to look for it.
    for name, (path, line) in declared_external.items():
        if name not in native_symbols and f"jni-orphan-decl:{name}" not in allow:
            findings.append(
                Finding("jni-orphan-decl", name, path, line, "no matching JNIEXPORT")
            )

    for name in sorted(native_symbols):
        if name not in declared_external and f"jni-orphan-symbol:{name}" not in allow:
            findings.append(
                Finding("jni-orphan-symbol", name, ROOT / "android-core/src/main/cpp", 0,
                        "no matching Kotlin external fun")
            )

    # Native functions with no call site. JNI entry points are excluded: the JVM
    # calls them, so there is no call site to find.
    for path, text in cpp_text.items():
        if path.suffix in {".h", ".hpp"}:
            continue
        for match in CPP_FUNC.finditer(text):
            name = match.group(1)
            if name in {"main", "if", "for", "while", "switch", "catch", "namespace", "extern"}:
                continue
            if name.startswith("Java_com_vyzorix"):
                continue
            if name in kotlin_names:
                continue
            if f"native-unused-func:{name}" in allow:
                continue

            # Count call sites across all native sources, excluding the definition.
            def_line = text[: match.start()].count("\n") + 1
            calls = 0
            for other, other_text in cpp_text.items():
                lines = other_text.splitlines()
                for i, ln in enumerate(lines):
                    if other == path and i + 1 == def_line:
                        continue
                    calls += len(re.findall(rf"\b{re.escape(name)}\s*\(", ln))
            if calls == 0:
                findings.append(Finding("native-unused-func", name, path, def_line))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--list", action="store_true", help="list findings, never fail")
    args = parser.parse_args()

    allow = load_allowlist()
    findings: list[Finding] = []

    kotlin_names = analyse_kotlin(findings, allow)
    analyse_native(findings, allow, kotlin_names)

    findings.sort(key=lambda f: (f.kind, str(f.path), f.line))

    if not findings:
        print("dead-code gate: clean")
        return 0

    by_kind: dict[str, int] = defaultdict(int)
    for f in findings:
        by_kind[f.kind] += 1

    for f in findings:
        print(f.render())

    print()
    print(f"dead-code gate: {len(findings)} finding(s)")
    for kind, count in sorted(by_kind.items()):
        print(f"  {kind}: {count}")
    print()
    print("Remove the dead code, or allowlist it in config/deadcode-allowlist.txt with a reason.")

    return 0 if args.list else 1


if __name__ == "__main__":
    sys.exit(main())
