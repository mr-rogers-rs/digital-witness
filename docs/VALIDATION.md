# Validation

Use Python 3.12 and install `tools/requirements.txt` in a virtual environment. Build the APKs and run lint as described in [BUILD](BUILD.md). From `source/`:

```text
python -m unittest discover -s tools -p "test_*.py"
python tools/test_verify_chain.py
python tools/check_localizations.py
```

Git must be on PATH for exporter tests, even when this archive has no repository. Those tests create temporary synthetic repositories. One symlink test may skip on Windows without symlink privileges. Never put test output into an archive intended for another recipient.

Compiled production-code regressions run after the debug build. See `.github/workflows/ci.yml` for exact dependency versions and commands. Set `JAVA_HOME` to JDK 17. Use the Android Platform 34 `android.jar` and Gradle's cached Kotlin stdlib 1.9.24 JAR for `run_jvm_security_tests.py` and `run_jvm_pin_tests.py`. `run_reliability_tests.py --variant debug` and `run_upload_drain_tests.py` also require `--json-jar` pointing to the public `org.json:json:20240303` JAR; the CI workflow records and verifies its checksum. Run each tool with `--help` for the required path arguments.

The host harnesses use synthetic keys/files and Android/media/provider adapters to test crypto formats, long audio clocks, A/V timestamp handling, bitrate policy, journaling/recovery, queue persistence and delivery cancellation. They cannot validate a real camera, Android Keystore security or cloud receipt. The instrumentation runner is a synthetic media regression helper, not a production UI test; inspect its source before installing/running it on a test device.

Before distributing a build: record at least twenty minutes on battery with the screen locked, stop explicitly, and play early/middle/final downloaded segments in a separate player. Check synchronized sound and picture, silent recording, preview orientation, torch before/during capture and the final segment. Check queued evidence while offline, reconnect, expired credentials, low storage, forced interruption and recovery. Verify your selected destination and confidentiality mode. Repeat on representative device models/Android versions. Review all warnings; don't infer a cloud receipt from a document-provider copy alone.

For downloaded, readable media and proofs run `python tools/verify_chain.py /path/to/evidence --require-signed --require-bound-metadata`. An externally trusted signing-key fingerprint, terminal digest and segment count can additionally detect key substitution or truncation. See `--help`. `--no-network` leaves external time bounds unverified. Require anchoring only after anchors have completed.
