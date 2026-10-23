# Digital Witness ProGuard / R8 rules

# ============================================================
# SECURITY: Strip all Android log calls from release builds.
# Prevents forensic extraction of logcat entries that reveal
# the app's true identity (DW-* tags defeat calculator disguise).
# ============================================================
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# ============================================================
# OBFUSCATION: Repackage all classes into the root package
# to eliminate readable package structure in decompiled output.
# ============================================================
-repackageclasses ''
-allowaccessmodification

# Strip source file names from stack traces (prevents leaking
# original file names like "CryptoEngine.kt" in crash reports).
-renamesourcefileattribute ''

# ============================================================
# KEEP RULES: Preserve classes accessed via reflection
# ============================================================

# Keep UploadWorker — WorkManager DOES instantiate workers by class name via
# reflection, so this one is load-bearing. Note the side effect: its `TAG`
# constant ("DW-UploadWorker") therefore survives in the dex as a cleartext
# string. R8 obfuscates names, never string literals.
-keep class com.fieldnotes.recorder.upload.UploadWorker { *; }
# Preserve names stored by existing WorkManager jobs across in-place upgrades.
-keep class com.fieldnotes.recorder.upload.TimestampWorker { *; }
-keep class com.fieldnotes.recorder.capture.CaptureRecoveryWorker { *; }

# Keep crypto engine methods called from integrity layer.
#
# NOTE: like the cloud/network keeps removed below, this has no reflection
# behind it — CryptoEngine is a plain object called directly. It is retained
# for now only because it is the most security-critical class in the app and
# nobody has confirmed on-device that R8 handles it cleanly. Dropping it would
# remove the readable `Lcom/fieldnotes/recorder/crypto/CryptoEngine;` descriptor
# from the release dex; do it together with a device test of signing,
# attestation, and at-rest encrypt/decrypt.
-keep class com.fieldnotes.recorder.crypto.CryptoEngine { *; }

# ── REMOVED (audit C3) ──────────────────────────────────────────────────────
# The following two wildcard keeps were deleted:
#
#   -keep class com.fieldnotes.recorder.cloud.**   { *; }   # "instantiated by name"
#   -keep class com.fieldnotes.recorder.network.** { *; }   # "used across modules"
#
# Both justifications were wrong. There is no reflection anywhere in either
# package: CloudUploadManager selects a provider with a `when` over a string
# and calls the constructor directly, and "used across modules" is ordinary
# static linkage that R8 resolves without help.
#
# The cost of the mistake was concrete. Decompiling the release APK showed the
# entire upload layer surviving R8 verbatim, closure names and all —
#     Lcom/fieldnotes/recorder/cloud/GoogleDriveProvider$TokenCallResult$Unauthorized;
#     Lcom/fieldnotes/recorder/cloud/DropboxProvider$uploadSmallFile$response$1;
#     Lcom/fieldnotes/recorder/network/PinnedHttpClient$socksRemoteDns$1;
# — a readable architectural map of exactly the subsystem a forensic analyst
# cares about, while unkept classes (PanicWipe, ProofManager) obfuscated to
# nothing. That contrast is the proof the keeps were breadth, not necessity.
#
# ⚠️ R8 breakage from a removed keep surfaces at RUNTIME, not build time.
# Smoke-test every upload provider (Nextcloud chunked + resume, WebDAV,
# Dropbox, Google Drive) and the Tor path on a device against a release build
# before relying on this.

# Keep OkHttp internals (platform detection uses reflection)
-dontwarn okhttp3.internal.platform.**
-keep class okhttp3.internal.platform.** { *; }
