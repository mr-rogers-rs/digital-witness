# Build and install

Run commands from the extracted `source/` directory unless instructed otherwise. Install JDK 17, Android SDK Platform 34, Build-Tools 34.0.0 and Platform-Tools. The application targets API 34 and runs on API 26+. Check current distribution-store requirements separately before submitting to a store.

Set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to your own SDK directory. Alternatively create `android-app/local.properties` containing `sdk.dir=` followed by your SDK path (use forward slashes on Windows). This local file must stay private. Ensure the Android SDK licenses are accepted. Gradle and Maven dependencies are downloaded on the first build; dependency lock files are included.

On Windows PowerShell:

```powershell
Set-Location android-app
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease --console=plain --max-workers=2
Set-Location ..
```

On Linux/macOS replace `.\gradlew.bat` with `./gradlew`; make `android-app/gradlew` executable if your ZIP extractor did not preserve executable permissions.

The debug APK is `android-app/app/build/outputs/apk/debug/app-debug.apk`. It is signed using the builder's automatically generated local Android debug key. The release APK is `android-app/app/build/outputs/apk/release/app-release-unsigned.apk` when no release signing configuration exists. An unsigned APK cannot be installed.

For an independently signed release, create and securely back up your own keystore outside the source directory. Example (one line; passwords are prompted):

```text
keytool -genkeypair -keystore /absolute/private/path/release.jks -alias release -keyalg RSA -keysize 4096 -validity 10000
```

Enter the certificate identity appropriate for your distribution. Copy `android-app/app/keystore.properties.example` to `android-app/app/keystore.properties`. Fill in your own passwords, alias and absolute keystore path (forward slashes on Windows). Relative `storeFile` paths resolve from `android-app/`, not from the workspace root. Rebuild `:app:assembleRelease`. The signed output is `android-app/app/build/outputs/apk/release/app-release.apk`. Keep the keystore, passwords and filled properties file out of source archives and public repositories.

Enable USB debugging on the test phone, connect it and authorize your computer. With recording stopped:

```text
adb devices
adb install -r android-app/app/build/outputs/apk/debug/app-debug.apk
```

Use your signed release path for a release installation. `-r` preserves app data if Android accepts the signature and version. Never use a debug build as the public release. Follow [VALIDATION](VALIDATION.md) before distributing your own build. Inspect relevant lint warnings as well as errors.
