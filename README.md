# UwU Mouse

Mapeador local de teclado y ratón a controles táctiles para **Android 14 o posterior**, desarrollado con Flutter y Kotlin. La interfaz gestiona juegos, perfiles y ajustes; la entrada y composición multitáctil se procesan en Kotlin dentro del teléfono.

La versión de desarrollo actual es **0.2.0+33**. Conserva la identidad Android `dev.mobimapper.mobimapper` para mantener perfiles y autorizaciones al actualizar con el mismo certificado. El nombre visible pasa de MobiMapper a **UwU Mouse**.

## Uso

**Activación necesaria en esta versión:** el motor de toques se inicia mediante ADB desde una PC. Accesibilidad y Depuración inalámbrica, por sí solas, no lo arrancan. En Windows usa **Activar-UwU-Mouse.cmd** antes de abrir el juego; cuando indique que el servicio está listo, toca el juego en la app. Si el servicio se detiene, vuelve a ejecutar el lanzador. La activación autónoma dentro del teléfono todavía no está implementada. [Pasos de activación](docs/ACTIVAR_PC.md).

1. Agrega un juego a la biblioteca y ábrelo desde su círculo.
2. Coloca los controles con **Mapear** y guarda el perfil.
3. Usa la burbuja para alternar Control/Teclado, editar, elegir un perfil o cerrar la sesión.
4. Mantén pulsado el círculo de un juego para abrir sus opciones.

Los perfiles son locales y persistentes. Cada uno conserva sus botones, posiciones, tamaños, joystick, cámara, sensibilidad y atajos. Se pueden usar varios perfiles del mismo juego; las teclas para seleccionarlos se validan contra las asignaciones existentes. El editor trabaja sobre un borrador: Guardar aplica el cambio mediante una transición segura y Cancelar conserva el perfil anterior.

Controles disponibles: joystick WASD, cámara, acciones táctiles, Disparo M1, Apuntar M2, macros de puntos, **Repetir** al mantener y **Mapa/Mochila** con alternancia de cursor. Se permiten hasta **24 acciones/macros combinadas**. Los círculos se arrastran, cambian de tamaño desde una esquina y se eliminan con su X. La burbuja permite mostrar el mapeo y ajustar su opacidad.

La sesión se pausa al perder el contexto autorizado, cambiar la calibración o recuperar el inyector. Las entradas mantenidas no se reproducen automáticamente después de una pausa. El guardián conserva la secuencia activa y puede reemplazar el worker y cancelar contactos; la pérdida completa de capacidad de inyección mantiene una limitación documentada. [Arquitectura y perfiles](docs/PUBLIC_ARCHITECTURE.md).

## Descargar APK por arquitectura

Abre **Actions → Android APKs**, elige una ejecución terminada y descarga su artifact. También puedes usar **Run workflow**. Hay APK separados para **arm64-v8a**, **armeabi-v7a** y **x86_64**, con versión en el nombre y un artifact de sumas SHA-256. El Samsung usado para las pruebas utiliza arm64-v8a.

Los builds de `main` y manuales usan el certificado de desarrollo persistente configurado como secreto del repositorio. Los pull requests sin ese secreto usan una firma efímera. Siguen siendo APK de desarrollo, no una distribución Play. [Workflow, toolchain y firma](docs/CI_ANDROID.md).

## Compilar

Base fijada: Flutter **3.47.2**, Dart **3.13.2**, Android SDK **36**, NDK **28.2.13676358**, AGP **8.13.1**, Kotlin **2.2.20** y Gradle **8.14.3**. CI utiliza Temurin JDK **17**; la compilación local también admite el JDK 21 de Android Studio y produce bytecode 17. Conserva `pubspec.lock` y el wrapper.

En Windows, con Flutter y Android SDK configurados:

```powershell
.\scripts\Build.ps1 -Release
```

El script ejecuta análisis y pruebas Flutter, construye el APK, ejecuta las pruebas JVM y lint de la app y del receptor independiente InputLab. Para obtener los tres APK localmente:

```sh
flutter pub get --enforce-lockfile
flutter analyze
flutter test
flutter build apk --release --split-per-abi --android-project-arg=force-version-code-ignoring-abi=true
cd android
bash ./gradlew :app:testDebugUnitTest :app:lintDebug :inputlab:testDebugUnitTest :inputlab:assembleDebug :inputlab:lintDebug
```

Las salidas se encuentran en `build/app/outputs/flutter-apk/`. En PowerShell sustituye `bash ./gradlew` por `.\gradlew.bat` al ejecutar las tareas Android.

## Instalar y activar

Con depuración Android autorizada, teclado y ratón conectados por OTG o Bluetooth:

```powershell
.\scripts\Install.ps1 -Release -Serial '<serial>'
.\scripts\Activate.ps1 -Serial '<serial>'
```

Detén la sesión y confirma que no quedan contactos antes de actualizar. Reutiliza las autorizaciones existentes; si Android lo requiere, habilita únicamente **UwU Mouse · entrada local** desde sus ajustes de accesibilidad. La activación inicia el backend local UID 2000 y comprueba `INJECT_EVENTS`; no modifica los juegos ni instala root.

## Pruebas y alcance

El historial distingue la evidencia de InputLab, los registros del mapeador y el comportamiento visible confirmado en Free Fire. Un ACK de inyección no prueba que una acción haya ocurrido dentro de un juego. La referencia física disponible corresponde a Samsung/Free Fire en un escenario controlado; no se anuncia compatibilidad universal.

Las pruebas físicas nuevas de perfiles y atajos, Repetir, Mapa/Mochila, macros y Suspender deben registrarse por separado. Los periodos de repetición son nominales: si hay retrasos se omiten periodos en lugar de acumular ráfagas. Las métricas internas no representan latencia hasta imagen. CI verifica compilación, pruebas automatizadas y empaquetado; no sustituye esas pruebas físicas.

Las capturas, perfiles, inventarios y logs del dispositivo en `evidence/`, las entregas locales de `dist/`, los informes históricos privados y los archivos de firma se excluyen del repositorio público.

No se ha definido una licencia general para el proyecto. Las dependencias conservan sus licencias respectivas.
