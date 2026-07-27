#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / "src" / "main" / "java"
CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")

# Java source should stay locale-neutral. Native-language data belongs in locale resources,
# not in implementation classes. Add narrowly scoped exceptions here only when a literal is
# a protocol/data identifier rather than UI copy.
ALLOWLIST: set[tuple[str, int]] = set()


def main() -> int:
    findings: list[str] = []
    for path in sorted(JAVA_ROOT.rglob("*.java")):
        relative = path.relative_to(ROOT).as_posix()
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if not CJK.search(line):
                continue
            if (relative, line_number) in ALLOWLIST:
                continue
            findings.append(f"{relative}:{line_number}: {line.strip()}")

    if findings:
        print("Hardcoded CJK text remains in Java source:", file=sys.stderr)
        for finding in findings:
            print(f"  {finding}", file=sys.stderr)
        return 1

    print("Java i18n audit passed: no hardcoded CJK text found.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
