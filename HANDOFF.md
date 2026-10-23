# Source handoff

The ZIP extracts into `source/`. Its checksum file records the SHA-256 of the ZIP. Compare that checksum before extraction using `Get-FileHash .\digital-witness-individual-source.zip -Algorithm SHA256` (PowerShell), or `sha256sum digital-witness-individual-source.zip` (Linux).

After extraction, open a terminal in `source/` and run:

```text
python tools/prepare_handoff.py verify --source .
```

Run this before building or adding configuration: it checks the exact file set and each file digest against `HANDOFF_MANIFEST.json`. A successful check confirms agreement with that manifest; it does not establish who sent the archive. The outer checksum is useful only if received through a trusted channel.

`android-app/` contains the complete application and Gradle wrapper; `tools/` contains the Python verifier, export utilities and regression harnesses; `docs/schemas/` contains evidence schemas; `verifier/` contains an additional Go verifier; `.github/` contains CI; `metadata/` contains a disabled draft listing. Use the Python verifier for the current complete proof checks. The Go verifier is an auxiliary implementation and does not implement all Python checks.

For a new installation follow [BUILD](docs/BUILD.md). For an update retain the same application ID and signing key and use a higher version code when needed. A different signing key cannot update an installed app. Do not uninstall a device with pending evidence to resolve a signature mismatch.

No credentials, PIN lists, signing certificates from a deployment, recordings, APKs, local SDK paths, logs, IDE state or Git history are included. Synthetic test fixtures and public protocol certificates remain because they are required for testing or network validation. The project name, package ID and legally required attribution remain; this is a cleaned source distribution, not a guarantee of unlinkability.
