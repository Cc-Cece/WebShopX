#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / "src" / "main" / "java"
CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")


def is_allowed_native_locale_metadata(relative: str, line: str) -> bool:
    """Allow native locale display names, but not general UI copy in Java source."""
    return (
        relative == "src/main/java/com/webshopx/LocaleCenterService.java"
        and 'return "简体中文";' in line
    )


def main() -> int:
    findings: list[str] = []
    for path in sorted(JAVA_ROOT.rglob("*.java")):
        relative = path.relative_to(ROOT).as_posix()
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if not CJK.search(line):
                continue
            if is_allowed_native_locale_metadata(relative, line):
                continue
            findings.append(f"{relative}:{line_number}: {line.strip()}")

    if findings:
        print("Hardcoded CJK text remains in Java source:", file=sys.stderr)
        for finding in findings:
            print(f"  {finding}", file=sys.stderr)
        return 1

    print("Java i18n audit passed: no hardcoded CJK text outside native locale metadata.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
