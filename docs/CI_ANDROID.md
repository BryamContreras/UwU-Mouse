# APK por arquitectura

El workflow [Android APKs](../.github/workflows/android-apks.yml) se ejecuta al enviar cambios a `main`, abrir o actualizar un pull request hacia `main`, o pulsar **Actions → Android APKs → Run workflow**. Requiere un runner de GitHub y conexión para descargar el SDK y las dependencias. Para los builds de `main` o manuales se configura una vez el secreto de firma descrito abajo; no necesita un teléfono.

Valida arquitectura, análisis y pruebas de Flutter, construye con `flutter build apk --release --split-per-abi --android-project-arg=force-version-code-ignoring-abi=true`, ejecuta pruebas JVM de todos los módulos de producción y de InputLab, y aplica lint a cada módulo Android. La lista compartida [android-check-tasks.txt](../tools/android-check-tasks.txt) se usa tanto en CI como en [Build.ps1](../scripts/Build.ps1). Cada APK contiene únicamente su ABI; se exige que incluya `libapp.so`, `libflutter.so`, `libuwu_capture.so` y `libuwu_adb.so`. `aapt` comprueba el versionCode. [Comando documentado por Flutter](https://docs.flutter.dev/deployment/android#build-an-apk).

| Artifact | Arquitectura |
|---|---|
| `UwU-Mouse-<versión>-arm64-v8a-dev` | ARM de 64 bits, incluido el Samsung de prueba |
| `UwU-Mouse-<versión>-armeabi-v7a-dev` | ARM de 32 bits |
| `UwU-Mouse-<versión>-x86_64-dev` | x86 de 64 bits |
| `UwU-Mouse-<versión>-checksums` | SHA-256 de los tres APK |

Cuando la ejecución termine, abre su sección **Artifacts** y descarga el ZIP de tu arquitectura. Cada ZIP contiene el APK con la versión de `pubspec.yaml`. Los APK y sumas se conservan 14 días; los informes de prueba/lint de todos los módulos, 7 días, incluso si una comprobación posterior falla. GitHub aplica además el límite de retención del repositorio. [Artifacts de GitHub](https://docs.github.com/en/actions/tutorials/store-and-share-data).

## Base reproducible

- Ubuntu 24.04, Temurin JDK 17 y bytecode Java/Kotlin 17.
- Flutter **3.47.9**, revisión `c197ca5199e9ee5661e865596c9c07294dfab51c`, Dart **3.13.7**.
- Android compile/target SDK **37**, mínimo **34**; paquete de SDK `platforms;android-37.0`, Build Tools **37.0.0** y Command-line Tools **23.0**.
- AGP **9.3.3**, Kotlin **2.4.21**, Gradle **9.5.0**, NDK LTS **30.0.16248370** y CMake **4.1.2**.

[android/toolchain.properties](../android/toolchain.properties) es la fuente compartida para los tres builds Gradle, los módulos Android, los scripts locales y CI. `.flutter-version`, la restricción Dart y el wrapper se comprueban mediante [check_toolchain.py](../tools/check_toolchain.py). Los scripts también comprueban la versión y revisión del Flutter instalado antes de compilar. No hay sabores por fabricante ni APK diferentes para S22/S25: cada ABI usa el mismo programa y nivel mínimo Android 14.

La selección usa versiones estables compatibles entre sí. La [matriz de Kotlin 2.4.21](https://kotlinlang.org/docs/gradle-configure-project.html) cubre Gradle hasta 9.7.0 y AGP hasta 9.3.1; se incluye el parche de la misma rama [AGP 9.3.3](https://developer.android.com/build/releases/agp-9-3-0-release-notes), que corrige fallos D8/R8 y mantiene el requisito Gradle 9.5.0 o superior. Se fija Gradle 9.5.0, el mínimo recomendado de AGP 9.3 y compatible con las herramientas de Flutter 3.47. AGP 9.5 y Gradle 9.9 son posteriores y quedan fuera de esa combinación documentada. Flutter 3.47 admite [Kotlin integrado de AGP 9](https://docs.flutter.dev/release/breaking-changes/migrate-to-built-in-kotlin): todos los módulos Android migraron a él; `android.newDsl=false` conserva la interfaz Gradle que Flutter todavía requiere, según su guía oficial.

El NDK se fija explícitamente a [r30 LTS](https://developer.android.com/ndk/downloads), aunque Flutter 3.47.9 y AGP 9.3 ofrecen r28c como valor predeterminado. Usar una versión superior es una configuración admitida; evita seguir compilando con una rama NDK antigua y permite verificar las bibliotecas nativas actuales. CMake 4.1.2, Build Tools 37.0.0 y Command-line Tools 23.0 son paquetes estables del [repositorio oficial Android SDK](https://dl.google.com/android/repository/repository2-3.xml); las versiones de CMake se seleccionan explícitamente como describe la [documentación de Android](https://developer.android.com/studio/projects/install-ndk). Los mínimos CMake declarados por las dos bibliotecas siguen describiendo las funciones que necesita su código.

Las acciones están fijadas a SHA completos de sus repositorios, con la versión mayor como comentario. El SDK Linux 3.47.9 está publicado en el [manifiesto oficial Flutter](https://storage.googleapis.com/flutter_infra_release/releases/releases_linux.json), con SHA-256 `25ed8c3c29d51a7353f2a944310b478af8214920639524b19c50a19e6c044c5a`. El workflow comprueba la versión y revisión exactas después de instalarlo. [Configuración del mantenedor de flutter-action](https://github.com/subosito/flutter-action#specifying-flutter-version).

Las dependencias de activación usan Conscrypt **2.9.0**, Bouncy Castle **1.86** y el Prefab BoringSSL **20251124**, todas fijadas en la configuración compartida. Conscrypt se obtiene del [artefacto publicado por Google](https://repo.maven.apache.org/maven2/org/conscrypt/conscrypt-android/maven-metadata.xml); Bouncy Castle 1.86 incorpora [correcciones y endurecimiento](https://www.bouncycastle.org/download/bouncy-castle-java/). BoringSSL 20251124 es la [última versión estándar publicada por el proveedor del Prefab](https://github.com/vvb2060/BoringSSL_Android); se elige la variante sin LTO para no vincular bitcode creado por otro NDK. Ese Prefab no equivale al último código fuente de BoringSSL upstream. Annotation **1.11.0**, Activity **1.13.0** (host de validación) y AndroidX Test Runner **1.7.0** / JUnit **1.3.0** completan las dependencias Android estables. Las bibliotecas JVM JSON y JUnit 4 conservan su contrato de prueba; la primera se comprueba además contra el ABI Android real.

El SDK real indicado arriba y `flutter pub get --enforce-lockfile` son obligatorios para validar el APK completo. Una compilación local con el host `android/platform-build` o una copia de previsualización que use otro Flutter sólo demuestra el alcance de esa comprobación; no sustituye el build de CI. El workflow funciona desde un checkout limpio y no lee `evidence/`, perfiles del teléfono ni configuraciones de la PC. El cache de Gradle se invalida con los scripts y propiedades de los módulos, además del wrapper.

## Firma y actualizaciones

Son APK de desarrollo compilados en variante release. Los builds de `main` y manuales restauran la clave debug de desarrollo desde el secreto **ANDROID_DEBUG_KEYSTORE_BASE64**, que debe contener el keystore actual codificado en base64. La ruta exacta se entrega a Gradle mediante **UWU_ANDROID_DEBUG_KEYSTORE**; se conserva la configuración debug local si esa variable no existe. Antes de publicar se compara el certificado de cada APK con el exportado del keystore y con su huella pública fijada, por lo que restaurar un archivo no basta para aprobar la firma. Así pueden actualizar la instalación local con el mismo certificado. El workflow falla con una indicación concreta si falta el secreto en esos eventos. La clave no se almacena en Git, artifacts ni logs; se escribe solo en el directorio privado del runner.

La huella pública admitida es `940a68a15fb6e37f42b0efcc7d175ea79e31e13f7e9995a50e9d6117c02e5bc6`. Crear otra clave o conocer esa huella no permite reconstruir la clave privada original. Para actualizar una instalación existente se necesita el keystore correspondiente, conservado por el propietario o en el secreto ya configurado; no se cambia la firma para resolver un fallo del workflow. Una instalación de laboratorio firmada con otra clave requiere su propia actualización compatible.

Los pull requests que no reciben secretos, incluidos los de forks, pueden validar y compilar con una clave debug efímera. Sus artifacts sirven para pruebas y pueden tener una firma distinta. No se usan privilegios de escritura ni `pull_request_target` para esos builds. Esta clave sigue siendo de desarrollo, no una clave de distribución Play. [Firma de aplicaciones Android](https://docs.flutter.dev/deployment/android#sign-the-app).

El nombre visible es **UwU Mouse**. Se conserva `dev.mobimapper.mobimapper` como applicationId y las autoridades/componentes existentes para preservar los datos y autorizaciones de las instalaciones con la misma firma. Se desactiva explícitamente el desplazamiento de versionCode por ABI que Flutter aplica de forma predeterminada: los tres APK mantienen el build number de `pubspec.yaml`, igual que el APK universal local. Así se pueden alternar actualizaciones locales y CI con el mismo certificado sin saltos artificiales de versión. Es distribución directa de desarrollo, no un conjunto multi-APK para Play.

CI ejecuta también el checker de arquitectura y las pruebas de los módulos de dominio, captura, ventanas, transporte de activación e inyección. Los builds CI no prueban adquisición exclusiva de periféricos, recuperación en un teléfono ni compatibilidad de un juego. Esas observaciones se registran por separado. Las capturas, inventarios, perfiles y logs del dispositivo permanecen locales en `evidence/` y no forman parte del repositorio público. [Arquitectura de módulos](../ARCHITECTURE.md).

`tools/test_capture_policy.py` compila y ejecuta las pruebas C++17 de la política de captura en el runner, sin teléfono. Localmente se añade `-NativePolicyTests` a `scripts/Build.ps1` si hay un compilador C++ disponible. Los módulos opcionales `latencyprobe` y el host de validación no son dependencias de la aplicación distribuida.

Después de compilar, `tools/check_android_json_abi.py` inspecciona las referencias de
métodos del bytecode de producción de `mapper-core`. Rechaza las sobrecargas
`JSONObject.put(String,float)`, `JSONArray.put(float)` y `JSONArray.put(int,float)`
que ofrece la dependencia JVM y no forman parte de la implementación Android.
Las pruebas JVM pueden pasar y aun así producir `NoSuchMethodError` en el teléfono.
Para valores Float se usa una sobrecarga Object o una conversión explícita a Double.
El guard acepta directorios de `.class` y archivos `.jar`, falla si no encuentra
clases y tiene fixtures compilados con `javac` para probar los casos válidos e
incompatibles. Se ejecuta en CI, `Build.ps1` y `Check-Architecture.ps1`; este último
guarda `evidence/architecture/android-json-abi.json`. Es una comprobación de estas
sobrecargas conocidas, no un análisis completo de compatibilidad con todas las API.
