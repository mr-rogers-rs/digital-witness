#!/usr/bin/env python3
"""Generate recipient RSA keys or decrypt Digital Witness compact JWE exports offline.
Private keys stay on the recipient computer. Requires cryptography.
"""
import argparse
import base64
import getpass
import json
import os
from pathlib import Path
import re
import tempfile
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa, padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes


def unb64(value):
    if not re.fullmatch(rb"[A-Za-z0-9_-]*", value):
        raise ValueError("Invalid base64url")
    return base64.b64decode(value + b"=" * (-len(value) % 4), altchars=b"-_", validate=True)


def keygen(directory, password):
    if len(password) < 12:
        raise ValueError("Use a private-key passphrase of at least 12 characters")
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    private_path, public_path = directory / "recipient-private.pem", directory / "recipient-public.pem"
    if private_path.exists() or public_path.exists():
        raise ValueError("Key files already exist; refusing to overwrite")
    key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
    with private_path.open("xb") as f:
        os.chmod(private_path, 0o600)
        f.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                serialization.BestAvailableEncryption(password.encode())))
    with public_path.open("xb") as f:
        f.write(key.public_key().public_bytes(serialization.Encoding.PEM,
                                             serialization.PublicFormat.SubjectPublicKeyInfo))
    return private_path, public_path


def decrypt(source, destination, private_key):
    """Streaming authenticated decrypt. Never publish output before GCM authentication."""
    source, destination = Path(source), Path(destination)
    if destination.exists():
        raise ValueError("Output already exists; refusing to overwrite")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temp_path = None
    try:
        with source.open("rb") as src:
            def field(limit):
                value = bytearray()
                while len(value) <= limit:
                    b = src.read(1)
                    if b == b".":
                        return bytes(value)
                    if not b:
                        raise ValueError("Truncated JWE header")
                    value.extend(b)
                raise ValueError("JWE field too long")
            protected = field(1024)
            header = json.loads(unb64(protected))
            if header != {"alg": "RSA-OAEP-256", "enc": "A256GCM"}:
                raise ValueError("Unsupported JWE header")
            wrapped, iv = unb64(field(2048)), unb64(field(32))
            if len(iv) != 12:
                raise ValueError("Invalid GCM nonce")
            cek = private_key.decrypt(wrapped, padding.OAEP(mgf=padding.MGF1(hashes.SHA256()),
                                                           algorithm=hashes.SHA256(), label=None))
            if len(cek) != 32:
                raise ValueError("Invalid content key")
            decryptor = Cipher(algorithms.AES(cek), modes.GCM(iv)).decryptor()
            decryptor.authenticate_additional_data(protected)
            fd, temporary = tempfile.mkstemp(prefix=".dw-decrypt-", dir=destination.parent)
            temp_path = Path(temporary)
            with os.fdopen(fd, "wb") as out:
                pending = b""
                size = 0
                while True:
                    block = src.read(65536)
                    if not block:
                        raise ValueError("Missing authentication tag")
                    if b"." in block:
                        cipher_end, tag_start = block.split(b".", 1)
                        pending += cipher_end
                        out.write(decryptor.update(unb64(pending)))
                        tag_encoded = tag_start + src.read(24)
                        if len(tag_encoded) != 22 or src.read(1):
                            raise ValueError("Invalid/trailing authentication tag")
                        tag = unb64(tag_encoded)
                        if len(tag) != 16:
                            raise ValueError("Invalid GCM tag")
                        out.write(decryptor.finalize_with_tag(tag))
                        break
                    pending += block
                    whole = len(pending) // 4 * 4
                    decoded = unb64(pending[:whole])
                    size += len(decoded)
                    if size > (1 << 34):
                        raise ValueError("Export exceeds 16 GiB")
                    out.write(decryptor.update(decoded))
                    pending = pending[whole:]
                out.flush()
                os.fsync(out.fileno())
        # Exclusive output creation prevents races from replacing another artifact.
        # Temp remains private until authentication; copy is streaming on Windows too.
        with temp_path.open("rb") as src, destination.open("xb") as out:
            import shutil
            shutil.copyfileobj(src, out, 65536)
            out.flush()
            os.fsync(out.fileno())
    finally:
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    gen = sub.add_parser("keygen")
    gen.add_argument("directory", type=Path)
    dec = sub.add_parser("decrypt")
    dec.add_argument("--private-key", type=Path, required=True)
    dec.add_argument("--output-dir", type=Path, required=True)
    dec.add_argument("files", type=Path, nargs="+")
    args = parser.parse_args()
    try:
        password = getpass.getpass("Recipient private-key passphrase: ")
        if args.command == "keygen":
            if password != getpass.getpass("Repeat passphrase: "):
                raise ValueError("Passphrases differ")
            _, public = keygen(args.directory, password)
            print(f"Paste only {public} into the recording phone.")
        else:
            key = serialization.load_pem_private_key(args.private_key.read_bytes(),
                                                     password=password.encode() if password else None)
            if not isinstance(key, rsa.RSAPrivateKey):
                raise ValueError("RSA private key required")
            for source in args.files:
                if source.suffix != ".jwe":
                    raise ValueError("Expected a .jwe file")
                destination = args.output_dir / source.name.removesuffix(".jwe")
                decrypt(source, destination, key)
                print(f"Authenticated and decrypted: {destination.name}")
    except Exception as exc:
        parser.exit(1, f"Export operation failed: {type(exc).__name__}: {exc}\n")


if __name__ == "__main__":
    main()
