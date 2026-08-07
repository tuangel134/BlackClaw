#!/usr/bin/env python3
"""Build BlackClaw's fixed, offline Linux terminal bootstrap.

The generated archives are deliberately not a package repository: they contain a
closed list of Alpine packages and their runtime dependencies. They run below the
Android app sandbox through PRoot, so no root, Shizuku or ADB permission is needed.

Run this only from a trusted release environment. It downloads upstream Alpine
artifacts, writes per-ABI rootfs archives and prints SHA-256 values to commit into
the asset manifest together with the PRoot archives built separately.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import os
import re
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request
from collections import deque
from pathlib import Path


ALPINE = "v3.21"
ROOTFS_VERSION = "3.21.7"
ARCHES = ("aarch64", "x86_64")
PACKAGES = (
    "bash", "ca-certificates", "coreutils", "curl", "diffutils", "findutils",
    "gawk", "git", "grep", "jq", "less", "openssh-client", "python3", "sed",
    "tar", "unzip", "zip",
)
MIRROR = "https://dl-cdn.alpinelinux.org/alpine"


def fetch(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "BlackClaw-bootstrap-builder/1"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_index(raw: bytes) -> dict[str, dict[str, str]]:
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:gz") as archive:
        index = archive.extractfile("APKINDEX")
        if index is None:
            raise RuntimeError("APKINDEX missing from Alpine index archive")
        content = index.read().decode("utf-8")
    result: dict[str, dict[str, str]] = {}
    for block in content.split("\n\n"):
        fields = dict(line.split(":", 1) for line in block.splitlines() if ":" in line)
        if fields.get("P") and fields.get("V"):
            result[fields["P"]] = fields
    return result


def dependency_name(token: str) -> str | None:
    token = token.strip()
    if not token or token.startswith("!"):
        return None
    # Alpine dependency strings can carry a version constraint or a soname marker.
    token = re.split(r"[<>=~]", token, maxsplit=1)[0]
    return token or None


def resolve(index: dict[str, dict[str, str]], requested: tuple[str, ...]) -> list[dict[str, str]]:
    providers: dict[str, str] = {}
    for name, package in index.items():
        providers[name] = name
        for provided in package.get("p", "").split():
            provided_name = dependency_name(provided)
            if provided_name:
                providers.setdefault(provided_name, name)

    selected: dict[str, dict[str, str]] = {}
    pending = deque(requested)
    while pending:
        requested_name = dependency_name(pending.popleft())
        if requested_name is None:
            continue
        name = providers.get(requested_name)
        if name is None:
            raise RuntimeError(f"Cannot resolve Alpine dependency: {requested_name}")
        if name in selected:
            continue
        package = index[name]
        selected[name] = package
        pending.extend(package.get("D", "").split())
    return [selected[name] for name in sorted(selected)]


def extract_tar_gz(raw: bytes, dest: Path) -> None:
    """Extract trusted Alpine archives without inheriting archive ownership."""
    with tempfile.NamedTemporaryFile(prefix="blackclaw-archive-", suffix=".tar.gz") as source:
        source.write(raw)
        source.flush()
        subprocess.run(
            ["tar", "--warning=no-unknown-keyword", "--no-same-owner", "--delay-directory-restore", "-xzf", source.name, "-C", str(dest)],
            check=True,
        )


def build_arch(arch: str, output: Path) -> Path:
    release = f"{MIRROR}/{ALPINE}/releases/{arch}/alpine-minirootfs-{ROOTFS_VERSION}-{arch}.tar.gz"
    index_url = f"{MIRROR}/{ALPINE}/main/{arch}/APKINDEX.tar.gz"
    with tempfile.TemporaryDirectory(prefix=f"blackclaw-rootfs-{arch}-") as temp_dir:
        temp = Path(temp_dir)
        root = temp / "rootfs"
        root.mkdir()
        extract_tar_gz(fetch(release), root)

        index = parse_index(fetch(index_url))
        for package in resolve(index, PACKAGES):
            package_url = f"{MIRROR}/{ALPINE}/main/{arch}/{package['P']}-{package['V']}.apk"
            extract_tar_gz(fetch(package_url), root)

        # Fixed means no in-terminal package installation. Keep the actual userland
        # but remove apk metadata, repository URLs and the package client.
        shutil.rmtree(root / "var/cache/apk", ignore_errors=True)
        shutil.rmtree(root / "var/lib/apk", ignore_errors=True)
        (root / "etc/apk/repositories").unlink(missing_ok=True)
        (root / "sbin/apk").unlink(missing_ok=True)

        home = root / "home/blackclaw"
        home.mkdir(parents=True, exist_ok=True)
        (root / "etc/profile.d").mkdir(parents=True, exist_ok=True)
        (root / "etc/profile.d/blackclaw.sh").write_text(
            "export HOME=/home/blackclaw\nexport PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n",
            encoding="utf-8",
        )
        # PRoot maps the terminal to this unprivileged identity.  It is a virtual
        # uid only; Android's sandbox remains the real security boundary.
        (root / "etc/passwd").write_text(
            "root:x:0:0:root:/root:/bin/sh\nblackclaw:x:1000:1000:BlackClaw:/home/blackclaw:/bin/bash\n",
            encoding="utf-8",
        )
        (root / "etc/group").write_text("root:x:0:\nblackclaw:x:1000:\n", encoding="utf-8")

        destination = output / arch / "rootfs.tar.gz"
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tarfile.open(destination, mode="w:gz", format=tarfile.PAX_FORMAT) as archive:
            archive.add(root, arcname="root", recursive=True)
        return destination


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("app/src/main/assets/terminal"))
    args = parser.parse_args()
    for arch in ARCHES:
        artifact = build_arch(arch, args.output)
        print(f"{artifact}: {sha256(artifact)}")


if __name__ == "__main__":
    main()
