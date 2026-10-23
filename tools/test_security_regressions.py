"""Offline hostile-input regressions for the security checks."""
import base64
import contextlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
import test_verify_chain as fixtures
import verify_chain as verifier


class SecurityRegressionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.folder = Path(self.tmp.name)
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.public = base64.b64encode(self.key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()

    def session(self, count=1, version=3, telemetry=True, recovery=False):
        fixtures.make_session(self.folder, segments=count, anonymous=False,
            with_telemetry=telemetry, signer=self.key, pubkey_b64=self.public)
        if version == 4:
            previous = None
            for path in sorted(self.folder.glob("*.proof.json")):
                proof = json.loads(path.read_text())
                metadata = {k: proof[k] for k in ("sessionId", "segmentIndex", "chainSeed",
                    "timestampUnix", "timestampIso", "publicKey", "signatureAlgorithm", "captureParams")}
                metadata.update(version=4, mode="evidence")
                if recovery:
                    metadata['recovery'] = dict(interruptedSession='prior-session', partial=True, captureTimeKnown=False)
                raw = json.dumps(metadata, separators=(",", ":")).encode()
                bound = bytes.fromhex(proof["sha256"])
                if "telemetrySha256" in proof:
                    bound += bytes.fromhex(proof["telemetrySha256"])
                bound += hashlib.sha256(raw).digest()
                proof.update(metadata)
                proof["metadata"] = base64.b64encode(raw).decode()
                proof["segmentHash"] = fixtures.sha(bound)
                if previous:
                    proof["prevChainHash"] = previous
                previous = fixtures._chain(proof["segmentHash"], proof["prevChainHash"])
                proof["chainHash"] = previous
                proof["deviceSignature"] = base64.b64encode(self.key.sign(
                    bytes.fromhex(previous), ec.ECDSA(hashes.SHA256()))).decode()
                path.write_text(json.dumps(proof))

    def edit(self, action, index=0):
        path = sorted(self.folder.glob("*.proof.json"))[index]
        proof = json.loads(path.read_text())
        action(proof)
        path.write_text(json.dumps(proof))

    def run_verify(self, **kwargs):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            result = verifier.verify_chain_integrity(self.folder, no_network=True, **kwargs)
        return result, out.getvalue()

    def test_substituted_media_with_original_signature_rejected(self):
        self.session()
        def substitute(proof):
            media = self.folder / proof["segmentFilename"]
            media.write_bytes(b"replaced")
            proof["sha256"] = fixtures.sha(b"replaced")
            proof.pop("telemetrySha256")
            proof.pop("telemetryFilename")
        self.edit(substitute)
        self.assertEqual(self.run_verify()[0], 1)

    def test_missing_telemetry_rejected(self):
        self.session()
        next(self.folder.glob("*.telemetry.json")).unlink()
        self.assertEqual(self.run_verify()[0], 1)

    def test_missing_crypto_rejected(self):
        self.session()
        with patch.object(verifier, "HAS_CRYPTO", False):
            self.assertEqual(self.run_verify()[0], 1)

    def test_signature_stripping_rejected_under_policy(self):
        self.session()
        self.edit(lambda p: [p.pop(k) for k in ("publicKey", "deviceSignature", "signatureAlgorithm")])
        self.assertEqual(self.run_verify(require_signed=True)[0], 1)

    def test_valid_prefix_reports_unknown_and_checkpoint_rejects(self):
        self.session(2)
        path = sorted(self.folder.glob("*.proof.json"))[-1]
        tail = json.loads(path.read_text())
        path.unlink()
        code, output = self.run_verify()
        self.assertEqual(code, 0)
        self.assertIn("Completeness: UNKNOWN", output)
        self.assertEqual(self.run_verify(expected_head=tail["chainHash"], expected_count=2)[0], 1)

    def test_v4_passes(self):
        self.session(2, version=4)
        code, output = self.run_verify(require_signed=True, require_bound_metadata=True)
        self.assertEqual(code, 0, output)

    def test_grouped_evidence_and_obfuscated_names_pass(self):
        self.session(2, version=4)
        subfolder = self.folder / "Evidence"
        subfolder.mkdir()
        for index, artifact in enumerate(sorted(self.folder.glob("*.json"))):
            artifact.rename(subfolder / f"random-{index}.json")
        code, output = self.run_verify(require_signed=True, require_bound_metadata=True)
        self.assertEqual(code, 0, output)

    def test_missing_grouped_telemetry_still_rejected(self):
        self.session(version=4)
        subfolder = self.folder / "Evidence"
        subfolder.mkdir()
        for artifact in self.folder.glob("*.json"):
            if artifact.name.endswith(".proof.json"):
                artifact.rename(subfolder / artifact.name)
            else:
                artifact.unlink()
        self.assertEqual(self.run_verify(require_bound_metadata=True)[0], 1)

    def test_grouped_detached_timestamp_discovered(self):
        self.session(version=4)
        subfolder = self.folder / "Evidence"
        subfolder.mkdir()
        proof = json.loads(next(self.folder.glob("*.proof.json")).read_text())
        magic = bytes.fromhex("004f70656e54696d657374616d7073000050726f6f6600bf89e2e884e89294")
        (subfolder / "random.ots").write_bytes(magic + bytes([1, 8]) + bytes.fromhex(proof["chainHash"]))
        code, output = self.run_verify()
        self.assertEqual(code, 0, output)
        self.assertIn("upper bound unverified", output)

    def test_v4_no_telemetry_passes(self):
        self.session(version=4, telemetry=False)
        code, output = self.run_verify(require_signed=True)
        self.assertEqual(code, 0, output)

    def test_v4_outer_metadata_tamper_rejected(self):
        self.session(version=4)
        self.edit(lambda p: p.update(timestampUnix=0))
        self.assertEqual(self.run_verify()[0], 1)

    def test_v4_inner_metadata_tamper_rejected(self):
        self.session(version=4)
        def change(p):
            raw = json.loads(base64.b64decode(p["metadata"]))
            raw["timestampUnix"] = 0
            p["timestampUnix"] = 0
            p["metadata"] = base64.b64encode(json.dumps(raw).encode()).decode()
        self.edit(change)
        self.assertEqual(self.run_verify()[0], 1)

    def test_legacy_metadata_cannot_meet_bound_policy(self):
        self.session()
        self.assertEqual(self.run_verify(require_bound_metadata=True)[0], 1)

    def test_trusted_signer_mismatch_rejected(self):
        self.session()
        self.assertEqual(self.run_verify(expected_key_sha256="0"*64)[0], 1)

    def test_recovered_partial_reports_limitations(self):
        self.session(version=4, recovery=True)
        code, output = self.run_verify(require_signed=True, require_bound_metadata=True)
        self.assertEqual(code, 0, output)
        self.assertIn('Recovered partial recording', output)

    def test_recovery_declaration_cannot_be_removed(self):
        self.session(version=4, recovery=True)
        self.edit(lambda p: p.pop('recovery'))
        self.assertEqual(self.run_verify()[0], 1)

    def test_duplicate_indices_rejected(self):
        self.session(2)
        self.edit(lambda p: p.update(segmentIndex=0), index=1)
        self.assertEqual(self.run_verify()[0], 1)

    def test_mixed_sessions_require_selection(self):
        self.session()
        self.session()
        self.assertEqual(self.run_verify()[0], 2)

    def test_traversal_rejected(self):
        self.session()
        self.edit(lambda p: p.update(segmentFilename="../outside.mp4"))
        self.assertEqual(self.run_verify()[0], 1)

    def test_invalid_hash_rejected_without_traceback(self):
        self.session()
        self.edit(lambda p: p.update(chainHash="invalid"))
        self.assertEqual(self.run_verify()[0], 1)

    def test_malformed_json_rejected(self):
        (self.folder / "test.proof.json").write_text("{")
        self.assertEqual(self.run_verify()[0], 1)

    def test_offline_never_invokes_timestamp_or_network(self):
        self.session()
        proof = json.loads(next(self.folder.glob("*.proof.json")).read_text())
        magic = bytes.fromhex("004f70656e54696d657374616d7073000050726f6f6600bf89e2e884e89294")
        (self.folder / "renamed.ots").write_bytes(magic+bytes([1,8])+bytes.fromhex(proof["chainHash"]))
        with patch.object(verifier, "verify_opentimestamps", side_effect=AssertionError("network")), patch.object(
                verifier, "fetch_drand_round", side_effect=AssertionError("network")):
            code, output = self.run_verify()
        self.assertEqual(code, 0, output)
        self.assertIn("upper bound unverified", output)

    def test_require_anchor_fails_offline(self):
        self.session()
        self.assertEqual(self.run_verify(require_anchored=True)[0], 1)


if __name__ == "__main__":
    unittest.main()
