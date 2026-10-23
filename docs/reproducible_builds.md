# Build comparison

Use [BUILD](BUILD.md) for the pinned SDK, JDK and build commands. Gradle dependency locks and wrapper are included. The release configuration disables embedded VCS and dependency metadata. These controls aid comparison; byte-identical output is not guaranteed across unqualified toolchains. Compare unsigned outputs under the same toolchain, and inspect ZIP entry differences. Different signing keys necessarily produce different APKs. Never compare against a hash from an unrelated deployment.
