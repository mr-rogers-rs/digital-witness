#!/usr/bin/env python3
"""
Digital Witness -- Integrity Chain Verifier

Verifies the SHA-256 hash chain and ECDSA signatures of Digital Witness
recording segments. Produces a court-ready report.

Usage:
    python verify_chain.py <folder_with_mp4s_and_proofs> [--session-id <id>]

The folder should contain pairs of files:
    recording_001.mp4           -- the video segment
    recording_001.proof.json    -- the sidecar integrity proof

Exit codes:
    0 -- all segments valid
    1 -- one or more segments failed verification
    2 -- input error (missing files, bad JSON, etc.)
"""

import argparse
import bisect
import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import sys
from base64 import b64decode
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import URLError
from urllib.request import Request as UrlRequest, urlopen

try:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec, utils
    from cryptography.x509 import load_der_x509_certificate
    HAS_CRYPTO = True
except ImportError:
    HAS_CRYPTO = False


# Public HTTP relays for the default League-of-Entropy drand chain. Must match
# DrandClient.kt — the verifier re-fetches each proof's claimed round from one
# of these and confirms the randomness matches. A proof could otherwise lie
# about its lower time bound (audit C7).
DRAND_RELAYS = (
    "https://api.drand.sh",
    "https://api2.drand.sh",
    "https://api3.drand.sh",
    "https://drand.cloudflare.com",
)
DRAND_FETCH_TIMEOUT = 8.0
DRAND_USER_AGENT = "dwverify/1.0"


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            chunk = f.read(8192)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def chain_hash(segment_hash_hex: str, prev_chain_hash_hex: str) -> str:
    md = hashlib.sha256()
    md.update(bytes.fromhex(segment_hash_hex))
    md.update(bytes.fromhex(prev_chain_hash_hex))
    return md.hexdigest()


def verify_signature(chain_hash_bytes: bytes, signature_b64: str, public_key_b64: str) -> bool:
    if not HAS_CRYPTO:
        return None  # cannot verify without cryptography library

    if not signature_b64 or not public_key_b64:
        return None

    try:
        sig_bytes = b64decode(signature_b64)
        pub_key_der = b64decode(public_key_b64)
        public_key = serialization.load_der_public_key(pub_key_der)
        if not isinstance(public_key, ec.EllipticCurvePublicKey) or not isinstance(public_key.curve, ec.SECP256R1):
            return False

        if not isinstance(public_key, ec.EllipticCurvePublicKey):
            return False

        public_key.verify(sig_bytes, chain_hash_bytes, ec.ECDSA(hashes.SHA256()))
        return True
    except Exception:
        return False


def fetch_drand_round(round_no):
    """
    Re-fetch a specific drand round from the public relays. Returns the
    ``randomness`` hex string, or None if every relay is unreachable.

    The drand network is public, immutable, and independently verifiable, so
    a proof's claimed ``round``/``randomness`` can be confirmed against it.
    Without this, a malicious proof could cite any future round's time as its
    lower bound and the verifier would accept it (audit C7).
    """
    for relay in DRAND_RELAYS:
        url = f"{relay}/public/{int(round_no)}"
        try:
            req = UrlRequest(url, headers={"User-Agent": DRAND_USER_AGENT})
            with urlopen(req, timeout=DRAND_FETCH_TIMEOUT) as resp:
                obj = json.loads(resp.read().decode("utf-8"))
                rnd = obj.get("randomness", "")
                if rnd and int(obj.get("round", -1)) == int(round_no):
                    return rnd
        except (URLError, OSError, ValueError):
            continue
    return None


def verify_opentimestamps(ots_path, chain_digest):
    """
    If an OpenTimestamps ``.ots`` sidecar exists for a segment, attempt to
    verify it with the upstream ``ots-cli`` (or ``ots``) tool against the
    Bitcoin chain. Returns one of: "valid", "pending", "failed", "no-tool",
    "error".

    OTS gives the chain its **upper** time bound ("this file existed before
    Bitcoin block N"); previously the verifier never even looked for ``.ots``
    files (audit C7). Best-effort: the tool may not be installed in a court
    air-gapped environment, in which case we report ``no-tool`` as a warning
    rather than silently passing.
    """
    tool = shutil.which("ots")
    if not tool:
        return "no-tool"
    try:
        # Both `ots-cli verify` and `ots verify` take the .ots file and the
        # target file; `ots-cli` prints "Success!" / "Pending" on stdout.
        proc = subprocess.run(
            [tool, "verify", "-d", chain_digest, ots_path],
            capture_output=True, text=True, timeout=30,
        )
        out = (proc.stdout + proc.stderr).lower()
        if proc.returncode == 0 and "success" in out:
            return "valid"
        if "pending" in out:
            return "pending"
        return "failed"
    except Exception:
        return "error"


