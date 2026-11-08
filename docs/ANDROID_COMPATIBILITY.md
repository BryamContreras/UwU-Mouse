# Compatibilidad Android 14–17

Revisión de contratos: 2026-11-08. Este documento describe una aplicación y una configuración de compilación compartidas. Los APK por ABI contienen el mismo comportamiento; la ABI identifica la arquitectura de CPU, no el modelo del teléfono.

El soporte mínimo es Android 14 (API 34). Ninguna selección de captura, cursor, inyección o geometría debe depender de `Build.MODEL`, `Build.MANUFACTURER`, un número de pantalla fijo o una resolución de Samsung. Las diferencias de API documentadas requieren comprobar la versión; las diferencias del fabricante requieren comprobar el resultado real de la operación y registrar el error.

## Matriz de contratos

| Sistema | Cambios relevantes | Respuesta requerida en UwU Mouse |
| --- | --- | --- |
| Android 14 / API 34 | Los servicios en primer plano requieren tipo y permiso específicos. La carga dinámica de DEX/JAR tiene restricciones. | `specialUse` con `FOREGROUND_SERVICE_SPECIAL_USE`, notificación y comienzo desde una acción visible. El backend usa el APK instalado; no debe ejecutar una copia modificable de sus clases. |
| Android 15 / API 35 | Tener `SYSTEM_ALERT_WINDOW` por sí solo deja de permitir iniciar un servicio desde segundo plano. El antiguo `InputManager.setPointerIconType(int)` de AOSP ya no hace nada. | Iniciar el servicio mientras la actividad es visible o existe una superposición visible. No declarar oculto el cursor del sistema tras invocar el método antiguo. |
| Android 16 / API 36 | La geometría de escritorio y las ventanas redimensionables requieren medir la ventana actual. Continúa la ausencia de efecto del método antiguo de cursor. | Obtener pantalla, contexto de ventana, posición, tamaño e insets reales. Cancelar contactos y exigir recalibración cuando cambie el área del juego. |
| Android 17 / API 37 | Al apuntar a API 37, la red local requiere permiso de ejecución; `System.load()` exige bibliotecas de solo lectura. El modo de captura pública interpreta gestos del touchpad como movimiento relativo por defecto. | Solicitar acceso a red local antes de mDNS/activación; cargar bibliotecas instaladas por PackageManager. La captura de producción sigue usando capacidades evdev; no interpreta eventos absolutos de touchpad como un mouse relativo. |

