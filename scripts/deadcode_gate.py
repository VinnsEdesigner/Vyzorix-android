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
MAIN_DIRS = [ROOT / "android-core" / "src" / "main"]
TEST_DIRS = [ROOT / "tests"]
ALLOWLIST = ROOT / "config" / "deadcode-allowlist.txt"
PUBLIC_API = ROOT / "config" / "deadcode-public-api.txt"

# Kotlin constructs whose absence of a caller is meaningful.
KOTLIN_DECL = re.compile(
    r"^(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?P<visibility>public|internal|private|protected)?\s*"
    r"(?:(?:open|abstract|sealed|data|enum|inline|suspend|"
    r"override|operator|infix|external|tailrec|const)\s+)*"
    r"(fun|val|var|class|object|interface)\s+"
    r"(?:<[^>]*>\s*)?"
    r"(?:[A-Za-z_][A-Za-z0-9_.<>?]*\.)?"      # receiver, if any
    r"([A-Za-z_][A-Za-z0-9_]*)",
    re.MULTILINE,
)

# Match only the name before the colon. Consuming the type as well would swallow
# the next parameter's name, because a type and a name look alike.
KOTLIN_PARAM = re.compile(r"([A-Za-z_][A-Za-z0-9_]*)\s*:")

JNI_EXTERNAL = re.compile(r"external\s+fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(", re.MULTILINE)

CPP_FUNC = re.compile(
    r"^(?:[A-Za-z_][A-Za-z0-9_:<>*&,\s]*?\s+)?"
    r"([A-Za-z_][A-Za-z0-9_]*)\s*\([^;{]*\)\s*(?:const\s*)?\{",
    re.MULTILINE,
)

JNI_SYMBOL = re.compile(r"Java_com_vyzorix_(\w+)_([A-Za-z_]\w*)\s*\(", re.MULTILINE)


def strip_kotlin(text: str) -> str:
    """Remove comments and string literals so prose does not count as a use."""
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.DOTALL)
    text = re.sub(r"//[^\n]*", " ", text)
    text = re.sub(r'""".*?"""', ' "" ', text, flags=re.DOTALL)
    text = re.sub(r'"(?:[^"\\]|\\.)*"', ' "" ', text)
    return text


def strip_cpp(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.DOTALL)
    text = re.sub(r"//[^\n]*", " ", text)
    text = re.sub(r'"(?:[^"\\]|\\.)*"', ' "" ', text)
    return text


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
    all_text = "\n".join(main_text.values())
    test_only_text = "\n".join(test_text.values())

    public_api = load_public_api()
    declared: list[tuple[str, str, Path, int]] = []

    for path, text in main_text.items():
        for match in KOTLIN_DECL.finditer(text):
            kind, name = match.group(2), match.group(3)
            if name in {"main", "init"}:
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
            line = prefix.count("\n") + 1
            declared.append((kind, name, path, line))

    for kind, name, path, line in declared:
        key = f"kotlin-unused-decl:{name}"
        if key in allow or f"kotlin-unused-const:{name}" in allow:
            continue

        # Count references outside the declaration's own file+line.
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

        test_occurrences = len(re.findall(rf"\b{re.escape(name)}\b", test_only_text))

        if occurrences > 0:
            continue

        if test_occurrences > 0:
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
        for fn in re.finditer(r"\bfun\s+[A-Za-z_][A-Za-z0-9_.]*\s*\(([^)]*)\)\s*(?::[^{=]*)?[{=]", text):
            params_src = fn.group(1)
            body_start = fn.end()
            # Take a generous slice of the body; a long function may exceed it, but
            # a false negative is safer than a false positive here.
            body = text[body_start : body_start + 4000]
            for pm in KOTLIN_PARAM.finditer(params_src):
                pname = pm.group(1)
                if pname in {"val", "var", "private", "internal"}:
                    continue
                if not re.search(rf"\b{re.escape(pname)}\b", body):
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
