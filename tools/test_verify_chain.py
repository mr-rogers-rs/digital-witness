#!/usr/bin/env python3
"""
Round-trip tests for verify_chain.py.

Generates synthetic Digital Witness recording sessions (the same proof format
ProofManager.kt emits), runs the verifier against them as a subprocess, and
asserts the expected pass/fail outcome and exit code. Covers:

  * anonymous mode, multi-segment chain, with telemetry, UPLOAD-OBFUSCATED names
    (proof + artifacts renamed to random UUIDs, as CloudUploadManager does)
  * evidence mode with REAL ECDSA P-256 signatures + public key (local names)
  * tamper detection (flipped media byte)
  * broken chain link (wrong prevChainHash)
  * telemetry hash mismatch

Pure standard library, plus `cryptography` (already in tools/requirements.txt)
for the evidence-mode signature test. Exit code 0 = all tests passed.

Usage:
    python tools/test_verify_chain.py
"""

import base64
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
VERIFIER = HERE / "verify_chain.py"

try:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    HAS_CRYPTO = True
except ImportError:
    HAS_CRYPTO = False


def sha(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def _combine(mp4_hex: str, tel_hex: str | None) -> str:
    if tel_hex is None:
        return mp4_hex
    return sha(bytes.fromhex(mp4_hex) + bytes.fromhex(tel_hex))


def _chain(seg_hex: str, prev_hex: str) -> str:
    return sha(bytes.fromhex(seg_hex) + bytes.fromhex(prev_hex))


def make_session(d: Path, *, segments=2, anonymous=True, with_telemetry=True,
                 obfuscate=False, beacon=False, signer=None, pubkey_b64=None):
    """Write a full session (media + telemetry + proofs) into directory `d`.

    Returns the session id. When `obfuscate` is True, every artifact is written
    under a random-UUID name (as uploads are), while the proof keeps the
    original recorded filenames — exercising content-hash resolution.
    """
    sid = str(uuid.uuid4())
    salt = os.urandom(16)
    md = hashlib.sha256()
    md.update(sid.encode("utf-8"))
    beacon_obj = None
    if beacon:
        randomness = os.urandom(32).hex()
        beacon_obj = {"source": "drand", "round": 4242, "randomness": randomness}
        md.update(randomness.encode("utf-8"))
    md.update(salt)
    h0 = md.hexdigest()

    chain_seed = {"salt": base64.b64encode(salt).decode()}
    if beacon_obj:
        chain_seed["beacon"] = beacon_obj

    prev = h0
    for i in range(segments):
        mp4 = os.urandom(2048)
        orig_mp4 = f"{sid}_seg{i:03d}.mp4"
        mp4_name = f"{uuid.uuid4()}.mp4" if obfuscate else orig_mp4
        (d / mp4_name).write_bytes(mp4)
        mp4_hex = sha(mp4)

        tel_hex = None
        orig_tel = None
        if with_telemetry:
            tel = json.dumps({
                "v": 1, "anonymized": anonymous, "clock": {"source": "REALTIME"},
                "streams": {
                    "gyro": [[k * 20_000_000, 0.01 * k, -0.02, 0.03 * k, 0.0] for k in range(8)],
                    "accel": [[k * 20_000_000, 0.1, 9.8 + 0.01 * k, 0.05, 0.0] for k in range(8)],
                },
            }).encode()
            orig_tel = f"{sid}_seg{i:03d}.telemetry.json"
            tel_name = f"{uuid.uuid4()}.json" if obfuscate else orig_tel
            (d / tel_name).write_bytes(tel)
            tel_hex = sha(tel)

        seg_hex = _combine(mp4_hex, tel_hex)
        chain_hex = _chain(seg_hex, prev)

        proof = {
            "version": 3, "sessionId": sid, "segmentIndex": i,
            "segmentFilename": orig_mp4, "sha256": mp4_hex,
            "segmentHash": seg_hex, "prevChainHash": prev, "chainHash": chain_hex,
            "chainSeed": chain_seed,
            "timestampUnix": 1_700_000_000 + i, "timestampIso": "2023-11-14T22:13:20Z",
        }
        if tel_hex is not None:
            proof["telemetrySha256"] = tel_hex
            proof["telemetryFilename"] = orig_tel
        if not anonymous:
            proof["signatureAlgorithm"] = "SHA256withECDSA"
            proof["captureParams"] = {"resolution": "1280x720", "fps": 24,
                                      "videoBitrateKbps": 2500, "audioSampleRate": 44100,
                                      "audioBitrateKbps": 32}
            if signer is not None:
                sig = signer.sign(bytes.fromhex(chain_hex), ec.ECDSA(hashes.SHA256()))
                proof["deviceSignature"] = base64.b64encode(sig).decode()
                proof["publicKey"] = pubkey_b64

        proof_name = f"{uuid.uuid4()}.json" if obfuscate else f"{sid}_seg{i:03d}.proof.json"
        (d / proof_name).write_text(json.dumps(proof, indent=2))
        prev = chain_hex
    return sid


def run_verifier(d: Path, *extra):
    r = subprocess.run([sys.executable, str(VERIFIER), str(d), *extra],
                       capture_output=True, text=True)
    return r.returncode, r.stdout + r.stderr


# ── test cases ────────────────────────────────────────────────────────────────

def _tmp():
    return Path(tempfile.mkdtemp(prefix="dwverify_test_"))


def test_anonymous_obfuscated_passes():
    d = _tmp()
    make_session(d, segments=3, anonymous=True, with_telemetry=True,
                 obfuscate=True, beacon=True)
    # Run with --no-network: the synthetic beacon uses a real round number but
    # fabricated randomness, so a live re-fetch would (correctly) flag it as a
    # forged lower bound. This test's purpose is beacon-seeded H_0 + obfuscated
    # content-hash resolution, not live drand matching, so we disable the
    # network re-fetch to keep it deterministic and offline-safe.
    code, out = run_verifier(d, "--no-network")
    assert code == 0, f"expected pass, got {code}\n{out}"
    assert "ALL 3 SEGMENTS VERIFIED" in out, out
    assert "drand round 4242" in out, "beacon lower-bound not reported"


def test_evidence_signatures_pass():
    if not HAS_CRYPTO:
        print("  - skipping evidence/signature test (cryptography not installed)")
        return
    d = _tmp()
    sk = ec.generate_private_key(ec.SECP256R1())
    pub_der = sk.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo)
    pub_b64 = base64.b64encode(pub_der).decode()
    make_session(d, segments=2, anonymous=False, with_telemetry=True,
                 obfuscate=False, signer=sk, pubkey_b64=pub_b64)
    code, out = run_verifier(d)
    assert code == 0, f"expected pass, got {code}\n{out}"
    assert "valid (ECDSA P-256)" in out, "signatures not verified\n" + out