def _pearson(xs, ys):
    """Pearson correlation, or None if undefined (too few points / zero variance)."""
    n = len(xs)
    if n < 3:
        return None
    mx = sum(xs) / n
    my = sum(ys) / n
    sxy = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    sxx = sum((x - mx) ** 2 for x in xs)
    syy = sum((y - my) ** 2 for y in ys)
    if sxx <= 0 or syy <= 0:
        return None
    return sxy / math.sqrt(sxx * syy)


def _stream_stats(samples):
    """(count, duration_s, rate_hz, max_gap_ms, monotonic) for a [[t, ...], ...] stream."""
    if not samples:
        return None
    ts = [s[0] for s in samples]
    count = len(ts)
    dur_s = (ts[-1] - ts[0]) / 1e9 if ts[-1] > ts[0] else 0.0
    rate = (count - 1) / dur_s if dur_s > 0 else 0.0
    gaps = [ts[i + 1] - ts[i] for i in range(len(ts) - 1)]
    max_gap_ms = max(gaps) / 1e6 if gaps else 0.0
    monotonic = all(g >= 0 for g in gaps)
    return count, dur_s, rate, max_gap_ms, monotonic


def _mag_variation(samples):
    """Std-dev of the per-sample 3-axis vector magnitude (removes DC/gravity offset)."""
    mags = [math.sqrt(s[1] ** 2 + s[2] ** 2 + s[3] ** 2) for s in samples if len(s) >= 4]
    if len(mags) < 3:
        return None
    m = sum(mags) / len(mags)
    return math.sqrt(sum((x - m) ** 2 for x in mags) / len(mags))


def _nearest(sorted_ts, values, t):
    i = bisect.bisect_left(sorted_ts, t)
    if i <= 0:
        return values[0]
    if i >= len(sorted_ts):
        return values[-1]
    return values[i - 1] if abs(sorted_ts[i - 1] - t) <= abs(sorted_ts[i] - t) else values[i]


def cross_modal_report(tel_path, warnings):
    try:
        _cross_modal_report(tel_path, warnings)
    except (TypeError, ValueError, KeyError, IndexError, OverflowError, AttributeError):
        warnings.append("Malformed telemetry analysis fields; advisory analysis unavailable")


def _cross_modal_report(tel_path, warnings):
    """
    Report cross-modal physical-consistency metrics from the signed sensor telemetry.
    These are the relationships a generative ("AI") video cannot fabricate consistently;
    findings are advisory (printed for an expert), not hard pass/fail criteria. Uses only
    the telemetry JSON (no video decode) and the Python standard library.
    """
    try:
        with open(tel_path, encoding="utf-8") as f:
            tel = json.load(f)
    except Exception:
        return
    streams = tel.get("streams", {})
    clock = tel.get("clock", {})
    print("  [INFO] Telemetry consistency (advisory; does not prove authenticity):")

    # 1. Sampling integrity
    for name in ("accel", "gyro", "baro", "light"):
        st = _stream_stats(streams.get(name, []))
        if st:
            c, dur, rate, gap, mono = st
            flag = "" if mono else "  [WARN: non-monotonic timestamps]"
            print(f"         {name}: {c} samples, {dur:.1f}s, {rate:.1f} Hz, max gap {gap:.0f} ms{flag}")
            if not mono:
                warnings.append(f"Telemetry {name} timestamps non-monotonic")

    # 2. Motion liveness (a static or empty trace is a red flag for fabricated handheld video)
    gyro_var = _mag_variation(streams.get("gyro", []))
    accel_var = _mag_variation(streams.get("accel", []))
    if gyro_var is not None and accel_var is not None:
        live = gyro_var > 1e-3 or accel_var > 1e-2
        verdict = "PLAUSIBLE hand-held motion" if live else "NO MOTION (fixed mount, or suspicious)"
        print(f"         motion liveness: gyro var~{gyro_var:.4f} rad/s, accel var~{accel_var:.3f} m/s^2 ({verdict})")
        if not live:
            warnings.append("Telemetry shows ~no motion - confirm the recording was not fixed/fabricated")

    # 3 & 4. Camera-control loop vs. environment (needs per-frame camera metadata)
    cam = tel.get("cameraFrames", [])
    if cam:
        src = clock.get("cameraTimestampSource", "")
        off = clock.get("cameraOffsetNanos") or 0
        def cam_t(fr):
            t = fr.get("t", 0)
            return t + off if src == "UNKNOWN" else t

        # AE <-> light: exposure*ISO (gain) should fall as ambient light rises (r < 0)
        light = streams.get("light", [])
        if light:
            lts = [s[0] for s in light]
            lv = [s[1] for s in light]
            gains, lux = [], []
            for fr in cam:
                exp, iso = fr.get("exposureNs"), fr.get("iso")
                if exp and iso:
                    gains.append(math.log(exp * iso + 1.0))
                    lux.append(_nearest(lts, lv, cam_t(fr)))
            r = _pearson(gains, lux)
            if r is not None:
                ok = r < 0
                tag = "OK (gain falls as light rises)" if ok else "UNEXPECTED SIGN"
                print(f"         camera AE<->light: r={r:+.2f} over {len(gains)} frames [{tag}]")
                if not ok and abs(r) > 0.3:
                    warnings.append(f"AE/light correlation has unexpected sign (r={r:+.2f})")

        # rolling-shutter skew <-> gyro magnitude: should rise together under motion (r >= 0)
        gyro = streams.get("gyro", [])
        if gyro:
            gts = [s[0] for s in gyro]
            gmag = [math.sqrt(s[1] ** 2 + s[2] ** 2 + s[3] ** 2) for s in gyro]
            skews, gm = [], []
            for fr in cam:
                sk = fr.get("skewNs")
                if sk is not None:
                    skews.append(abs(sk))
                    gm.append(_nearest(gts, gmag, cam_t(fr)))
            r = _pearson(skews, gm)
            if r is not None:
                print(f"         rolling-shutter<->gyro: r={r:+.2f} over {len(skews)} frames (expect >=0 under motion)")

    print("         (note: full pixel-level optical-flow<->gyro correlation requires video")
    print("          decode and is an advanced expert step beyond this verifier.)")


