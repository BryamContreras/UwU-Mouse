# UwU Mouse

Mapeador local de teclado y ratón a controles táctiles para **Android 14 o posterior**, con interfaz Flutter y motor Kotlin. Versión de desarrollo **0.2.0+37**. Conserva el paquete Android `dev.mobimapper.mobimapper` para mantener datos y autorizaciones al actualizar con la misma firma.

## Empezar

1. Abre **Ajustes de conexión** y comprueba **Captura nativa**, **Motor de toques** y **Mostrar sobre otras apps**. Esta implementación ya no declara un servicio de accesibilidad.
2. Para activar el motor sin PC, abre **Activar en este teléfono**, empareja mediante el código de Depuración inalámbrica e introdúcelo desde la notificación. Después puedes usar **Reactivar sin código** mientras Android conserve la autorización. [Guía local](docs/ACTIVAR_TELEFONO.md).
3. Agrega un juego y toca su círculo para abrirlo. **Mapear** permite colocar controles y guardar el perfil.
4. La burbuja permite elegir **Perfiles**, mapear, alternar Control/Teclado y cerrar la sesión. Mantén pulsado un juego para ver sus opciones.

El permiso estándar **Mostrar sobre otras apps** se utiliza tanto en el teléfono como en pantallas externas. La captura de teclado y ratón usa un proceso autorizado UID 2000 y lectura exclusiva de dispositivos externos mediante `EVIOCGRAB`; el inyector conserva su proceso separado.

Después de reiniciar o perder el motor puede hacer falta reactivarlo. Al tocar un juego, la app intenta hacerlo con la identidad guardada si Depuración inalámbrica sigue disponible; requiere tu pulsación y no recupera acciones mantenidas. **InputLab es opcional**. El [activador de PC](docs/ACTIVAR_PC.md) sigue disponible.

## Mapeo y perfiles

Joystick WASD, cámara continua o por región, acciones táctiles, Disparo M1, Apuntar M2, macros de puntos, **Repetir** al mantener y **Mapa/Mochila** con cursor. Hasta **24 acciones y macros combinadas**. Arrastra los círculos, asigna directamente una tecla, cambia su tamaño desde una esquina o elimínalos con su X. La burbuja permite mostrar los controles y ajustar su opacidad.

El selector de cámara ofrece **Continua** y **Región suave**. Los perfiles antiguos de región se convierten sólo en el borrador del editor: Guardar aplica el modo elegido y Cancelar conserva el original. La burbuja permite ajustar sensibilidad y tamaño del cursor de forma independiente de la cámara.

En los ajustes de **Disparo M1**, **Opciones de cadencia** añade desplazamiento vertical tras **500 ms** manteniendo el botón. El nivel se ajusta de −100 a +100 %: izquierda hacia abajo, derecha hacia arriba y cero neutro. Se detiene al soltar, pausar o entrar al cursor; permanece desactivado por defecto y se guarda por perfil.

La cámara ofrece **100, 500, 1000 Hz y Libre** para limitar la entrega de movimiento desde el lector nativo antes de JNI/Binder. Conserva las muestras y sus tiempos; clics y teclas se entregan sin esperar ese límite. No cambia el polling del hardware ni acelera un mouse más lento. Los perfiles anteriores conservan su frecuencia guardada hasta cambiarla. Los ajustes concentran su ayuda en un **?** por fila, visible al pasar el cursor o tocarlo.

**Mira precisa** suaviza algo más los movimientos horizontales pequeños mientras se mantiene el disparo. Conserva el movimiento vertical y los giros rápidos, sin añadir una espera ni reproducir después el movimiento suprimido.

Cada juego admite varios perfiles locales con mapeos independientes. **Perfiles** aparece primero en la burbuja y permite seleccionarlos o eliminarlos con confirmación. Los atajos se validan contra las asignaciones existentes. El editor usa un borrador: Guardar valida y aplica de forma segura; Cancelar conserva el perfil anterior. Los paneles tienen controles compactos y barras de ajuste más visibles.

En **DeX**, el perfil de pantalla externa se separa del perfil del teléfono. Su calibración incluye el marco de la ventana; cambiar su tamaño, posición u orientación exige recalibrar. La sesión se pausa al salir del contexto autorizado. Pausar, editar o recuperar el inyector no reproduce teclas o botones que seguían mantenidos.

## Compatibilidad y alcance de la migración

La arquitectura separa interfaz, dominio, captura, ventanas, presentación, activación e inyección mediante contratos reemplazables. Hay 13 proyectos Gradle: diez de producción, dos laboratorios independientes y un host de validación. [Mapa de módulos y contratos](ARCHITECTURE.md). El código del motor y los perfiles mantienen sus formatos; la captura nativa exige dispositivos externos compatibles y rechaza adquirir entradas mantenidas. Su lease devuelve los dispositivos si el consumidor muere o deja de renovar.

