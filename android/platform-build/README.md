# Platform architecture checks

This standalone Gradle build validates the actual Android modules and the host
Kotlin, Java, AIDL and resources without applying the Flutter Gradle plugin. It
does not change the project's Flutter/Dart pins or dependency lockfiles.

Run from the repository root on Windows:

```powershell
.\scripts\Check-Architecture.ps1
# An already installed Gradle distribution can be selected explicitly:
.\scripts\Check-Architecture.ps1 -GradlePath C:\path\to\gradle.bat
```

The default uses `android/gradlew.bat` and its pinned distribution. The script
locates Android SDK and Android Studio's JBR when the standard environment
variables are absent. `-AndroidSdk`, `-FlutterRoot` and `-GradlePath` allow explicit
tool locations. A cached Flutter embedding jar is required to type-check the
existing host Activity; it does not compile Dart or validate Flutter engine ABI.

The included projects are the real `mapper-core`, `capture-api`, `capture-native`,
`injection-api`, `injection-runtime`, `presentation`, `activation-transport`,
`window-context`, `backend-host` and `inputlab`. `host` reads the existing app sources and manifest,
retains the original resource namespace and uses a separate validation app ID.
Its dependency graph compiles the real native-capture adapter transitively.
`-PincludeLatencyProbe=true` includes the separate receive/draw experiment.

The check runs core, capture API, window-context, activation transport, host and
InputLab JVM tests and compiles host Kotlin. It also verifies that the engine
does not import capture/injection or Android adapters, presentation does not
depend on app or accessibility services, and the app manifest contains no
accessibility service. BuildTools 36.0.0 is explicit; a local SDK aapt2 is selected
by the script when available.

Outputs live under `build/platform/`. The script writes its log and XML-test
summary to ignored `evidence/architecture/`. A failed integration build stays
failed even when some independent test modules have passed. Existing XML reports
are labeled with timestamps rather than presented as proof of a fresh test run.

This is **not a distributable mapper APK**. It omits Dart assets, Flutter build
integration and production packaging/signing checks. It does not replace the
normal pinned Flutter build, full APK validation or physical device trials.