PROOF_MARKER_FIELDS = ("chainHash", "sha256", "sessionId")


def _looks_like_proof(obj) -> bool:
    return isinstance(obj, dict) and all(k in obj for k in PROOF_MARKER_FIELDS)


def evidence_files(folder: Path):
    """Read the root and the app-owned Evidence subfolder, without following links."""
    yield from folder.iterdir()
    sidecars = folder / "Evidence"
    if not sidecars.is_symlink() and sidecars.is_dir():
        yield from sidecars.iterdir()


def build_index(folder: Path):
    """
    Scan the folder once and return (proofs, hash_index).

    proofs:     list of (path, proof_dict)
    hash_index: dict { sha256_hex -> path } for every non-proof file

    Proofs are detected by *content* (a JSON file carrying the proof marker
    fields), not by the ``.proof.json`` suffix, because uploads are renamed to
    random UUIDs (CloudUploadManager/uploadViaSAF) and a downloaded proof is
    therefore ``<uuid>.json``. Every other file is indexed by its SHA-256 so a
    proof's ``sha256`` / ``telemetrySha256`` can be resolved regardless of
    filename obfuscation (the cryptographic binding lives in the proof, not in
    the names). See design doc §2.9 item 7.
    """
    proofs = []
    hash_index = {}
    for p in sorted(evidence_files(folder)):
        if p.is_symlink() or not p.is_file():
            continue
        # Proofs and telemetry sidecars are JSON; only JSON files can be proofs.
        if p.suffix.lower() == ".json":
            if p.stat().st_size > 32 * 1024 * 1024:
                raise ValueError("JSON artifact exceeds 32 MiB limit")
            try:
                with open(p, "r", encoding="utf-8") as f:
                    obj = json.load(f)
            except (json.JSONDecodeError, UnicodeError, OSError) as exc:
                raise ValueError(f"Invalid JSON artifact: {p.name}") from exc
            if _looks_like_proof(obj) or p.name.endswith(".proof.json"):
                if not isinstance(obj, dict):
                    raise ValueError("Proof must be a JSON object")
                proofs.append((p, obj))
                continue
            # A JSON file that is not a proof (e.g. a telemetry sidecar) is a
            # content artifact — fall through and index it by hash.
        hash_index[sha256_file(str(p))] = p
    return proofs, hash_index


def discover_proof_pairs(folder: str, session_id: str = None):
    """Find proofs and resolve their media/telemetry artifacts by content hash."""
    folder = Path(folder)
    proofs, hash_index = build_index(folder)

    pairs = []
    for proof_path, proof in proofs:
        if session_id and proof.get("sessionId") != session_id:
            continue
        validate_proof(proof)

        # Prefer content-hash resolution (survives UUID renaming on upload);
        # fall back to the recorded filename for the local, un-obfuscated layout.
        mp4_path = hash_index.get(proof.get("sha256", ""))
        if mp4_path is None:
            mp4_name = proof.get("segmentFilename", "")
            cand = safe_artifact_path(folder, mp4_name) if mp4_name else None
            if cand and cand.exists():
                mp4_path = cand
            else:
                stem = proof_path.stem.replace(".proof", "")
                mp4_path = folder / (stem + ".mp4")  # may not exist; reported later

        tel_path = None
        tel_hash = proof.get("telemetrySha256")
        if tel_hash:
            tel_path = hash_index.get(tel_hash)
            if tel_path is None:
                tel_name = proof.get("telemetryFilename")
                if tel_name and safe_artifact_path(folder, tel_name).is_file():
                    tel_path = safe_artifact_path(folder, tel_name)

        pairs.append((mp4_path, proof_path, proof, tel_path))

    pairs.sort(key=lambda t: t[2].get("segmentIndex", 0))
    return pairs


