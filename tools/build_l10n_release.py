#!/usr/bin/env python3
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
import shutil
import sys
import zipfile
from pathlib import Path


MESSAGE_FILE_PATTERN = re.compile(r"^messages\.(?P<locale>[A-Za-z0-9-]+)\.ya?ml$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build locale zip assets and manifest.json for GitHub Release."
    )
    parser.add_argument("--repo", required=True, help="GitHub repository in owner/name format.")
    parser.add_argument("--tag", required=True, help="Release tag used for package URL.")
    parser.add_argument("--version", required=False, help="Manifest version label.")
    parser.add_argument(
        "--default-locale",
        default="zh-CN",
        help="Default locale value written into manifest.",
    )
    parser.add_argument(
        "--output-dir",
        default="dist/l10n",
        help="Output directory for manifest and locale zip assets.",
    )
    parser.add_argument(
        "--metadata-file",
        default="tools/locale-metadata.json",
        help="Locale metadata mapping file.",
    )
    parser.add_argument(
        "--web-i18n-dir",
        default="",
        help="Frontend src/i18n directory. Defaults to the CI checkout or sibling frontend repository.",
    )
    return parser.parse_args()


def load_locale_metadata(path: Path) -> dict[str, dict[str, str]]:
    if not path.is_file():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:  # pragma: no cover - defensive
        raise RuntimeError(f"Invalid locale metadata JSON: {path}") from exc
    result: dict[str, dict[str, str]] = {}
    if not isinstance(data, dict):
        return result
    for locale, payload in data.items():
        if not isinstance(locale, str) or not isinstance(payload, dict):
            continue
        name = str(payload.get("name", "")).strip()
        native_name = str(payload.get("nativeName", "")).strip()
        result[locale] = {"name": name, "nativeName": native_name}
    return result


def canonicalize_locale(raw: str) -> str:
    text = str(raw or "").strip().replace("_", "-")
    if not text:
        return ""
    parts = [part for part in text.split("-") if part]
    if not parts:
        return ""
    language = parts[0].lower()
    if len(parts) == 1:
        return language
    region = parts[1].upper() if len(parts[1]) == 2 else parts[1].lower()
    if len(parts) == 2:
        return f"{language}-{region}"
    tail = "-".join(part.lower() for part in parts[2:])
    return f"{language}-{region}-{tail}"


def discover_message_files(messages_dir: Path) -> dict[str, Path]:
    result: dict[str, Path] = {}
    if not messages_dir.is_dir():
        return result
    for path in sorted(messages_dir.glob("messages.*.yml")):
        match = MESSAGE_FILE_PATTERN.match(path.name)
        if not match:
            continue
        locale = canonicalize_locale(match.group("locale"))
        if not locale:
            continue
        result[locale] = path
    for path in sorted(messages_dir.glob("messages.*.yaml")):
        match = MESSAGE_FILE_PATTERN.match(path.name)
        if not match:
            continue
        locale = canonicalize_locale(match.group("locale"))
        if not locale:
            continue
        result[locale] = path
    return result


def discover_web_i18n_files(web_i18n_dir: Path) -> dict[str, dict[str, Path]]:
    result: dict[str, dict[str, Path]] = {}
    if not web_i18n_dir.is_dir():
        return result
    for namespace_dir in sorted(web_i18n_dir.iterdir()):
        if not namespace_dir.is_dir():
            continue
        namespace = namespace_dir.name
        for path in sorted(namespace_dir.glob("*.json")):
            locale = canonicalize_locale(path.stem)
            if not locale:
                continue
            entry = result.setdefault(locale, {})
            entry[namespace] = path
    return result


def sha256_of_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_locale_zip(
    locale: str,
    message_path: Path | None,
    web_files: dict[str, Path],
    stage_root: Path,
    zip_path: Path,
) -> tuple[bool, list[str]]:
    locale_root = stage_root / locale
    if locale_root.exists():
        shutil.rmtree(locale_root)
    locale_root.mkdir(parents=True, exist_ok=True)

    included_namespaces: list[str] = []
    if message_path and message_path.is_file():
        message_dest = locale_root / "messages" / f"messages.{locale}.yml"
        message_dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(message_path, message_dest)

    for namespace, source in sorted(web_files.items()):
        if not source.is_file():
            continue
        namespace_dest = locale_root / "web" / "i18n" / namespace / f"{locale}.json"
        namespace_dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, namespace_dest)
        included_namespaces.append(namespace)

    has_files = any(locale_root.rglob("*"))
    if not has_files:
        return False, []

    zip_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for path in sorted(locale_root.rglob("*")):
            if path.is_dir():
                continue
            zf.write(path, path.relative_to(locale_root).as_posix())
    return True, included_namespaces


def main() -> int:
    args = parse_args()
    repo_root = Path(__file__).resolve().parents[1]
    output_dir = (repo_root / args.output_dir).resolve()
    stage_dir = output_dir / "_stage"

    messages_dir = repo_root / "src" / "main" / "resources" / "messages"
    if args.web_i18n_dir:
        web_i18n_dir = Path(args.web_i18n_dir).resolve()
    else:
        ci_frontend = repo_root / ".frontend" / "webshopx-web" / "src" / "i18n"
        sibling_frontend = repo_root.parents[1] / "webshopx-web" / "src" / "i18n"
        web_i18n_dir = ci_frontend if ci_frontend.is_dir() else sibling_frontend
    metadata_file = (repo_root / args.metadata_file).resolve()
    metadata = load_locale_metadata(metadata_file)

    message_files = discover_message_files(messages_dir)
    web_files = discover_web_i18n_files(web_i18n_dir)
    locales = sorted(set(message_files.keys()) | set(web_files.keys()))

    if output_dir.exists():
        shutil.rmtree(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    stage_dir.mkdir(parents=True, exist_ok=True)

    now = dt.datetime.now(dt.timezone.utc)
    version = (args.version or "").strip() or f"l10n-{now.strftime('%Y.%m.%d-%H%M%S')}"
    base_url = f"https://github.com/{args.repo}/releases/download/{args.tag}"

    manifest_locales: list[dict[str, object]] = []
    for locale in locales:
        zip_file = output_dir / f"{locale}.zip"
        has_bundle, namespaces = build_locale_zip(
            locale=locale,
            message_path=message_files.get(locale),
            web_files=web_files.get(locale, {}),
            stage_root=stage_dir,
            zip_path=zip_file,
        )
        if not has_bundle:
            continue

        locale_meta = metadata.get(locale, {})
        name = str(locale_meta.get("name", "")).strip() or locale
        native_name = str(locale_meta.get("nativeName", "")).strip() or name
        manifest_locales.append(
            {
                "locale": locale,
                "name": name,
                "nativeName": native_name,
                "version": version,
                "packageUrl": f"{base_url}/{locale}.zip",
                "sha256": sha256_of_file(zip_file),
                "size": zip_file.stat().st_size,
                "messages": locale in message_files,
                "webNamespaces": namespaces,
            }
        )

    manifest = {
        "schemaVersion": 1,
        "version": version,
        "generatedAt": now.replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "defaultLocale": canonicalize_locale(args.default_locale) or "zh-CN",
        "locales": manifest_locales,
    }

    manifest_path = output_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")

    shutil.rmtree(stage_dir, ignore_errors=True)

    print(f"Generated manifest: {manifest_path}")
    print(f"Locale packages: {len(manifest_locales)}")
    for entry in manifest_locales:
        print(f" - {entry['locale']}: {entry['packageUrl']}")

    if not manifest_locales:
        print("No locale package was generated.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
