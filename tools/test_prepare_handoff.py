"""Offline exporter regressions; temporary repositories, no remotes or credentials."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import prepare_handoff as handoff


class HandoffTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.root = self.base / "repo"
        self.root.mkdir()
        self.git("init", "--initial-branch=main", "--template=")
        self.git("config", "user.name", "Synthetic Developer")
        self.git("config", "user.email", "synthetic@example.invalid")
        self.git("config", "commit.gpgSign", "false")
        self.git("config", "core.autocrlf", "false")
        self.put("README.md", b"old version\n")
        self.put("docs/deleted.md", b"removed later\n")
        self.git("add", "--all")
        self.git("commit", "-m", "Synthetic baseline")

    def git(self, *args):
        env = dict(os.environ, GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull)
        return subprocess.check_output(["git", "-C", str(self.root), *args], env=env, stderr=subprocess.PIPE)

    def put(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        return path

    def export(self, name="handoff.tar"):
        names, _ = handoff.inventory(self.root)
        output = self.base / name
        handoff.export(self.root, output, names, {"private-term-not-present"})
        return output

    def test_current_dirty_and_untracked_source_not_head(self):
        self.put("README.md", b"current fixed version\n")
        self.put("android-app/app/src/main/new.kt", b"// new implementation\n")
        (self.root / "docs/deleted.md").unlink()
        output = self.export()
        with tarfile.open(output) as archive:
            self.assertEqual(archive.extractfile("source/README.md").read(), b"current fixed version\n")
            self.assertIn("source/android-app/app/src/main/new.kt", archive.getnames())
            self.assertNotIn("source/docs/deleted.md", archive.getnames())
            self.assertFalse(any(".git/" in name for name in archive.getnames()))
        self.assertEqual(self.git("rev-list", "--count", "HEAD").strip(), b"1")

    def test_secrets_context_and_artifacts_excluded_even_if_tracked(self):
        denied = ["android-app/local.properties", "android-app/app/keystore.properties", "tools/key.json",
                  ".claude/private.md", ".env", "android-app/app/build/notes.md", "docs/test.mp4", "docs/private.pem"]
        for name in denied:
            self.put(name, b"private material")
        self.git("add", "--all")
        names, excluded = handoff.inventory(self.root)
        for name in denied:
            self.assertNotIn(name, names)
            self.assertIn(name, excluded)

    def test_signing_template_kept_but_real_signing_config_excluded(self):
        self.put("android-app/app/keystore.properties.example", b"storePassword=CHANGE_ME\n")
        self.put("android-app/app/keystore.properties", b"storePassword=private\n")
        names, excluded = handoff.inventory(self.root)
        self.assertIn("android-app/app/keystore.properties.example", names)
        self.assertIn("android-app/app/keystore.properties", excluded)

    def test_inventory_requires_repository_root(self):
        with self.assertRaises(ValueError):
            handoff.inventory(self.root / "docs")

    def test_private_configuration_excluded_even_if_tracked(self):
        for name in ('tools/credentials.json', 'tools/secrets.json'):
            self.put(name, b'private synthetic configuration')
            self.git('add', name)
        names, excluded = handoff.inventory(self.root)
        self.assertIn('tools/credentials.json', excluded)
        self.assertIn('tools/secrets.json', excluded)
        self.assertNotIn('tools/credentials.json', names)

    def test_identity_and_credentials_block_export(self):
        path = self.put("docs/report.md", b"Private Person")
        names, _ = handoff.inventory(self.root)
        with self.assertRaises(ValueError):
            handoff.export(self.root, self.base / "bad.tar", names, {"private person"})
        self.assertFalse((self.base / "bad.tar").exists())
        path.write_text("gh" + "p_" + "x" * 36, encoding="utf-8")
        with self.assertRaises(ValueError):
            self.export("token.tar")
        path.write_text("-----BEGIN " + "PRIVATE KEY-----", encoding="utf-8")
        with self.assertRaises(ValueError):
            self.export("key.tar")

    def test_git_author_and_committer_terms_read_without_config_changes(self):
        terms = handoff.git_identities(self.root)
        self.assertIn("Synthetic Developer", terms)
        self.assertIn("synthetic@example.invalid", terms)

    def test_manifest_modes_metadata_and_determinism(self):
        self.put("android-app/gradlew", b"#!/bin/sh\r\necho test\r\n")
        first = self.export("one.tar")
        second = self.export("two.tar")
        self.assertEqual(first.read_bytes(), second.read_bytes())
        extracted = self.base / "extracted"
        extracted.mkdir()
        with tarfile.open(first) as archive:
            for member in archive.getmembers():
                self.assertEqual((member.uid, member.gid, member.mtime), (0, 0, 0))
                self.assertEqual((member.uname, member.gname), ("", ""))
            self.assertEqual(archive.getmember("source/android-app/gradlew").mode, 0o755)
            self.assertNotIn(b"\r", archive.extractfile("source/android-app/gradlew").read())
            archive.extractall(extracted, filter="data")
        tree = extracted / "source"
        self.assertGreater(handoff.verify(tree), 0)
        (tree / "README.md").write_text("tampered", encoding="utf-8")
        with self.assertRaises(ValueError):
            handoff.verify(tree)
        digest = hashlib.sha256(first.read_bytes()).hexdigest()
        self.assertEqual(first.with_suffix(".tar.sha256").read_text().strip(), digest + "  one.tar")

    def test_existing_output_is_never_overwritten(self):
        first = self.export()
        before = first.read_bytes()
        with self.assertRaises(ValueError):
            self.export()
        self.assertEqual(first.read_bytes(), before)

    def test_concurrent_output_creation_is_not_deleted(self):
        output = self.base / "race.tar"
        original_open = Path.open
        def racing_open(path, mode="r", *args, **kwargs):
            if path == output and mode == "xb":
                with original_open(path, "wb") as other:
                    other.write(b"another process owns this")
                raise FileExistsError("synthetic race")
            return original_open(path, mode, *args, **kwargs)
        with patch.object(Path, "open", racing_open):
            with self.assertRaises(FileExistsError):
                self.export("race.tar")
        self.assertEqual(output.read_bytes(), b"another process owns this")

    def test_symlink_rejected(self):
        link = self.root / "docs/link.md"
        try:
            link.symlink_to(self.root / "README.md")
        except OSError:
            self.skipTest("Host does not allow symlink creation")
        with self.assertRaises(ValueError):
            handoff.inventory(self.root)

    def test_unknown_binary_and_unsafe_paths(self):
        self.put("docs/binary.md", b"bad\0data")
        with self.assertRaises(ValueError):
            self.export()
        for name in ("../README.md", "/README.md", "docs/../README.md", "docs/private.jks"):
            self.assertFalse(handoff.permitted(name))

    def test_manifest_extra_file_and_traversal_rejected(self):
        output = self.export()
        with tarfile.open(output) as archive:
            archive.extractall(self.base / "extract", filter="data")
        tree = self.base / "extract/source"
        (tree / "extra.txt").write_text("unexpected", encoding="utf-8")
        with self.assertRaises(ValueError):
            handoff.verify(tree)
        (tree / "extra.txt").unlink()
        manifest = tree / "HANDOFF_MANIFEST.json"
        manifest.write_text(json.dumps({"format": 1, "files": {"../outside": "0" * 64}}), encoding="utf-8")
        with self.assertRaises(ValueError):
            handoff.verify(tree)


if __name__ == "__main__":
    unittest.main()
