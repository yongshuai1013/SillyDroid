#!/usr/bin/env python3
from __future__ import annotations

import argparse
import copy
import os
import pathlib
import shutil
import stat
import subprocess
import sys
import tarfile
import time
import zipfile


def human_bytes(value: int) -> str:
    units = ["B", "KB", "MB", "GB", "TB"]
    size = float(value)
    unit_index = 0
    while size >= 1024.0 and unit_index < len(units) - 1:
        size /= 1024.0
        unit_index += 1
    if unit_index == 0:
        return f"{int(size)} {units[unit_index]}"
    return f"{size:.1f} {units[unit_index]}"


def strip_path(name: str, strip_components: int) -> str | None:
    normalized = pathlib.PurePosixPath(name)
    parts = [part for part in normalized.parts if part not in ("", ".")]
    if any(part == ".." for part in parts):
        raise ValueError(f"Unsafe archive entry: {name}")
    if strip_components:
        if len(parts) <= strip_components:
            return None
        parts = parts[strip_components:]
    if not parts:
        return None
    return "/".join(parts)


def render(label: str, current: int, total: int, current_bytes: int, total_bytes: int, tty: bool) -> None:
    percent = (current * 100.0 / total) if total > 0 else 100.0
    byte_segment = ""
    if total_bytes > 0:
        byte_segment = f" {human_bytes(current_bytes)}/{human_bytes(total_bytes)}"
    line = f"{label} 解压中 {percent:5.1f}% {current}/{total}{byte_segment}"

    if tty:
        width = 0
        try:
            width = os.get_terminal_size(sys.stderr.fileno()).columns - 1
        except OSError:
            width = 0
        if width > 0 and len(line) > width:
            line = line[: width - 1] + "…"
        sys.stderr.write("\x1b[2K\r" + line)
    else:
        sys.stderr.write(line + "\n")
    sys.stderr.flush()


def should_render(now: float, last_render: float, current: int, total: int, tty: bool) -> bool:
    interval = 0.1 if tty else 0.5
    return current == total or (now - last_render) >= interval


def ensure_parent(path: pathlib.Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)


def extract_zip(archive_path: pathlib.Path, destination: pathlib.Path, label: str, strip_components: int, tty: bool) -> None:
    with zipfile.ZipFile(archive_path) as archive:
        entries = []
        total_bytes = 0
        for info in archive.infolist():
            target_name = strip_path(info.filename, strip_components)
            if target_name is None:
                continue
            entries.append((info, target_name))
            if not info.is_dir():
                total_bytes += info.file_size

        extracted = 0
        extracted_bytes = 0
        last_render = 0.0
        for info, target_name in entries:
            target_path = destination / target_name
            mode = info.external_attr >> 16
            if info.is_dir() or stat.S_ISDIR(mode):
                target_path.mkdir(parents=True, exist_ok=True)
            elif stat.S_ISLNK(mode):
                ensure_parent(target_path)
                with archive.open(info) as source:
                    link_target = source.read().decode("utf-8")
                if target_path.exists() or target_path.is_symlink():
                    target_path.unlink()
                os.symlink(link_target, target_path)
            else:
                ensure_parent(target_path)
                with archive.open(info) as source, open(target_path, "wb") as handle:
                    shutil.copyfileobj(source, handle)
                permissions = mode & 0o777
                if permissions:
                    target_path.chmod(permissions)
                extracted_bytes += info.file_size
            extracted += 1
            now = time.monotonic()
            if should_render(now, last_render, extracted, len(entries), tty):
                render(label, extracted, len(entries), extracted_bytes, total_bytes, tty)
                last_render = now


def extract_tar(archive_path: pathlib.Path, destination: pathlib.Path, label: str, strip_components: int, tty: bool) -> None:
    member_names: list[str] = []
    total_bytes = 0

    try:
        with tarfile.open(archive_path) as archive:
            for member in archive.getmembers():
                target_name = strip_path(member.name, strip_components)
                if target_name is None:
                    continue
                member_names.append(member.name)
                if member.isfile():
                    total_bytes += member.size
    except tarfile.ReadError:
        result = subprocess.run(
            ["tar", "-tf", str(archive_path)],
            check=True,
            capture_output=True,
            text=True,
        )
        for raw_name in result.stdout.splitlines():
            if strip_path(raw_name, strip_components) is not None:
                member_names.append(raw_name)

    command = ["tar", "-xvf", str(archive_path), "-C", str(destination)]
    if strip_components:
        command.append(f"--strip-components={strip_components}")

    extracted = 0
    extracted_bytes = 0
    last_render = 0.0
    with subprocess.Popen(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    ) as process:
        assert process.stdout is not None
        for line in process.stdout:
            if not line.strip():
                continue
            extracted += 1
            now = time.monotonic()
            display_current = min(extracted, len(member_names)) if member_names else extracted
            if should_render(now, last_render, display_current, len(member_names) or display_current, tty):
                render(label, display_current, len(member_names) or display_current, extracted_bytes, total_bytes, tty)
                last_render = now
        return_code = process.wait()

    if return_code != 0:
        raise subprocess.CalledProcessError(return_code, command)

    if member_names:
        render(label, len(member_names), len(member_names), extracted_bytes, total_bytes, tty)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive", required=True)
    parser.add_argument("--destination", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--strip-components", type=int, default=0)
    args = parser.parse_args()

    archive_path = pathlib.Path(args.archive)
    destination = pathlib.Path(args.destination)
    destination.mkdir(parents=True, exist_ok=True)
    tty = sys.stderr.isatty()

    try:
        if zipfile.is_zipfile(archive_path):
            extract_zip(archive_path, destination, args.label, args.strip_components, tty)
        else:
            extract_tar(archive_path, destination, args.label, args.strip_components, tty)
    finally:
        if tty:
            sys.stderr.write("\n")
            sys.stderr.flush()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())