def safe_artifact_path(folder, name):
    if not isinstance(name, str) or not name or Path(name).name != name or '/' in name or '\\' in name:
        raise ValueError("Unsafe artifact filename")
    path = (Path(folder) / name).resolve()
    if path.parent != Path(folder).resolve():
        raise ValueError("Artifact escapes evidence directory")
    return path


def validate_proof(proof):
    if type(proof.get('version')) is not int or proof.get('version') not in (3, 4):
        raise ValueError('Unsupported proof version')
    if not isinstance(proof.get('sessionId'), str) or not proof['sessionId']:
        raise ValueError('Missing sessionId')
    if type(proof.get('segmentIndex')) is not int or proof['segmentIndex'] < 0:
        raise ValueError('Invalid segmentIndex')
    for field in ('sha256', 'segmentHash', 'prevChainHash', 'chainHash'):
        if not isinstance(proof.get(field), str) or not re.fullmatch('[0-9a-f]{64}', proof[field]):
            raise ValueError(f'Invalid {field}')
    for field in ('segmentFilename', 'telemetryFilename'):
        if field in proof:
            safe_artifact_path(Path.cwd(), proof[field])
    if 'telemetrySha256' in proof and not re.fullmatch('[0-9a-f]{64}', str(proof['telemetrySha256'])):
        raise ValueError('Invalid telemetrySha256')
    if not isinstance(proof.get('chainSeed', {}), dict):
        raise ValueError('Invalid chainSeed')
    for field in ('captureParams', 'environment', 'deviceAttestation', 'recovery'):
        if field in proof and not isinstance(proof[field], dict):
            raise ValueError(f'Invalid {field}')
    if 'recovery' in proof:
        recovery = proof['recovery']
        if (recovery.get('partial') is not True or recovery.get('captureTimeKnown') is not False
                or not isinstance(recovery.get('interruptedSession'), str)
                or not recovery['interruptedSession']):
            raise ValueError('Invalid recovery declaration')
    for field in ('publicKey', 'deviceSignature', 'metadata', 'timestampIso', 'signatureAlgorithm'):
        if field in proof and (not isinstance(proof[field], str) or len(proof[field]) > 1024 * 1024):
            raise ValueError(f'Invalid {field}')
    if 'publicKey' in proof and proof.get('signatureAlgorithm') != 'SHA256withECDSA':
        raise ValueError('Unsupported signature algorithm')



