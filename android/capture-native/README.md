# Captura evdev en proceso shell

`ShellEvdevCapture` implementa el contrato Kotlin de `capture-api`. Sólo abre nodos
externos USB/Bluetooth de mouse relativo o teclado con capacidades reconocidas;
se excluyen nodos multitáctiles, dispositivos internos y virtuales. La identidad se
vuelve a comprobar al abrir cada descriptor, antes de solicitar `EVIOCGRAB`.

La detección usa capacidades del kernel, sin nombres ni listas de fabricantes.
Admite interfaces de movimiento y botones separadas, teclados compactos, pads
numéricos, teclas programables e interfaces de controles multimedia. Se mantienen
fuera los nodos de encendido/suspensión sin otras teclas, gamepads, entradas
internas y nodos multitáctiles. La huella incluye bus, versión, fabricante,
producto, nombre, identidad física/única y capacidades para detectar la
reutilización de un `eventN` por un dispositivo distinto. Esto amplía los
dispositivos admitidos; no certifica cada modelo comercial.

El proceso debe ser UID 2000 autorizado. La app normal no adquiere estos permisos
por depender del módulo. El caller admite paquete, foco y display y renueva una
lease entre 250 y 2000 ms. El reader y un watchdog nativo independiente comprueban
su vencimiento; el watchdog puede soltar los grabs aunque un callback se bloquee.
La muerte del consumidor debe llamar también a `stop()` desde el supervisor.

La adquisición rechaza teclas o botones ya mantenidos, verifica otra vez tras
capturar todos los descriptores y descarta entrada previa a la sesión. Los cambios
de modo que reinicien el reader deben esperar a que se suelte el atajo activador.
No se reconstruyen acciones mantenidas ni se recupera automáticamente tras hotplug.
Una pérdida de dispositivo, `SYN_DROPPED` o error del consumidor termina la captura.

`captureMouse` y `captureKeyboard` permiten escoger las fuentes. Un nodo que anuncia
ambas capacidades no se puede capturar parcialmente y se rechaza si se excluye una.
Sin `mousePath` se capturan todos los nodos de mouse relativo externos admitidos:
algunos teclados USB publican una interfaz de mouse adicional aunque sólo se use
otro ratón. Los estados de botones se combinan entre esos nodos, de forma que un
movimiento de otra interfaz no suelte un botón mantenido. Un `mousePath` explícito
conserva la selección individual. El broker publica el inventario de la última
adquisición con su instante de muestreo; consultar el estado no vuelve a explorar
dispositivos.
Los botones usan los bits Android: primary=1, secondary=2, middle=4, back=8,
forward=16. Los códigos de teclado siguen siendo Linux scan codes. El `deviceId`
es el número evdev de `eventN`, no el ID `InputDevice` de Android.

Los frames contienen primitivas, timestamp CLOCK_MONOTONIC y movimiento acumulado
hasta SYN_REPORT. El módulo no abre ventanas, no interpreta perfiles, no inyecta
toques y no obtiene el display a partir del evento evdev. La sesión posee el
dispositivo para un destino explícito; la validación del destino corresponde al
runtime que conecta captura e inyección.

`CaptureRequest.mouseRateHz` limita la entrega de movimiento **en el lector
nativo, antes de JNI y Binder**. Cero significa libre. Los límites no modifican
la frecuencia USB/Bluetooth ni dejan de leer el kernel. El lector conserva cada
delta y timestamp original en un lote global de hasta 256 muestras, de modo que
el filtro de precisión posterior siga procesando las muestras originales.
`PhysicalMouseBatch` conserva también la identidad de cada nodo; rueda vertical
y horizontal se acumulan en el lote. La fase de entrega usa un plazo monotónico
y `ppoll`, sin un ticker cuando no hay movimiento pendiente. En modo libre se
entrega el trabajo ya disponible al terminar cada lectura acotada, sin espera.

Los bordes de botones y teclas vacían antes el movimiento pendiente y se entregan
inmediatamente. Por ello no quedan limitados por la frecuencia de movimiento.
`configureMouseRate()` cambia el límite sin devolver/reabrir descriptores ni
reiniciar la propiedad de botones. Una señal al lector aplica el cambio y vacía
antes el lote del límite anterior. El estado distingue límite solicitado y
aplicado, muestras crudas/entregadas, lotes, máximo pendiente, lecturas y barreras.
Pausa o pérdida descarta el movimiento pendiente; un desbordamiento detiene la
captura en vez de perder muestras silenciosamente.

`python tools/test_capture_policy.py` compila y ejecuta las pruebas C++ de esta
política con MSVC, GCC o Clang. Comprueba 8 kHz a 100/500/1000 Hz, conservación
de muestras, barreras, cambios de frecuencia, límites y tipos de dispositivos.

La librería se carga por ruta absoluta desde `Context.applicationInfo.nativeLibraryDir`.
El APK debe extraer `libuwu_capture.so` para que el proceso shell pueda cargarla.
Las reglas consumer de R8 conservan el contrato JNI. No hay copia literal de KeySync;
la investigación de su rama experimental se mantiene en evidencia local.
