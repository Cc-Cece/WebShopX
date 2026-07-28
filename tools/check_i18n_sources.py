#!/usr/bin/env python3
"""Fail when Java production sources reintroduce unapproved Han literals."""

from __future__ import annotations

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE_ROOT = ROOT / "src" / "main" / "java"
ALLOWLIST = json.loads(
    (ROOT / "tools" / "i18n-source-allowlist.json").read_text(encoding="utf-8")
)
STRING = re.compile(r'"(?:\\.|[^"\\])*"')
HAN = re.compile(r"[\u3400-\u9fff]")


def mask_comments(source: str) -> str:
    output: list[str] = []
    index = 0
    state = "code"
    while index < len(source):
        pair = source[index : index + 2]
        char = source[index]
        if state == "code" and pair == "//":
            output.extend("  ")
            index += 2
            state = "line-comment"
        elif state == "code" and pair == "/*":
            output.extend("  ")
            index += 2
            state = "block-comment"
        elif state == "line-comment" and char in "\r\n":
            output.append(char)
            index += 1
            state = "code"
        elif state == "block-comment" and pair == "*/":
            output.extend("  ")
            index += 2
            state = "code"
        elif state in {"line-comment", "block-comment"}:
            output.append(char if char in "\r\n" else " ")
            index += 1
        elif state == "code" and char == '"':
            start = index
            index += 1
            while index < len(source):
                if source[index] == "\\":
                    index += 2
                elif source[index] == '"':
                    index += 1
                    break
                else:
                    index += 1
            output.extend(source[start:index])
        else:
            output.append(char)
            index += 1
    return "".join(output)


def allowed(path: str, text: str) -> bool:
    return any(
        item["rule"] == "backend-han-literal"
        and item.get("path") == path
        and item.get("text") == text
        for item in ALLOWLIST
    )


findings: list[tuple[str, int, int, str]] = []
for source_file in sorted(SOURCE_ROOT.rglob("*.java")):
    relative = source_file.relative_to(ROOT).as_posix()
    source = source_file.read_text(encoding="utf-8")
    masked = mask_comments(source)
    for match in STRING.finditer(masked):
        literal = match.group()[1:-1]
        if not HAN.search(literal) or allowed(relative, literal):
            continue
        before = source[: match.start()]
        line = before.count("\n") + 1
        column = match.start() - before.rfind("\n")
        findings.append((relative, line, column, literal))

for path, line, column, text in findings:
    print(
        f"backend-han-literal {path}:{line}:{column} "
        f"{json.dumps(text, ensure_ascii=False)}",
        file=sys.stderr,
    )
print(f"Backend source i18n scan: {len(findings)} finding(s).")
raise SystemExit(1 if findings else 0)
