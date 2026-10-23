#!/usr/bin/env python3
"""Offline, history-free WORKING-TREE export. Review the inventory before exporting.

Includes modified tracked files and eligible untracked source; never exports .git.
No commit, network, source deletion, automatic redaction or anonymity guarantee.
"""
import argparse
import getpass
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tarfile

ROOT_FILES = {"README.md", "HANDOFF.md", "LICENSE", "NOTICE", ".gitignore", ".gitattributes"}
ROOT_DIRS = {"android-app", "docs", "tools", "specs", "metadata", "verifier", ".github"}
DENIED_DIRS = {".git", ".claude", ".codex", ".agents", ".idea", ".vscode", ".gradle",
               "build", "__pycache__", ".venv", "venv", "node_modules", "handoff-output"}
DENIED_NAMES = {"local.properties", "keystore.properties", "election.properties", "election-signing.properties", "key.json", ".gitmodules",
                ".gitconfig", ".git-credentials", ".netrc", "credentials.json", "secrets.json"}
TEXT_SUFFIXES = {".md", ".kt", ".java", ".xml", ".gradle", ".properties", ".json", ".yml",
                 ".yaml", ".py", ".sh", ".bat", ".txt", ".pro", ".lockfile", ".go", ".mod", ".sum"}
WRAPPER = "android-app/gradle/wrapper/gradle-wrapper.jar"
SECRET_PATTERNS = [
    re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----"),
    re.compile(rb"\bgh[pousr]_[A-Za-z0-9]{30,}\b"),
    re.compile(rb"\bgithub_pat_[A-Za-z0-9_]{30,}\b"),
    re.compile(rb"https?://[^\s/:]+:[^\s/@]+@"),
]


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], stderr=subprocess.PIPE)


def permitted(name):
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or "\\" in name or any(ord(c) < 32 for c in name):
        return False
    if any(part in DENIED_DIRS for part in path.parts) or path.name in DENIED_NAMES:
        return False
    if path.name.startswith(".env"):
        return False
    if name in ROOT_FILES or name in {WRAPPER, "android-app/gradlew", "android-app/app/keystore.properties.example"}:
        return True
    return len(path.parts) > 1 and path.parts[0] in ROOT_DIRS and path.suffix in TEXT_SUFFIXES


def inventory(root):
    if Path(git(root, "rev-parse", "--show-toplevel").decode("utf-8").strip()).resolve() != root.resolve():
        raise ValueError("--source must be the repository root")
    # ls-files is read-only and includes the current uncommitted implementation.
    names = sorted(set(git(root, "ls-files", "--cached", "--others", "--exclude-standard", "-z")
                       .decode("utf-8").split("\0")) - {""})
    accepted, excluded = [], []
    for name in names:
        path = root / name
        if not permitted(name):
            excluded.append(name)
            continue
        if path.is_symlink() or any(parent.is_symlink() for parent in path.parents if parent != root):
            raise ValueError("Symlink in eligible source; review it before export")
        if not path.exists():  # A working-tree deletion is intentional, not an old HEAD file.
            continue
        if not path.is_file() or not path.resolve().is_relative_to(root):
            raise ValueError("Eligible path is not a regular source file")
        accepted.append(name)
    return accepted, excluded


def findings(name, data, terms):
    reasons = []
    folded = (name + "\n" + data.decode("utf-8", errors="replace")).casefold()
    if any(term.casefold() in folded for term in terms if term):
        reasons.append("private identity term")
    if any(pattern.search(data) for pattern in SECRET_PATTERNS):
        reasons.append("possible credential")
    if name != WRAPPER:
        try:
            data.decode("utf-8")
        except UnicodeError:
            reasons.append("non-UTF-8 source requires review")
        if b"\0" in data:
            reasons.append("unexpected binary data")
    return reasons


def git_identities(root):
    raw = git(root, "log", "--all", "--format=%an%x00%ae%x00%cn%x00%ce").decode("utf-8")
    return {part.strip() for line in raw.splitlines() for part in line.split("\0") if part.strip()}


