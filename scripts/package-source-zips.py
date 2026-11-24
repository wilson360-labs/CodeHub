#!/usr/bin/env python3
"""Create separate, tracked-source ZIPs for CodeHub Android and web releases."""

from __future__ import annotations

import os
import subprocess
import zipfile
from pathlib import Path


def tracked_files() -> list[Path]:
    output = subprocess.check_output(["git", "ls-files", "-z"])
    return [Path(item.decode("utf-8")) for item in output.split(b"\0") if item]


def safe_source(path: Path) -> bool:
    if path.is_symlink():
        return False
    if any(part in {".git", ".gradle", "build", "node_modules", "vendor", "test-results"}
           for part in path.parts):
        return False
    if path.name.lower().endswith((".jks", ".keystore", ".p12", ".pfx", ".pem", ".key", ".enc")):
        return False
    if path.name.startswith(".env") and path.name != ".env.example":
        return False
    return path.name != "local.properties"


def write_archive(destination: Path, prefix: str, paths: list[Path], info: str) -> int:
    count = 0
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        archive.writestr(f"{prefix}/SOURCE-INFO.txt", info)
        for path in paths:
            if safe_source(path) and path.is_file():
                archive.write(path, f"{prefix}/{path.as_posix()}")
                count += 1
    if count == 0:
        raise RuntimeError(f"No source files found for {destination.name}")
    print(f"{destination.name}: {count} files, {destination.stat().st_size} bytes")
    return count


def main() -> None:
    tag = os.environ["RELEASE_TAG"]
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    short_sha = commit[:7]
    files = tracked_files()
    out_dir = Path("release-source-zips")
    out_dir.mkdir(parents=True, exist_ok=True)

    android_files = [path for path in files if path.parts[:2] == ("apk", "android")]
    excluded_web_roots = {".github", ".opencode", "apk", "vendor", "node_modules", "test-results"}
    web_files = [
        path for path in files
        if path.parts
        and path.parts[0] not in excluded_web_roots
        and path.as_posix() != "scripts/package-source-zips.py"
    ]

    base_info = f"Release: {tag}\nCommit: {commit}\n"
    android_zip = out_dir / f"CodeHub-Android-source-{short_sha}.zip"
    web_zip = out_dir / f"CodeHub-Web-source-{short_sha}.zip"
    write_archive(
        android_zip,
        f"CodeHub-Android-{short_sha}",
        android_files,
        base_info + "Contents: native Android project and included Android modules under apk/android.\n",
    )
    write_archive(
        web_zip,
        f"CodeHub-Web-{short_sha}",
        web_files,
        base_info + "Contents: CodeHub web frontend and backend; Android project and CI metadata are excluded.\n",
    )

    output_path = os.environ.get("GITHUB_OUTPUT")
    if output_path:
        with open(output_path, "a", encoding="utf-8") as output:
            output.write(f"android_zip={android_zip.as_posix()}\n")
            output.write(f"web_zip={web_zip.as_posix()}\n")


if __name__ == "__main__":
    main()
