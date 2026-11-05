# Laboratorio independiente

`dev.mobimapper.inputlab` tiene un UID distinto del mapeador. Solo observa lo que
Android entrega a su propia ventana. No contiene IPC hacia MobiMapper, permisos de
accesibilidad, inyección, red, captura de puntero ni reenvío de teclado/ratón.

Compilar desde `android`: `./gradlew.bat :inputlab:assembleDebug`.
Instalar usando siempre el serial confirmado:

```powershell
& $adb -s $serial install -r ..\build\inputlab\outputs\apk\debug\inputlab-debug.apk
& $adb -s $serial shell am start -n dev.mobimapper.inputlab/.InputLabActivity
& $adb -s $serial logcat -s MobiInputLab:I '*:S'
```

El lienzo usa coordenadas lógicas de la ventana de borde a borde; oculta barras
con restauración transitoria del sistema y muestra dimensiones, display e insets.
No cambia resolución, densidad ni rotación del sistema. En pantalla completa la
región de juego es `[0,width] × [0,height]`. Multiventana y displays externos
requieren otra calibración y siguen pendientes de prueba.

Referencias normalizadas: joystick `(0.23,0.70)`, radio `0.12 × width`; cámara
`x=0.50..0.90, y=0.30..0.80`; acción `(0.82,0.82)`. Los dedos se pintan con ID,
coordenadas y color estable. Se validan conjuntos de punteros, índices y downTime.
Una liberación parcial debe incrementar el contador manteniendo los otros dedos.
El validador también rechaza una pulsación cuyo índice apunte a un ID ya activo,
`POINTER_UP` con un solo puntero y `UP` con varios punteros. Sus pruebas JVM están
en `src/test` y se ejecutan con `./gradlew.bat :inputlab:testDebugUnitTest`.

Las teclas WASD/ESPACIO y los eventos de ratón recibidos se cuentan como posibles
fugas solo si esos controles están asignados en la sesión. El laboratorio no
conoce el estado del mapeador y no presupone si un evento es físico o sintético.
Para evaluar fugas hay que delimitar la sesión y restar la línea base de eventos
observados antes de activar el mapeador. El contador de ratón filtra exclusivamente
`SOURCE_MOUSE` y `SOURCE_MOUSE_RELATIVE`; no cuenta joystick ni stylus como ratón.

Cada transición táctil escribe `MobiInputLab`: secuencia, acción, índice, IDs,
coordenadas, downTime, eventTime, receiptUptime, fuente y dispositivo. Los movimientos
se retienen en memoria circular (256 muestras), sin disco ni log por movimiento.
Los contadores y muestras representan callbacks recibidos. No desglosan el historial
incluido en `MotionEvent`, y no permiten inferir Hz físicos del periférico.
El botón **Guardar resumen** y `onPause` escriben solo el resumen propio y las últimas
256 muestras en `files/input-lab-summary.json`. Se puede extraer del APK debug:

```powershell
& $adb -s $serial shell run-as dev.mobimapper.inputlab cat files/input-lab-summary.json
```

`MotionEvent.eventTime`, `downTime` y `SystemClock.uptimeMillis()` comparten base
monotónica en milisegundos. `receiptUptime-eventTime` mide únicamente entrega del
evento a esta ventana; no mide latencia del periférico ni tiempo hasta el fotón.
La pintura, las copias y los logs de transición de este observador añaden coste de
instrumentación. No usar sus cifras como benchmark de juego sin comparar esa carga.