def export(root, output, names, terms):
    if output.exists() or output.with_suffix(output.suffix + ".sha256").exists():
        raise ValueError("Output or checksum already exists; use a new filename")
    if output.suffix != ".tar":
        raise ValueError("Use a .tar output filename")
    # Read once: the bytes scanned are the bytes packaged, even if an editor later saves.
    payload = {name: (root / name).read_bytes() for name in names}
    # Shell launchers must work after a Windows-to-Tails transfer.
    for name in payload:
        if name == "android-app/gradlew" or name.endswith(".sh"):
            payload[name] = payload[name].replace(b"\r\n", b"\n")
    blocked = [(name, findings(name, data, terms)) for name, data in payload.items()]
    if any(reasons for _, reasons in blocked):
        raise ValueError("Export blocked by scan findings; run inventory and review locally")
    manifest = {"format": 1, "files": {name: hashlib.sha256(data).hexdigest() for name, data in payload.items()}}
    payload["HANDOFF_MANIFEST.json"] = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
    output.parent.mkdir(parents=True, exist_ok=True)
    created = False
    try:
        with output.open("xb") as stream:
            created = True
            with tarfile.open(fileobj=stream, mode="w", format=tarfile.PAX_FORMAT) as archive:
                for name, data in sorted(payload.items()):
                    info = tarfile.TarInfo("source/" + name)
                    info.size = len(data)
                    info.mtime = info.uid = info.gid = 0
                    info.uname = info.gname = ""
                    info.mode = 0o755 if name == "android-app/gradlew" or name.endswith(".sh") else 0o644
                    archive.addfile(info, io.BytesIO(data))
        digest = hashlib.sha256(output.read_bytes()).hexdigest()
        with output.with_suffix(output.suffix + ".sha256").open("x", encoding="ascii") as checksum:
            checksum.write(f"{digest}  {output.name}\n")
        return digest
    except Exception:
        # Only our newly created incomplete output, never the source tree.
        if created:
            output.unlink(missing_ok=True)
        raise


def verify(folder):
    manifest = json.loads((folder / "HANDOFF_MANIFEST.json").read_text(encoding="utf-8"))
    if manifest.get("format") != 1 or not isinstance(manifest.get("files"), dict):
        raise ValueError("Invalid manifest")
    expected = manifest["files"]
    actual = set()
    for path in folder.rglob("*"):
        relative = path.relative_to(folder).as_posix()
        if relative == ".git" or relative.startswith(".git/"):
            continue
        if path.is_symlink():
            raise ValueError("Unexpected symlink")
        if path.is_file() and relative != "HANDOFF_MANIFEST.json":
            actual.add(relative)
    if actual != set(expected):
        raise ValueError("Missing or extra source files")
    for name, digest in expected.items():
        if not permitted(name) or hashlib.sha256((folder / name).read_bytes()).hexdigest() != digest:
            raise ValueError("Changed file or unsafe manifest path")
    return len(expected)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["inventory", "export", "verify"])
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path)
    parser.add_argument("--git-identities", action="store_true", help="Scan original author/committer names and emails without printing them")
    parser.add_argument("--prompt", action="store_true", help="Enter additional private literal terms without echo/history")
    parser.add_argument("--reviewed", action="store_true", help="Confirm you reviewed eligible/excluded files and metadata")
    args = parser.parse_args()
    root = args.source.resolve()
    if args.action == "verify":
        print(f"PASS: {verify(root)} source files match the handoff manifest.")
        return
    names, excluded = inventory(root)
    terms = git_identities(root) if args.git_identities else set()
    if args.prompt:
        if not sys.stdin.isatty():
            raise ValueError("--prompt requires an interactive terminal; private terms must not echo into logs")
        print("Enter private names, usernames, emails, serials or hostnames one per prompt; blank ends. Values are not saved.")
        while True:
            value = getpass.getpass("Private search term (hidden): ").strip()
            if not value:
                break
            terms.add(value)
    if not terms:
        raise ValueError("Provide --git-identities and/or nonempty --prompt terms; empty scans are not accepted")
    blocked = False
    print(f"Eligible files: {len(names)}; excluded files: {len(excluded)}")
    for name in names:
        reasons = findings(name, (root / name).read_bytes(), terms)
        blocked |= bool(reasons)
        print(("REVIEW " if reasons else "INCLUDE ") + name + (": " + ", ".join(reasons) if reasons else ""))
    for name in excluded:
        print("EXCLUDE " + name)
    if blocked:
        raise ValueError("Scan findings remain. No archive was created; matched contents are intentionally not printed.")
    if args.action == "export":
        if not args.output or not args.reviewed:
            raise ValueError("Export requires --output and --reviewed after manual inventory review")
        print("Archive SHA-256: " + export(root, args.output.resolve(), names, terms))
    print("No scanned matches. This is not an anonymity or comprehensive secret-scan certification.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        # Keep raw OS paths, Git stderr and private term values out of error output.
        print("FAILED: " + (str(error) if isinstance(error, ValueError) else type(error).__name__))
        raise SystemExit(1)
