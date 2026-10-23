"""Test compiled production PIN verification with synthetic settings and independent hash fixtures.

No phone or real credentials are accessed. Requires an Android debug build and JDK 17.
"""
import argparse
import base64
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--android-jar", type=Path, required=True)
parser.add_argument("--stdlib", type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
java = Path(os.environ["JAVA_HOME"]) / "bin"
classes = root / "android-app/app/build/tmp/kotlin-classes/debug"
resources = root / "android-app/app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar"
with tempfile.TemporaryDirectory(prefix="dw-pin-") as tmp:
    folder = Path(tmp)
    # Only host adapters are replaced; the Prefs bytecode is the app's actual build.
    context = folder / "Context.java"
    context.write_text("package android.content; public class Context {}")
    base64_stub = folder / "Base64.java"
    base64_stub.write_text(
        "package android.util; public class Base64 { "
        "public static boolean forbidDecode; "
        "public static byte[] decode(String text, int flags) { "
        "if (forbidDecode) throw new AssertionError(\"Blank PIN reached hash processing\"); "
        "return java.util.Base64.getDecoder().decode(text); } }")
    document_stub = folder / "DocumentFile.java"
    document_stub.write_text(
        "package androidx.documentfile.provider; public abstract class DocumentFile { "
        "public abstract boolean isDirectory(); public abstract boolean canRead(); "
        "public abstract boolean canWrite(); public abstract DocumentFile findFile(String name); "
        "public abstract DocumentFile createDirectory(String name); }")
    salt = bytes(range(16))
    for version, cost in (("v1", 100000), ("v2", 600000)):
        expected = hashlib.pbkdf2_hmac("sha256", b"7391", salt, cost, 32)
        (folder / (version + ".txt")).write_text(base64.b64encode(salt + expected).decode())
    cp = os.pathsep.join(map(str, (folder, classes, resources, args.stdlib, args.android_jar)))
    subprocess.run([str(java / "javac"), "-cp", cp, "-d", str(folder), str(context),
                    str(base64_stub), str(document_stub), str(root / "tools/PinVerificationHarness.java"),
                    str(root / "tools/CaptureStatusHarness.java"),
                    str(root / "tools/SegmentTimelineHarness.java"),
                    str(root / "tools/AudioSampleClockHarness.java"),
                    str(root / "tools/AdaptiveBitrateHarness.java"),
                    str(root / "tools/ResolutionPolicyHarness.java"),
                    str(root / "tools/FrameMeasurementsHarness.java"),
                    str(root / "tools/NativeCaptureHarness.java"),
                    str(root / "tools/EvidenceLayoutHarness.java"),
                    str(root / "tools/SafEvidenceFolderHarness.java")], check=True)
    subprocess.run([str(java / "java"), "-cp", cp, "PinVerificationHarness", str(folder)], check=True)
    subprocess.run([str(java / "java"), "-cp", cp, "CaptureStatusHarness"], check=True)
    subprocess.run([str(java / "java"), "-cp", cp, "SegmentTimelineHarness"], check=True)
    subprocess.run([str(java / "java"), "-cp", cp, "AudioSampleClockHarness"], check=True)
    subprocess.run([str(java / "java"), "-cp", cp, "AdaptiveBitrateHarness"], check=True)
    subprocess.run([str(java / "java"), "-cp", cp, "ResolutionPolicyHarness"], check=True)
    subprocess.run([str(java / "java"), "-cp", cp, "FrameMeasurementsHarness"], check=True)
    subprocess.run([str(java / "java"), "-cp", cp, "NativeCaptureHarness"], check=True)

    subprocess.run([str(java / "java"), "-cp", cp, "EvidenceLayoutHarness"], check=True)

    subprocess.run([str(java / "java"), "-cp", cp, "SafEvidenceFolderHarness"], check=True)
