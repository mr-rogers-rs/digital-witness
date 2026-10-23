"""Exercise compiled production Kotlin and independently decrypt JWE with Python cryptography.
Requires JDK17, an Android debug build, Android SDK, and Gradle's cached Kotlin stdlib.
The in-memory test Keystore validates file format only, NOT Android hardware security.
"""
import hashlib
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from recipient_export import decrypt

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--android-jar", type=Path, required=True)
parser.add_argument("--stdlib", type=Path, required=True)
args = parser.parse_args()
java_home = Path(os.environ["JAVA_HOME"])
classes = root / "android-app/app/build/tmp/kotlin-classes/debug"
with tempfile.TemporaryDirectory(prefix="dw-jvm-") as tmp:
    folder = Path(tmp)
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    (folder/"public.pem").write_bytes(key.public_key().public_bytes(
        serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo))
    # Production wipe controller needs Android services. Stub only its read-only gate;
    # all format and crypto operations execute unchanged production Kotlin bytecode.
    stub = folder/"PanicWipe.java"
    stub.write_text("package com.fieldnotes.recorder.util; public class PanicWipe { "
        "public static final PanicWipe INSTANCE = new PanicWipe(); "
        "public static boolean getWipeInProgress() { return false; } }")
    cp = os.pathsep.join(map(str,(folder,classes,args.stdlib,args.android_jar)))
    subprocess.run([str(java_home/"bin/javac"),"-cp",cp,"-d",str(folder),str(stub),
                    str(root/"tools/JvmSecurityHarness.java")],check=True)
    subprocess.run([str(java_home/"bin/java"),"-cp",cp,"JvmSecurityHarness",str(folder)],check=True)
    outputs = folder/"decrypted"
    for artifact in folder.glob("*.jwe"):
        output = outputs/artifact.name.removesuffix(".jwe")
        decrypt(artifact,output,key)
        assert output.read_bytes() == (folder/output.name).read_bytes()
        content = output.read_bytes()
        expected = hashlib.sha256(b"".join(hashlib.sha256(content[i:i+4194304]).digest()
            for i in range(0,len(content),4194304))).hexdigest()
        assert (folder/(output.stem+".hash")).read_text() == expected
    source = folder/"65537.bin.jwe"
    original = source.read_bytes()
    for i, contents in enumerate((original[:-1], original+b"x",
                                  original[:100]+bytes([original[100]^1])+original[101:],
                                  original[:-2]+b"AA")):
        bad = folder/("bad"+str(i)+".jwe"); bad.write_bytes(contents)
        destination = outputs/("bad"+str(i))
        try:
            decrypt(bad,destination,key)
            raise AssertionError("Corrupt envelope accepted")
        except Exception as exc:
            if isinstance(exc, AssertionError): raise
        assert not destination.exists()
    wrong = rsa.generate_private_key(public_exponent=65537,key_size=2048)
    try:
        decrypt(source,outputs/"wrong",wrong)
        raise AssertionError("Wrong recipient accepted")
    except ValueError: pass
    assert not (outputs/"wrong").exists()
    assert not list(outputs.glob(".dw-decrypt-*"))
    print("13 Kotlin-to-Python JWE round trips; 4 corruption cases and wrong recipient rejected.")
