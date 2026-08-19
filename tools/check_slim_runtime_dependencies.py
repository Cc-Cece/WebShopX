#!/usr/bin/env python3
"""Verify slim plugin variants cover every direct runtime dependency."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD_FILE = ROOT / "build.gradle"
SLIM_METADATA = [
    ROOT / "src/main/resources/plugin-slim.yml",
    ROOT / "src/main/resources/plugin-folia-slim.yml",
]

COORDINATE = r"[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+\-]+"
IMPLEMENTATION_RE = re.compile(
    rf"^\s*implementation(?:\s*\()?\s*['\"]({COORDINATE})['\"]",
    re.MULTILINE,
)
SLIM_EMBEDDED_RE = re.compile(
    rf"^\s*slimEmbedded(?:\s*\()?\s*['\"]({COORDINATE})['\"]",
    re.MULTILINE,
)
LIBRARY_RE = re.compile(rf"^\s*-\s+({COORDINATE})\s*$", re.MULTILINE)


def read_coordinates(path: Path) -> set[str]:
    return set(LIBRARY_RE.findall(path.read_text(encoding="utf-8")))


def main() -> int:
    build_text = BUILD_FILE.read_text(encoding="utf-8")
    runtime_dependencies = set(IMPLEMENTATION_RE.findall(build_text))
    embedded_dependencies = set(SLIM_EMBEDDED_RE.findall(build_text))

    errors: list[str] = []

    unexpected_embedded = embedded_dependencies - runtime_dependencies
    if unexpected_embedded:
        errors.append(
            "slimEmbedded contains dependencies that are not direct implementation dependencies: "
            + ", ".join(sorted(unexpected_embedded))
        )

    expected_external = runtime_dependencies - embedded_dependencies

    for metadata_path in SLIM_METADATA:
        declared_external = read_coordinates(metadata_path)
        missing = expected_external - declared_external
        stale = declared_external - expected_external

        if missing:
            errors.append(
                f"{metadata_path.relative_to(ROOT)} is missing runtime libraries: "
                + ", ".join(sorted(missing))
            )
        if stale:
            errors.append(
                f"{metadata_path.relative_to(ROOT)} declares stale/unexpected libraries: "
                + ", ".join(sorted(stale))
            )

    if errors:
        print("Slim runtime dependency verification failed:")
        for error in errors:
            print(f"  - {error}")
        return 1

    print(
        "Slim runtime dependency verification passed: "
        f"{len(runtime_dependencies)} direct runtime dependencies, "
        f"{len(expected_external)} externalized, {len(embedded_dependencies)} bundled."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
