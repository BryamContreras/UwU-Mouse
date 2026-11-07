# UwU Mouse

Mapeador local de teclado y ratón a controles táctiles para **Android 14 o posterior**, con interfaz Flutter y motor Kotlin. Versión de desarrollo **0.2.0+37**. Conserva el paquete Android `dev.mobimapper.mobimapper` para mantener datos y autorizaciones al actualizar con la misma firma.

## Empezar

1. Abre **Ajustes de conexión** y comprueba **Accesibilidad** y **Motor de toques**. Cada estado muestra el acceso para activarlo cuando hace falta.
2. Para activar el motor sin PC, abre **Activar en este teléfono**, empareja mediante el código de Depuración inalámbrica e introdúcelo desde la notificación. Después puedes usar **Reactivar sin código** mientras Android conserve la autorización. [Guía local](docs/ACTIVAR_TELEFONO.md).
3. Agrega un juego y toca su círculo para abrirlo. **Mapear** permite colocar controles y guardar el perfil.
4. La burbuja permite elegir **Perfiles**, mapear, alternar Control/Teclado y cerrar la sesión. Mantén pulsado un juego para ver sus opciones.

En una pantalla externa, activa también **Ventanas de DeX** desde Ajustes de conexión: abre el permiso estándar de Android para mostrar ventanas sobre otras apps. El teléfono conserva su ruta de superposición de accesibilidad.

Después de reiniciar o perder el motor puede hacer falta reactivarlo. Al tocar un juego, la app intenta hacerlo con la identidad guardada si Depuración inalámbrica sigue disponible; requiere tu pulsación y no recupera acciones mantenidas. **InputLab es opcional**. El [activador de PC](docs/ACTIVAR_PC.md) sigue disponible.

## Mapeo y perfiles

Joystick WASD, cámara continua o por región, acciones táctiles, Disparo M1, Apuntar M2, macros de puntos, **Repetir** al mantener y **Mapa/Mochila** con cursor. Hasta **24 acciones y macros combinadas**. Arrastra los círculos, asigna directamente una tecla, cambia su tamaño desde una esquina o elimínalos con su X. La burbuja permite mostrar los controles y ajustar su opacidad.

Cada juego admite varios perfiles locales con mapeos independientes. **Perfiles** aparece primero en la burbuja y permite seleccionarlos o eliminarlos con confirmación. Los atajos se validan contra las asignaciones existentes. El editor usa un borrador: Guardar valida y aplica de forma segura; Cancelar conserva el perfil anterior. Los paneles tienen controles compactos y barras de ajuste más visibles.

En **DeX**, el perfil de pantalla externa se separa del perfil del teléfono. Su calibración incluye el marco de la ventana; cambiar su tamaño, posición u orientación exige recalibrar. La sesión se pausa al salir del contexto autorizado. Pausar, editar o recuperar el inyector no reproduce teclas o botones que seguían mantenidos.

## Compatibilidad comprobada

En **Samsung con Android 14, DeX por HDMI y pantalla completa a 1920×1080**, se probó Free Fire en Entrenamiento con teclado y ratón físicos. El usuario confirmó el recorrido de movimiento/cámara/acciones, incluida la mira, también después de editar, guardar y volver a abrir desde la interfaz.

Esta prueba no acredita Wireless DeX, DeX para PC, ventanas movidas o redimensionadas, otros juegos ni todos los controles de cada perfil. La reapertura comprobada conservó el repositorio en memoria; el perfil externo reciente no se volvió a probar físicamente tras un reinicio frío del proceso. Las pruebas de persistencia, InputLab y recuperación tienen alcances separados. [Arquitectura, rendimiento y límites](docs/PUBLIC_ARCHITECTURE.md).

## Descargar y compilar

**Actions → Android APKs** genera APK para **arm64-v8a**, **armeabi-v7a** y **x86_64**, más sumas SHA-256. Descarga los artifacts de una ejecución terminada o usa **Run workflow**. Los builds de `main` y manuales usan el certificado de desarrollo persistente del repositorio; los pull requests sin ese secreto usan firma efímera. [CI, herramientas y firma](docs/CI_ANDROID.md).

Base fijada: Flutter **3.47.2**, Dart **3.13.2**, SDK **36**, NDK **28.2.13676358**, AGP **8.13.1**, Kotlin **2.2.20**, Gradle **8.14.3** y bytecode Java **17**. Conserva `pubspec.lock` y el wrapper. En Windows:

```powershell
.\scripts\Build.ps1 -Release
```

Ejecuta análisis, pruebas Flutter/JVM, lint y APK. Para generar APK separados localmente:

```sh
flutter pub get --enforce-lockfile
flutter build apk --release --split-per-abi --android-project-arg=force-version-code-ignoring-abi=true
```

Instala sobre la versión existente con el mismo certificado, con la sesión detenida y sin contactos. El motor local se activa con ADB autorizado como UID 2000 y comprueba el permiso de inyección; no requiere root ni modifica los juegos. Accesibilidad sigue siendo necesaria para la captura y coordinación de esta implementación.

Las métricas de cola o IPC no son latencia hasta imagen. CI no sustituye las pruebas físicas. Capturas, perfiles reales, inventarios, registros privados y claves de firma se excluyen del repositorio público.

No se ha definido una licencia general. Las dependencias conservan sus licencias; el cliente de activación adapta componentes de Shizuku bajo Apache 2.0 y utiliza Conscrypt, Bouncy Castle y BoringSSL. [Atribuciones](THIRD_PARTY_NOTICES.md).