def test_tampered_media_fails():
    d = _tmp()
    make_session(d, segments=2, anonymous=True, obfuscate=False)
    # Flip a byte of the first segment's media after the proof was written.
    mp4 = sorted(d.glob("*_seg000.mp4"))[0]
    b = bytearray(mp4.read_bytes()); b[0] ^= 0xFF; mp4.write_bytes(b)
    code, out = run_verifier(d)
    assert code == 1, f"expected fail, got {code}\n{out}"
    assert "SHA-256 mismatch" in out or "not found" in out, out


def test_broken_chain_fails():
    d = _tmp()
    make_session(d, segments=2, anonymous=True, obfuscate=False)
    # Corrupt the second segment's prevChainHash so the link is broken.
    p = sorted(d.glob("*_seg001.proof.json"))[0]
    proof = json.loads(p.read_text())
    proof["prevChainHash"] = "0" * 64
    p.write_text(json.dumps(proof))
    code, out = run_verifier(d)
    assert code == 1, f"expected fail, got {code}\n{out}"
    assert "Chain" in out, out


def test_telemetry_mismatch_fails():
    d = _tmp()
    make_session(d, segments=1, anonymous=True, with_telemetry=True, obfuscate=False)
    tel = sorted(d.glob("*.telemetry.json"))[0]
    tel.write_bytes(tel.read_bytes() + b"x")  # change bytes -> hash no longer matches
    code, out = run_verifier(d)
    assert code == 1, f"expected fail, got {code}\n{out}"


def test_stripped_prevchain_fails():
    # Removing prevChainHash ENTIRELY (not just corrupting it) must FAIL, not be
    # silently skipped. Otherwise a forged/inserted segment with the field
    # stripped passes in anonymous mode (fail-open). Regression for that fix.
    d = _tmp()
    make_session(d, segments=2, anonymous=True, obfuscate=False)
    p = sorted(d.glob("*_seg001.proof.json"))[0]
    proof = json.loads(p.read_text())
    del proof["prevChainHash"]
    p.write_text(json.dumps(proof))
    code, out = run_verifier(d)
    assert code == 1, f"expected fail, got {code}\n{out}"
    assert "prevChainHash" in out, out


def test_stripped_signature_fails():
    # Evidence-mode session with the signature (and key) stripped from one
    # segment must FAIL. Previously "no signature" was only a warning, so an
    # attacker could strip the signature off a forged segment and downgrade it
    # to anonymous-looking. Regression for that fix.
    if not HAS_CRYPTO:
        print("  - skipping signature-strip test (cryptography not installed)")
        return
    d = _tmp()
    sk = ec.generate_private_key(ec.SECP256R1())
    pub_der = sk.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo)
    pub_b64 = base64.b64encode(pub_der).decode()
    make_session(d, segments=2, anonymous=False, with_telemetry=True,
                 obfuscate=False, signer=sk, pubkey_b64=pub_b64)
    p = sorted(d.glob("*_seg001.proof.json"))[0]
    proof = json.loads(p.read_text())
    proof.pop("deviceSignature", None)
    proof.pop("publicKey", None)
    p.write_text(json.dumps(proof))
    code, out = run_verifier(d)
    assert code == 1, f"expected fail, got {code}\n{out}"
    assert "Signature" in out, out


def main():
    tests = [
        test_anonymous_obfuscated_passes,
        test_evidence_signatures_pass,
        test_tampered_media_fails,
        test_broken_chain_fails,
        test_telemetry_mismatch_fails,
        test_stripped_prevchain_fails,
        test_stripped_signature_fails,
    ]
    failed = 0
    for t in tests:
        try:
            t()
            print(f"PASS  {t.__name__}")
        except AssertionError as e:
            failed += 1
            print(f"FAIL  {t.__name__}: {e}")
        except Exception as e:  # noqa: BLE001
            failed += 1
            print(f"ERROR {t.__name__}: {type(e).__name__}: {e}")
    print(f"\n{len(tests) - failed}/{len(tests)} passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
