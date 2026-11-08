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
- Flutter 3.47.2, revisión `d3b14c876900e553bc736ca19295fc09e3853e8e`, Dart 3.13.2.
- Android SDK 36; build tools 35.0.0/36.0.0; NDK 28.2.13676358, el definido por ese Flutter.
- Gradle 8.14.3 y su SHA-256 en el wrapper; AGP 8.13.1, Kotlin 2.2.20 y dependencias de `pubspec.lock`.

Las acciones están fijadas a SHA completos de sus repositorios, con la versión mayor como comentario. El SDK Linux 3.47.2 está publicado en el [manifiesto oficial](https://storage.googleapis.com/flutter_infra_release/releases/releases_linux.json), con SHA-256 `447878859d01ca9bfdb99a85f245af07ed8a15fedcd9d189c4749e8e92d1f185`. El workflow comprueba la versión y revisión exactas después de instalarlo. [Configuración del mantenedor de flutter-action](https://github.com/subosito/flutter-action#specifying-flutter-version).

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
