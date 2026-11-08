# Laboratorio temporal de latencia

App auxiliar `dev.mobimapper.latencyprobe`, separada del mapeador instalado. No es
una implementación alternativa del mapeador ni mide su cola, conversión o inyector.
No modifica ni reemplaza la app original ni sus perfiles o permisos.
Compila el archivo real `InputLatencyProbe.kt` mediante una tarea de fuente generada de AGP, sin incluir el resto del motor.

Los modos `native` y `accessibility` actualizan la misma Activity y el mismo View.
El primero recibe mouse mediante los callbacks normales de la Activity; el segundo
mediante `AccessibilityService.onMotionEvent` de Android 14. El servicio empieza
con fuentes de movimiento cero, sin eventos de accesibilidad, lectura de ventanas,
teclado ni inyección. Solo una prueba explícita activa fuentes de mouse, con plazo
predeterminado de 30 segundos y máximo de 60. Al terminar, perder foco, pausar o
interrumpirse el servicio, devuelve las fuentes a cero. El servicio auxiliar debe
habilitarse para la prueba y quitarse al terminar.

Control ADB autorizado (siempre seleccionar el serial):

```powershell
adb -s SERIAL shell am start -n dev.mobimapper.latencyprobe/.ProbeActivity
adb -s SERIAL shell content call --uri content://dev.mobimapper.latencyprobe.control --method start --extra mode:s:native --extra durationMs:i:30000
# Mover el mouse físico durante la ventana. Cambiar mode:s:accessibility para el otro modo.
adb -s SERIAL shell content call --uri content://dev.mobimapper.latencyprobe.control --method stop
adb -s SERIAL shell content call --uri content://dev.mobimapper.latencyprobe.control --method snapshot --extra offset:i:0 --extra limit:i:512
```

El snapshot usa la paginación del recorder real: leer `nextRecordsOffset` hasta
null después de detener la prueba. Conserva hasta 4096 registros; `overwrittenCount`
expone sobrescrituras. No se escriben movimientos a logs ni archivos. Los callbacks
se registran como `helper_native_receipt` o `helper_accessibility_receipt`; cada
frame atribuye `helper_*_draw_cpu_end` únicamente al último callback pendiente.
`supersededBeforeDraw` cuenta los anteriores, que no tienen dibujo individual.
No se desglosa el historial agrupado de MotionEvent. Los timestamps son
`System.nanoTime` y `MotionEvent.eventTimeNanos`, ambos monotónicos sin suspensión.

La medición hasta recepción incluye toda la ruta de entrega Android. La diferencia
entre modos no aísla causalmente el coste puro de accesibilidad. El final de onDraw
registra comandos CPU: no acredita ejecución GPU, presentación, latencia del panel
ni tiempo hasta el fotón. El hardware del periférico anterior al timestamp del
evento tampoco se mide. El dibujo, recorder y consultas ADB añaden instrumentación;
extraer snapshots después de la ventana para reducir interferencia.

El módulo también tiene settings independientes para compilar sin Flutter ni cambiar
los settings del proyecto principal. Configura un JDK 17 o posterior en `JAVA_HOME`
y Android SDK en `ANDROID_HOME` o `android/latencyprobe/local.properties`:

```powershell
.\android\gradlew.bat -p android/latencyprobe assembleDebug
```

Durante esta sesión se compiló con el harness aislado
`evidence/latency-20261107/validation` y Gradle 9.1 cacheado.