def verify_chain_integrity(folder: str, session_id: str = None, no_network: bool = False,
                           require_signed: bool = False, expected_head: str = None,
                           expected_count: int = None, require_anchored: bool = False,
                           require_bound_metadata: bool = False, expected_key_sha256: str = None):
    # Normalize to a Path up front: the OTS-sidecar lookup below does
    # `folder / name`, which raises TypeError on a bare str and aborted
    # verification for every segment whose MP4 was present (the normal case).
    folder = Path(folder)
    try:
        pairs = discover_proof_pairs(folder, session_id)
    except (ValueError, TypeError, OSError) as exc:
        print(f'ERROR: {exc}', file=sys.stderr)
        return 1

    if not pairs:
        print("ERROR: No proof files found.", file=sys.stderr)
        return 2

    detected_session = pairs[0][2].get("sessionId", "unknown")
    if any(p[2]['sessionId'] != detected_session for p in pairs):
        print('ERROR: Multiple sessions present; select --session-id.', file=sys.stderr)
        return 2
    if [p[2]['segmentIndex'] for p in pairs] != list(range(len(pairs))):
        print('ERROR: Segment indices must be unique and contiguous from zero.', file=sys.stderr)
        return 1
    session_signed = require_signed or bool(expected_key_sha256) or any(p[2].get('publicKey') or p[2].get('deviceSignature') for p in pairs)
    print("=" * 72)
    print("DIGITAL WITNESS -- INTEGRITY VERIFICATION REPORT")
    print("=" * 72)
    print(f"Date:       {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')}")
    print(f"Folder:     {os.path.abspath(folder)}")
    print(f"Session:    {detected_session}")
    print(f"Segments:   {len(pairs)}")
    print(f"Signature verification: {'available' if HAS_CRYPTO else 'UNAVAILABLE (install cryptography)'}")
    print(f"Drand re-fetch: {'DISABLED (--no-network)' if no_network else 'enabled'}")
    print("=" * 72)
    print()

    all_ok = True
    prev_chain_hash = None
    public_key_b64 = None
    signatures_verified = 0

    for mp4_path, proof_path, proof, tel_path in pairs:
        if require_bound_metadata and proof["version"] != 4:
            print("ERROR: Version 4 required for metadata binding", file=sys.stderr)
            return 1
        idx = proof.get("segmentIndex", "?")
        filename = proof.get("segmentFilename") or (mp4_path.name if mp4_path else "?")
        print(f"--- Segment {idx}: {filename} ---")

        errors = []
        warnings = []

        if mp4_path is None or not mp4_path.exists():
            errors.append(f"MP4 not found (by content hash {proof.get('sha256','')[:16]}... or filename {filename})")
        else:
            actual_hash = sha256_file(str(mp4_path))
            expected_hash = proof.get("sha256", "")
            if actual_hash == expected_hash:
                print(f"  [OK] SHA-256:      {actual_hash}")
            else:
                errors.append(f"SHA-256 mismatch: expected {expected_hash}, got {actual_hash}")
                print(f"  [FAIL] SHA-256:    expected {expected_hash}")
                print(f"                     got      {actual_hash}")

        expected_prev = proof.get("prevChainHash", "")
        expected_chain = proof.get("chainHash", "")
        mp4_hash = proof.get("sha256", "")

        # Sensor-telemetry sidecar (anti-"it's AI"): verify its hash and that the value
        # committed to the chain is SHA256(mp4_hash ‖ telemetry_hash).
        tel_hash_hex = proof.get("telemetrySha256")
        tel_name = proof.get("telemetryFilename")
        declared_segment_hash = proof.get("segmentHash", mp4_hash)
        if tel_hash_hex:
            if tel_path and tel_path.exists():
                actual_tel = sha256_file(str(tel_path))
                if actual_tel == tel_hash_hex:
                    print(f"  [OK] Telemetry:    {actual_tel[:32]}... ({tel_path.name})")
                else:
                    errors.append(f"Telemetry hash mismatch: expected {tel_hash_hex}, got {actual_tel}")
                    print(f"  [FAIL] Telemetry:  hash mismatch")
            else:
                errors.append(f"Telemetry referenced (sha256 {tel_hash_hex[:16]}...) but file not found: {tel_name}")
            try:
                md = hashlib.sha256()
                md.update(bytes.fromhex(mp4_hash))
                md.update(bytes.fromhex(tel_hash_hex))
                recomputed = md.hexdigest()
                if proof['version'] == 3 and recomputed != declared_segment_hash:
                    errors.append("segmentHash != SHA256(mp4 hash || telemetry hash)")
                    print(f"  [FAIL] Segment:    segmentHash does not bind telemetry")
                else:
                    print(f"  [OK] Segment bind: segmentHash = SHA256(mp4 || telemetry)")
            except Exception:
                errors.append("Could not recompute combined segmentHash")
            if tel_path and tel_path.exists():
                cross_modal_report(tel_path, warnings)

        if proof['version'] == 3 and not tel_hash_hex and declared_segment_hash != mp4_hash:
            errors.append('segmentHash does not bind the media hash')
        if proof['version'] == 4:
            try:
                metadata_bytes = b64decode(proof['metadata'], validate=True)
                metadata = json.loads(metadata_bytes)
                required = ('version', 'sessionId', 'segmentIndex', 'chainSeed',
                            'timestampUnix', 'timestampIso', 'mode')
                for field in required:
                    if field not in metadata or metadata[field] != proof.get(field):
                        raise ValueError(f'metadata mismatch: {field}')
                for field in ('environment', 'captureParams', 'deviceAttestation', 'publicKey', 'signatureAlgorithm', 'recovery'):
                    if metadata.get(field) != proof.get(field):
                        raise ValueError(f'metadata mismatch: {field}')
                if metadata['mode'] not in ('anonymous', 'evidence'):
                    raise ValueError('Invalid recording mode')
                if metadata['mode'] == 'evidence' and not session_signed:
                    raise ValueError('Evidence mode requires signatures')
                bound = bytes.fromhex(mp4_hash)
                if tel_hash_hex:
                    bound += bytes.fromhex(tel_hash_hex)
                bound += hashlib.sha256(metadata_bytes).digest()
                if sha256_bytes(bound) != declared_segment_hash:
                    raise ValueError('segmentHash does not bind media, telemetry and metadata')
            except (KeyError, ValueError, TypeError) as exc:
                errors.append(f'Invalid v4 metadata binding: {exc}')
        else:
            warnings.append('Legacy v3: time, location, capture parameters and attestation are NOT authenticated')

        if proof.get('recovery'):
            warnings.append('Recovered partial recording after interruption: capture time, continuity and the final tail are not established')

        # Value committed to the hash chain (combined segmentHash when present, else mp4 hash)
        segment_hash = declared_segment_hash

        if prev_chain_hash is None:
            # H_0 = SHA256(sessionId ‖ beacon.randomness ‖ salt) when a chainSeed is
            # present (non-deterministic seed with a drand lower time bound); otherwise
            # the legacy H_0 = SHA256(sessionId).
            seed = proof.get("chainSeed") or {}
            salt_b64 = seed.get("salt")
            beacon = seed.get("beacon") or {}
            randomness = beacon.get("randomness", "")
            md = hashlib.sha256()
            md.update(detected_session.encode("utf-8"))
            if randomness:
                md.update(randomness.encode("utf-8"))
            if salt_b64:
                try:
                    md.update(b64decode(salt_b64))
                except Exception:
                    pass
            init_hash = md.hexdigest()
            seeded_with = "sessionId"
            if randomness:
                seeded_with += " + drand round %s" % beacon.get("round", "?")
            if salt_b64:
                seeded_with += " + salt"

            # H_0 is the root of trust for the whole chain — a mismatch means
            # the chain was not seeded the way the proof claims, so it must be
            # a HARD failure, not a warning (audit C7). A warning would let a
            # tampered chain pass court review.
            if expected_prev == init_hash:
                print(f"  [OK] Chain init:   H_0 = SHA-256({seeded_with})")
            else:
                errors.append(
                    f"H_0 mismatch: first segment prevChainHash ({expected_prev[:16]}...) "
                    f"!= derived H_0 ({init_hash[:16]}...). Chain root cannot be trusted."
                )
                print(f"  [FAIL] Chain init: H_0 mismatch (chain root not verifiable)")

            # Re-fetch the claimed drand round from the public network and
            # confirm the randomness in the proof matches. Without this, a
            # proof could cite any round number / randomness and the verifier
            # would take it on faith (audit C7).
            if randomness and not no_network:
                round_no = beacon.get("round")
                if round_no is not None:
                    fetched = fetch_drand_round(round_no)
                    if fetched is None:
                        warnings.append(
                            f"Could not re-fetch drand round {round_no} from any relay "
                            f"(offline?) — lower time bound NOT independently confirmed"
                        )
                        print(f"  [WARN] Drand:      round {round_no} unreachable — bound unconfirmed")
                    elif fetched == randomness:
                        print(f"  [OK] Drand:        round {round_no} randomness matches public beacon")
                    else:
                        errors.append(
                            f"Drand randomness mismatch for round {round_no}: proof cites a "
                            f"value that does not match the public beacon (forged lower bound?)"
                        )
                        print(f"  [FAIL] Drand:      round {round_no} randomness does NOT match public beacon")
                else:
                    warnings.append("Chain seed has randomness but no round number — cannot re-verify")
            elif randomness and no_network:
                print(f"  [SKIP] Drand:      re-fetch disabled (--no-network), round {beacon.get('round','?')} unconfirmed")
            prev_chain_hash = expected_prev

        if not expected_prev:
            # A genuine proof ALWAYS carries prevChainHash (segment 0's is H_0).
            # An empty value can only mean the field was stripped or forged, so
            # this must be a HARD failure — never a silently-skipped check.
            # Skipping it (the previous behavior) let a fabricated segment with
            # prevChainHash removed pass verification in anonymous mode.
            errors.append("prevChainHash missing/empty — chain link unverifiable (possible tampering)")
            print(f"  [FAIL] Chain link: prevChainHash absent")
        elif prev_chain_hash:
            if expected_prev == prev_chain_hash:
                print(f"  [OK] Chain link:   prevChainHash matches")
            else:
                errors.append(f"Chain break: expected prev={prev_chain_hash[:16]}..., proof says {expected_prev[:16]}...")
                print(f"  [FAIL] Chain link: BROKEN")

        if not expected_chain:
            errors.append("chainHash missing/empty — segment is not chained (possible tampering)")
            print(f"  [FAIL] Chain hash: chainHash absent")
        elif not segment_hash:
            errors.append("segmentHash/sha256 missing — nothing to bind into the chain (possible tampering)")
            print(f"  [FAIL] Chain hash: segment hash absent")
        elif expected_prev:
            computed_chain = chain_hash(segment_hash, expected_prev)
            if computed_chain == expected_chain:
                print(f"  [OK] Chain hash:   {expected_chain}")
            else:
                errors.append(f"Chain hash mismatch: expected {expected_chain}, computed {computed_chain}")
                print(f"  [FAIL] Chain hash: expected {expected_chain}")
                print(f"                     computed {computed_chain}")

        sig_b64 = proof.get("deviceSignature", "")
        pk_b64 = proof.get("publicKey", "")
        if expected_key_sha256:
            try:
                if hashlib.sha256(b64decode(pk_b64, validate=True)).hexdigest() != expected_key_sha256:
                    errors.append("Signing key does not match trusted fingerprint")
            except Exception:
                errors.append("Invalid signing key encoding")
        if pk_b64:
            if public_key_b64 is None:
                public_key_b64 = pk_b64
            elif pk_b64 != public_key_b64:
                errors.append("Public key changed between segments")

        # Once a session presents a device key it is in evidence mode, and every
        # segment must then be signed. A *missing* signature here used to be a
        # mere warning, which let an attacker strip the signature off a forged
        # evidence segment and have it pass — a silent downgrade to
        # anonymous-looking. Fail closed: a missing signature on a signed session
        # is a hard error. A genuinely anonymous session (no key anywhere) is
        # informational, not a warning — integrity there rests on the hash chain
        # and time anchors by design.
        signing_expected = session_signed
        if sig_b64 and expected_chain:
            chain_bytes = bytes.fromhex(expected_chain)
            result = verify_signature(chain_bytes, sig_b64, pk_b64 or public_key_b64 or "")
            if result is True:
                signatures_verified += 1
                print(f"  [OK] Signature:    valid (ECDSA P-256)")
            elif result is False:
                errors.append("ECDSA signature verification FAILED")
                print(f"  [FAIL] Signature:  INVALID")
            else:
                errors.append("Signature not verified (cryptography library unavailable or key missing)")
                print(f"  [SKIP] Signature:  not verified")
        elif signing_expected:
            errors.append("Signature missing on an evidence-mode segment (device key present) — possible tampering")
            print(f"  [FAIL] Signature:  missing on a signed session")
        else:
            print(f"  [INFO] Signature:  none (anonymous mode; authenticity not established)")

        # OpenTimestamps upper time bound. The proof may carry an
        # ``openTimestamps`` field naming a ``.ots`` sidecar that anchors the
        # segment's hash to a Bitcoin block. Previously the verifier never
        # looked for ``.ots`` files at all (audit C7). If the sidecar exists,
        # try to verify it with `ots-cli`/`ots`; if the proof claims an OTS
        # anchor but no file is present, that's a warning (the anchor may have
        # been stripped in transit); if verification fails, it's a hard error.
        ots_claim = proof.get("openTimestamps") or proof.get("ots")
        if isinstance(ots_claim, dict):
            ots_name = ots_claim.get("filename") or ots_claim.get("file")
        else:
            ots_name = ots_claim if isinstance(ots_claim, str) else None
        # Default sidecar naming: <mp4 stem>.ots, alongside the segment.
        ots_candidates = []
        if ots_name:
            try:
                ots_candidates.append(safe_artifact_path(folder, ots_name))
            except ValueError as exc:
                errors.append(str(exc))
        if mp4_path and mp4_path.exists():
            ots_candidates.append(folder / (mp4_path.stem + ".ots"))
        # Detached proofs bind a digest and survive independent filename changes.
        for candidate in evidence_files(folder):
            if candidate.is_symlink() or not candidate.is_file() or candidate.suffix != '.ots':
                continue
            if candidate.is_symlink():
                continue
            with candidate.open('rb') as stream:
                header = stream.read(65)
            magic = b'\x00OpenTimestamps\x00\x00Proof\x00\xbf\x89\xe2\xe8\x84\xe8\x92\x94'
            if len(header) == 65 and header[:31] == magic and header[31:33] == b'\x01\x08' and header[33:].hex() == expected_chain:
                ots_candidates.append(candidate)
        ots_path = next((c for c in reversed(ots_candidates) if c and c.is_file()), None)
        anchor_verified = False
        if ots_path:
            if no_network:
                warnings.append('OTS verification disabled in offline mode; upper bound unverified')
            elif mp4_path and mp4_path.exists():
                result = verify_opentimestamps(str(ots_path), expected_chain)
                if result == "valid":
                    anchor_verified = True
                    print(f"  [OK] OTS:          {ots_path.name} verified against Bitcoin chain")
                elif result == "pending":
                    warnings.append(f"OTS proof {ots_path.name} is still pending confirmation")
                    print(f"  [WARN] OTS:        {ots_path.name} pending")
                elif result == "no-tool":
                    warnings.append(
                        f"OTS sidecar {ots_path.name} present but `ots-cli`/`ots` not installed "
                        f"— upper time bound NOT verified"
                    )
                    print(f"  [SKIP] OTS:        tool not installed, {ots_path.name} unverified")
                elif result == "failed":
                    errors.append(f"OTS verification FAILED for {ots_path.name}")
                    print(f"  [FAIL] OTS:        {ots_path.name} failed verification")
                else:
                    warnings.append(f"OTS verification errored for {ots_path.name}")
                    print(f"  [WARN] OTS:        {ots_path.name} verification error")
            else:
                warnings.append(f"OTS sidecar present but MP4 missing — cannot verify")
        elif ots_name:
            warnings.append(
                f"Proof references OTS file '{ots_name}' but it was not found in the folder "
                f"— upper time bound unavailable"
            )
        if require_anchored and not anchor_verified:
            errors.append('Required external timestamp was not verified')
        if proof.get('deviceAttestation'):
            warnings.append('Hardware attestation NOT validated; no camera-origin claim is established')

        ts_iso = proof.get("timestampIso", "")
        ts_unix = proof.get("timestampUnix", "")
        if ts_iso:
            print(f"  [INFO] Timestamp:  {ts_iso} (unix: {ts_unix})")

        params = proof.get("captureParams", {})
        if params:
            parts = [f"{k}={v}" for k, v in params.items()]
            print(f"  [INFO] Params:     {', '.join(parts)}")

        for w in warnings:
            print(f"  [WARN] {w}")
        for e in errors:
            print(f"  [FAIL] {e}")

        if errors:
            all_ok = False
            print(f"  RESULT: FAILED")
        else:
            print(f"  RESULT: PASSED")

        prev_chain_hash = expected_chain
        print()

    if expected_head is not None and prev_chain_hash != expected_head:
        print('[FAIL] Final head does not match the trusted checkpoint')
        all_ok = False
    if expected_count is not None and len(pairs) != expected_count:
        print('[FAIL] Segment count does not match the trusted checkpoint')
        all_ok = False
    print("=" * 72)
    if all_ok:
        print(f"OVERALL RESULT: ALL {len(pairs)} SEGMENTS VERIFIED FOR REQUESTED CHECKS")
        print()
        print('The supplied bytes match the supplied chain. This does not prove scene truth,')
        print('camera origin, exact capture time, or completeness without a trusted checkpoint.')
        print('Completeness: ' + ('matches supplied checkpoint' if expected_head and expected_count is not None else 'UNKNOWN (a valid prefix can omit a tail)'))
        if signatures_verified > 0:
            print(f"ECDSA signatures verify {signatures_verified} segment(s) against the supplied key; device identity unverified.")
        else:
            print("Unsigned chain: anyone can recompute it. Authenticity requires independently trusted anchors/checkpoints.")
    else:
        print(f"OVERALL RESULT: VERIFICATION FAILED")
        print()
        print("One or more segments failed integrity checks. The recording chain")
        print("may have been tampered with or segments may be missing/corrupted.")
    print("=" * 72)

    return 0 if all_ok else 1


