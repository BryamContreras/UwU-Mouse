# Build and continuous integration

The [Android APKs workflow](../.github/workflows/android-apks.yml) runs for pushes to `main`, pull requests to `main`, and manual dispatch. It checks architecture, dependencies, Flutter analysis and tests, builds release APKs by ABI, runs Android JVM tests and lint, checks the compiled JSON ABI and native capture policy, and validates native library alignment for 16 KB pages.

Each successful release build produces separate **arm64-v8a**, **armeabi-v7a** and **x86_64** development APK artifacts and a SHA-256 checksum file. CI verifies the application ID, version, signature, included ABI and required native libraries. Main and manually dispatched builds use the repository's configured development signing secret; pull requests without that secret use an ephemeral certificate and cannot update an installation signed by the persistent key.

The [Android 14–17 compatibility workflow](../.github/workflows/android-compatibility.yml) builds one x86_64 application/instrumentation APK pair and uses that same pair for twelve framework tests on Android API 34, 35, 36 and 37. The suite includes the idle-hover, retired-display and repeated WindowManager summary formats observed on a physical Android 17 device, including rejection of pressed pointers. These emulator tests do not certify every device, game or peripheral.

The shared toolchain is pinned in [android/toolchain.properties](../android/toolchain.properties), and the Android test/lint tasks used locally and in CI are listed in [tools/android-check-tasks.txt](../tools/android-check-tasks.txt). On Windows, `scripts/Build.ps1 -Release` runs the local release checks and build. The full [CI and signing guide](CI_ANDROID.md) is available in Spanish.