La detección se basa en capacidades USB/Bluetooth y admite más interfaces compuestas, teclados compactos, pads numéricos y controles multimedia. Las teclas usan layouts instalados por fabricante/producto o nombre, con Generic como fallback. Esto amplía la compatibilidad sin certificar todos los modelos, firmwares o idiomas.

El preview nativo se instala con un paquete separado cuando no está disponible la firma del APK existente.

La ruta nativa se probó en el teléfono con teclado y ratón físicos, sin servicio de accesibilidad y con InputLab como receptor independiente. En la observación delimitada recibió 3311 callbacks táctiles, sin errores de secuencia ni fugas de mouse o WASD asignado; las teclas sin asignación se reenviaron de forma deliberada. [Resultados y alcance de la medición](ARCHITECTURE.md#validación-y-referencias).

Con un perfil del teléfono se validó **Free Fire en Entrenamiento**: WASD, cámara, M1/M2 y Espacio; clic y arrastre con el cursor; alternancias de mochila/mapa y vuelta a cámara. Guardar conservó los ajustes del usuario y Cancelar dejó el perfil exactamente igual. [Pruebas del juego y editor](ARCHITECTURE.md#validación-y-referencias). En DeX se corrigieron la adquisición de dispositivos USB compuestos y la saturación con mouse de alta frecuencia; el usuario confirmó cámara, cursor y ajustes del menú, incluidos deslizador, arrastre y regreso al juego. [Validación nativa en DeX](ARCHITECTURE.md#validación-y-referencias). Otros juegos y configuraciones requieren sus propias pruebas.

Salir mediante HOME cancela los contactos. Al volver al destino original, el motor puede cerrar la secuencia pendiente con el **mismo guardian y la misma sesión**, sin reiniciarlos ni reanudar el mapeo. Se comprobaron tanto la cancelación admitida como la reconciliación estricta con Android cuando la cancelación adicional fue rechazada. La sesión termina pausada y sin contactos; Jugar sigue siendo una acción explícita. [Condiciones de recuperación](ARCHITECTURE.md#validación-y-referencias).

Las comprobaciones históricas siguientes corresponden al motor anterior. No acreditan por sí solas la migración nativa en todos los juegos o dispositivos.

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

Instala sobre la versión existente con el mismo certificado, con la sesión detenida, sin contactos y el backend anterior cerrado. El motor local se activa con ADB autorizado como UID 2000 y comprueba el permiso de inyección; no requiere root ni modifica los juegos. La captura y las ventanas ya no dependen de accesibilidad. Un backend antiguo sin capacidad nativa debe reiniciarse después de actualizar el APK.

`python tools/check_architecture.py --json` verifica los límites y el protocolo Binder. `scripts/Check-Architecture.ps1` compila y prueba los módulos Android por separado, sin cambiar las versiones Flutter/Dart del proyecto. InputLab y `android/latencyprobe` son laboratorios independientes; este último conserva una prueba de accesibilidad exclusivamente para comparar rutas y no forma parte de la aplicación.

La etapa de frecuencia y mira precisa aprobó **571 pruebas JVM**, **59 pruebas Flutter** y **12 comprobaciones C++** de captura, con análisis Flutter correcto. El límite de 100 Hz entregó 96,26 paquetes/s mientras el lector recibía 480 muestras/s, conservando las muestras. El usuario confirmó selector, ayudas y guardado a 500 Hz, además de estabilidad algo mayor al disparar y conservación de giros rápidos en Entrenamiento con el G502 y teclado USB de DeX. [Frecuencia, rendimiento y alcance](ARCHITECTURE.md#frecuencia-de-captura-muestras-y-entrega-del-motor).

La cámara continua y **M4/M5 para Suspender** conservan su validación anterior en DeX a 100 Hz. La región estricta todavía mostró una pausa al recentrar; los ensayos de reinicio durante movimiento e intercalado no fueron aceptados. El ensayo RAM posterior `deferred`, que mantiene el arrastre y renueva durante pausas, recibió la respuesta **«Va bien, me gusta»** al probar giros largos sin y con disparo, WASD y sensibilidad. Su selección persistente como **Región suave** (`smooth`) ya está instalada y activa en DeX; aprobó **705 pruebas JVM**, **63 pruebas Flutter**, análisis correcto y revisión de **122 clases** de JSON. Se seleccionó explícitamente conservando los demás ajustes; los perfiles existentes mantienen su modo y defaults. El mínimo adaptativo de 20 ms exige quietud y no ordena reinicios periódicos. [Artefactos, investigación y límites](ARCHITECTURE.md#validación-y-referencias).

Las métricas de cola o IPC no son latencia hasta imagen. CI no sustituye las pruebas físicas. Capturas, perfiles reales, inventarios, registros privados y claves de firma se excluyen del repositorio público.

No se ha definido una licencia general. Las dependencias conservan sus licencias; el cliente de activación adapta componentes de Shizuku bajo Apache 2.0 y utiliza Conscrypt, Bouncy Castle y BoringSSL. [Atribuciones](THIRD_PARTY_NOTICES.md).
