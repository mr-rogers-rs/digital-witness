#!/usr/bin/env bash
# Reproducible-build verifier for Digital Witness.
#
# Builds the release APK from the current source and prints its SHA-256, so it can
# be compared against a published reference hash (see docs/reproducible_builds.md).
# Optionally compares against a published APK *ignoring the signing block*.
#
# IMPORTANT: a matching hash only means anything if you built with the SAME pinned
# toolchain — especially the SAME Android build-tools version (see §1 of the doc).
#
# Usage:
#   tools/verify_apk.sh                         # build + print unsigned-APK sha256
#   tools/verify_apk.sh --expect <sha256>       # build + assert it equals <sha256>
#   tools/verify_apk.sh --compare <published.apk>  # build + diff ignoring signatures
#
# Requires: a configured Android SDK + JDK 17. For --compare: python3 + unzip.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/android-app"
OUT="$APP/app/build/outputs/apk/release/app-release-unsigned.apk"

EXPECT=""
COMPARE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --expect) EXPECT="$2"; shift 2;;
    --compare) COMPARE="$2"; shift 2;;
    *) echo "unknown arg: $1" >&2; exit 2;;
  esac
done

echo ">> Building release (clean) ..."
( cd "$APP" && ./gradlew clean assembleRelease )

if [ ! -f "$OUT" ]; then
  echo "ERROR: expected unsigned APK not found at $OUT" >&2
  echo "       (if you added a release signingConfig, compare the signed APK with --compare)" >&2
  exit 1
fi

HASH="$(sha256sum "$OUT" | awk '{print $1}')"
echo ">> Built: $OUT"
echo ">> SHA-256: $HASH"

if [ -n "$EXPECT" ]; then
  if [ "$HASH" = "$EXPECT" ]; then
    echo ">> MATCH: reproduces the expected hash."
  else
    echo ">> MISMATCH: expected $EXPECT" >&2
    echo "   Check you used the same toolchain, especially build-tools version." >&2
    exit 1
  fi
fi

if [ -n "$COMPARE" ]; then
  echo ">> Comparing against $COMPARE (ignoring signature entries) ..."
  python3 - "$OUT" "$COMPARE" <<'PY'
import sys, zipfile, hashlib
def entries(path):
    out = {}
    with zipfile.ZipFile(path) as z:
        for n in z.namelist():
            # Skip the v1 signature files; the v2/v3 signing block lives outside
            # the zip entries and is not seen here.
            if n.startswith("META-INF/") and n.rsplit(".",1)[-1] in ("SF","RSA","EC","DSA"):
                continue
            out[n] = hashlib.sha256(z.read(n)).hexdigest()
    return out
a, b = entries(sys.argv[1]), entries(sys.argv[2])
only_a = sorted(set(a) - set(b)); only_b = sorted(set(b) - set(a))
diff = sorted(n for n in (set(a) & set(b)) if a[n] != b[n])
if not only_a and not only_b and not diff:
    print(">> MATCH: identical content (ignoring signatures).")
else:
    if only_a: print("   only in your build:", only_a)
    if only_b: print("   only in published :", only_b)
    if diff:   print("   differing entries  :", diff)
    print(">> MISMATCH.")
    sys.exit(1)
PY
fi