Fuentes oficiales: [servicios de Android 14](https://developer.android.com/about/versions/14/changes/fgs-types-required), [cambios de Android 15](https://developer.android.com/about/versions/15/behavior-changes-15), [cambios de Android 16](https://developer.android.com/about/versions/16/behavior-changes-all), [ventanas de Android 16](https://developer.android.com/guide/practices/device-compatibility-mode), [cambios de Android 17 para todas las apps](https://developer.android.com/about/versions/17/behavior-changes-all) y [cambios al apuntar a Android 17](https://developer.android.com/about/versions/17/behavior-changes-17).

## Cursor: API pública y funciones privilegiadas

El cursor propio y el cursor del sistema son dos presentaciones independientes. Dibujar correctamente el cursor propio no demuestra que Android haya ocultado el suyo. Los diagnósticos deben conservar esta distinción.

`View.requestPointerCapture()` es una API pública que oculta el cursor y entrega movimientos a una vista enfocada de la propia aplicación. Exige el foco de su jerarquía y cambia el destino de los eventos del mouse. Por eso no es un sustituto directo para una superposición pasiva mientras otro juego mantiene el foco. Véase [captura pública del puntero](https://developer.android.com/develop/ui/views/touch-and-input/gestures/movement#pointer-capture).

La frontera del método global antiguo es **API 35**, no API 36. Evidencia exacta de versiones publicadas de AOSP:

| Fuente etiquetada | Implementación de `setPointerIconType(int)` |
| --- | --- |
| [Android 14, `android-14.0.0_r1`](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/core/java/android/hardware/input/InputManager.java) | Delega en `mGlobal.setPointerIconType(iconId)`. Sigue siendo una API oculta, sin garantía SDK. |
| [Android 15, `android-15.0.0_r1`](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/core/java/android/hardware/input/InputManager.java) | El comentario dice: “This method exists for backwards-compatibility, and is a no-op.” El cuerpo solo escribe un error al registro. |
| [Android 16, `android-16.0.0_r1`](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/hardware/input/InputManager.java) | Conserva ese cuerpo sin efecto. |

El [contrato Binder actual de `IInputManager`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/input/IInputManager.aidl) ofrece un método de icono ligado a pantalla, dispositivo, puntero y token de entrada. No ofrece el control global `setPointerIconVisible`. Este último reside en el servicio local de `system_server`, como muestra [InputManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/input/InputManagerService.java). No se debe simular su disponibilidad desde un proceso shell ni sustituirla por números de transacción Binder fijos.

El cursor confiable del proceso shell también usa funciones ocultas de WindowManager. La sesión observada en Android 17 rechazó ese proceso con `Unknown pid=… uid=2000`. Esa observación no prueba que todos los dispositivos de esa versión se comporten igual. La selección de representación debe usar el resultado real del alta de ventana: conservar la representación confiable cuando se admite y usar la ventana del proceso de la app cuando se rechaza. El error original debe permanecer disponible en los diagnósticos.

## Superposiciones y coordenadas

Las ventanas `TYPE_APPLICATION_OVERLAY` necesitan permiso del usuario incluso en una pantalla externa. La pantalla seleccionada debe existir y el `WindowManager` debe provenir de un contexto de ventana de ese tipo y pantalla. [Contrato de `Context.createWindowContext`](https://developer.android.com/reference/android/content/Context#createWindowContext(android.view.Display,%20int,%20android.os.Bundle)).

Una superposición pasiva normal no es una ventana confiable. Android limita la opacidad combinada de las ventanas que atraviesa un toque. UwU Mouse obtiene el límite con `InputManager.getMaximumObscuringOpacityForTouch()` y calcula el presupuesto combinado; subir cada ventana a opacidad completa puede bloquear al juego. El fondo transparente de una superficie no elimina esta regla sobre `LayoutParams.alpha`. [Contrato de `FLAG_NOT_TOUCHABLE`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_NOT_TOUCHABLE).

La geometría del mapeo debe proceder del área efectiva del juego, su pantalla y su transformación. Ni `displayId == 0` ni la resolución física identifican el área de una ventana en escritorio. Rotación, cambio de tamaño/densidad, desconexión de monitor y cambio de foco invalidan la sesión previa. La app debe liberar los contactos antes de reconfigurar la presentación.

## Activación, servicios y carga nativa

Al apuntar a API 37, `NsdManager` y las conexiones a direcciones LAN requieren `ACCESS_LOCAL_NETWORK`. La solicitud debe hacerse desde la actividad visible antes de iniciar la búsqueda o conexión; denegación y revocación deben producir un estado recuperable. Un socket de loopback del backend no convierte la búsqueda mDNS en una operación exenta. [Permiso de red local](https://developer.android.com/privacy-and-security/local-network-permission).

Los servicios de sesión y activación declaran `specialUse`, su permiso y su finalidad en el manifiesto. La activación comienza desde la actividad antes de abrir ajustes; las acciones de la burbuja ya tienen una ventana visible. No debe iniciarse un servicio nuevo desde segundo plano suponiendo que el permiso de superposición equivale a una ventana visible. [Restricciones de inicio de servicios](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

El lanzamiento directo del juego debe corresponder a una acción del usuario desde la app o una superposición visible. La admisión de una actividad en una pantalla no demuestra que un lanzamiento en segundo plano esté permitido. Los `PendingIntent` de notificación son explícitos; el código de producción no envía `IntentSender` ni delega lanzamientos mediante `PendingIntent.send()`. Si se incorpora alguno, necesita revisar las reglas de opt-in de [seguridad de actividades](https://developer.android.com/guide/components/activities/secure-bal).

`ShellEvdevCapture` carga `libuwu_capture.so` desde `applicationInfo.nativeLibraryDir`; la instalación administra ese archivo. `AdbPairingClient` usa `System.loadLibrary`. No se deben copiar estas bibliotecas a una caché escribible para cargarlas. En la auditoría no se encontró modificación reflectiva de campos `static final`, JNI `SetStatic*Field` ni acceso a campos privados de `MessageQueue`. La modificación de atributos de una instancia de `Context` durante el arranque shell sigue siendo un uso de API oculta y debe fallar con un diagnóstico si cambia Android.

## Captura e inyección: límites del contrato

Evdev entrega eventos del kernel con códigos independientes de la arquitectura. AOSP permite lectura de nodos de entrada al dominio shell, pero esa política no convierte `/dev/input` o `EVIOCGRAB` en una API pública Android garantizada en cada fabricante. [Interfaz evdev del kernel](https://docs.kernel.org/input/input.html#evdev), [política shell de AOSP](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/shell.te).

La selección de dispositivos usa capacidades, identidad y roles; los números de `eventN` no son estables. La adquisición debe confirmar lectura, reloj monotónico y acceso exclusivo antes de declarar activa la captura. Al perder el dispositivo, el foco, la concesión de captura o el backend, debe liberar las teclas/contactos y cerrar los descriptores. Un kernel o una política que deniegue esas operaciones debe devolver una causa explícita; no debe dejar una captura parcial.

La inyección entre aplicaciones y la consulta privilegiada de ventanas dependen de interfaces shell ocultas. Deben comprobar permiso, firma de método, pantalla y UID del destino. Una actualización de Flutter/Kotlin/Gradle no concede estas capacidades al dispositivo. Ninguna revisión estática permite garantizar ausencia de fallos en todos los fabricantes.

La reconciliación excepcional de contactos usa texto de `dumpsys input`, que tampoco es un contrato SDK. Android 14/15 emitían `TouchStates: <no displays touched>`; Android 16 separa estados táctiles y de cursor y cambia ese texto. El parser admite el formato moderno solamente cuando ambas secciones declaran explícitamente estar vacías; los mapas modernos no vacíos, incluidos los que contienen solo hover, siguen rechazándose de forma conservadora. Solo se aceptan formatos completos contrastados con AOSP; los desconocidos o truncados no prueban una liberación. Fuentes: [InputDispatcher Android 14](https://android.googlesource.com/platform/frameworks/native/+/android-14.0.0_r1/services/inputflinger/dispatcher/InputDispatcher.cpp), [Android 15](https://android.googlesource.com/platform/frameworks/native/+/android-15.0.0_r1/services/inputflinger/dispatcher/InputDispatcher.cpp), [Android 16](https://android.googlesource.com/platform/frameworks/native/+/android-16.0.0_r1/services/inputflinger/dispatcher/InputDispatcher.cpp).

## Validación de una versión

Las pruebas JVM/nativas comprueban las decisiones y liberaciones; compilar con SDK 37 no ejecuta la app en Android 14–17. Antes de afirmar una regresión resuelta deben guardarse resultados del mismo APK firmado en esta matriz:

El workflow `Android 14–17 compatibility` compila una sola pareja de APK de depuración x86_64, publica sus hashes e instala exactamente esos archivos en los cuatro emuladores. La instrumentación comprueba el inicio de la actividad, el servicio `specialUse`, el enlace de la biblioteca nativa instalada, ventanas reales del cursor en la pantalla principal y una pantalla virtual privada, rechazo del permiso de overlay y el permiso de red local denegado/concedido en API 37. El script se limita a emuladores y falla si la instrumentación no termina todas las pruebas. No adquiere nodos evdev ni inyecta controles; esas operaciones requieren validación física. La ejecución del workflow debe constar como aprobada antes de registrar esos casos como comprobados. [Ejecución de instrumentación por ADB](https://developer.android.com/studio/test/command-line#run-tests-with-adb).

| Entorno | Comprobación pendiente de cada nueva versión |
| --- | --- |
| API 34, 35, 36 y 37 en emulador | Instalación; permiso de overlay; creación del contexto por pantalla; alta/baja del cursor; ciclo de vida del servicio; permiso de red local cuando corresponda. |
| Dispositivo físico de cada versión disponible | Captura USB/Bluetooth; teclado y botones; cursor libre/cámara/editor/menú; pérdida de foco; suspensión; desconexión y recuperación. |
| Pantalla interna y escritorio externo | Coordenadas, densidad, rotación, cambio de ventana, reconexión y cursor en la pantalla correcta. |
| S22 Ultra que antes funcionaba | Repetir la alternancia de cursor/cámara, editor y cámara suave con el APK actualizado; el comportamiento anterior no valida una compilación nueva. |
| S25 Ultra del fallo observado | Confirmar movimiento visible del cursor propio y comprobar por separado el estado del cursor del sistema. |

No registrar como aprobado un caso sin ejecutarlo. Guardar versión Android/API, ABI, hash del APK, versión de app, representación elegida, errores de cursor/captura y el resultado visual. Los perfiles existentes deben conservarse durante las actualizaciones con la misma firma.