def main():
    parser = argparse.ArgumentParser(
        description="Digital Witness integrity chain verifier",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__
    )
    parser.add_argument("folder", help="Folder containing .mp4 and .proof.json files")
    parser.add_argument('--require-bound-metadata', action='store_true', help='Require v4 metadata binding')
    parser.add_argument('--expected-key-sha256', help='Trusted SHA-256 fingerprint of DER signing public key')
    parser.add_argument("--session-id", help="Only verify segments for this session ID")
    parser.add_argument('--require-signed', action='store_true', help='Reject unsigned/downgraded sessions')
    parser.add_argument('--require-anchored', action='store_true', help='Require verified OTS anchors')
    parser.add_argument('--expected-head', help='Terminal chain digest obtained from a trusted external checkpoint')
    parser.add_argument('--expected-count', type=int, help='Segment count from a trusted external checkpoint')
    parser.add_argument(
        "--no-network", action="store_true",
        help="Disable drand re-fetch and OTS subprocesses; external time bounds remain unverified",
    )
    args = parser.parse_args()

    if not os.path.isdir(args.folder):
        print(f"ERROR: '{args.folder}' is not a directory", file=sys.stderr)
        sys.exit(2)

    code = verify_chain_integrity(args.folder, args.session_id, no_network=args.no_network,
                                  require_signed=args.require_signed, expected_head=args.expected_head,
                                  expected_count=args.expected_count, require_anchored=args.require_anchored,
                                  require_bound_metadata=args.require_bound_metadata, expected_key_sha256=args.expected_key_sha256)
    sys.exit(code)


if __name__ == "__main__":
    main()
