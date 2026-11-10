# Revisión de rendimiento — 9 y 10 de noviembre de 2026

Revisión del recorrido evdev → JNI/Binder → cola del motor → composición táctil →
guardián → pipe → inyector, junto con cámara, transiciones, overlays, editor,
monitor de rendimiento y ciclo de vida de Flutter. Los cambios locales previos
se conservaron.

## Cambios aplicados

| Área | Problema encontrado | Cambio |
| --- | --- | --- |
| Captura C++ | El lector despertaba cada 100 ms sin entrada; el watchdog comprobaba la lease cada 25 ms. | Esperas hasta el vencimiento de la lease o del lote de movimiento. `eventfd` conserva la respuesta inmediata a parada y cambio de frecuencia. El watchdog independiente sigue liberando dispositivos aunque el callback quede bloqueado. |
| Pipe del inyector | Cada envío y ACK despertaban al watchdog aunque ampliaran o cancelaran su plazo. | Se despierta al iniciar desde reposo o adelantar un vencimiento. Conserva los límites de cada operación y la terminación del worker bloqueado. |
| Cursor confiable | Un `Runnable` por posición podía acumular trabajo visual con ratones rápidos. | Un buzón con la última posición y una entrega pendiente por frame, validada con el token del cursor. Los clics y contactos mantienen su recorrido y orden. |
| Cursor de la app | Invalidaciones repetidas de animación para cada movimiento. | Un callback de dibujo pendiente; se omiten posiciones idénticas y se cancela al desmontar la vista. Los colores se preparan al crearla. |
| Cámara genérica | Despertador cada 350 ms aun sin dedo activo, antes del primer movimiento o después del retiro por inactividad. | Se suspende sin contacto ni reinicio pendiente. Conserva la fase temporal, el retiro por inactividad y el reinicio de 15 ms. Free Fire conserva su política. |
| Composición táctil | Búsquedas con iteradores por muestra y envío de MOVE cuando todos los contactos regresaban a su posición ya enviada dentro del lote. | Búsqueda indexada y supresión de ese MOVE redundante. Cada muestra sigue integrándose y los cambios de contacto siguen vaciando el movimiento pendiente. |
| Worker de inyección | Array temporal para validar acciones y copia de `TouchFrame` por MOVE. | Validación directa y reutilización del frame recibido para mantener el estado del MOVE aceptado. |
| Ventanas y arrastres | Relayout y refresco visual aunque el píxel final no cambiara. | Burbuja, menú y monitor actualizan sólo cuando cambia la posición. Los snapshots que cambian sólo su imagen no provocan relayout del backend. |
| Editor | Listas, triples, puntos, rectángulos y métricas temporales durante el dibujo de marcadores. | Geometría reutilizable, etiquetas de tecla cacheadas y dibujo directo. |
| Monitor FPS | Cada alternancia de visibilidad creaba un executor; una llamada Binder bloqueada podía sobrevivir al cierre. | Una sola vía reutilizable, máximo una muestra en vuelo, último destino vigente y una entrega pendiente. El hilo expira tras 10 s sin trabajo; al ocultar se cancelan las tareas futuras. |
| Activación Flutter | El timer seguía despertando en segundo plano aunque evitara consultar el backend. | Se cancela al salir de primer plano y se crea uno al regresar. |
| Diagnósticos de latencia | Contadores `Int` crecientes e interpretación del buffer de 512 muestras como si admitiera hasta 2048, añadiendo ceros al crecer la copia. | Rings con índice y tamaño acotados a su capacidad real. Ordenación de arrays primitivos sobre snapshots separados del bloqueo del motor. |

Estos ahorros describen operaciones eliminadas en el código. No equivalen a una
reducción medida de latencia hasta la imagen, consumo o temperatura.

## Procesos, colas y tareas que se conservan

- La app mantiene el worker del motor; el proceso shell aloja captura y guardián,
  y el inyector conserva su proceso separado. Esta separación sostiene los
  permisos, la recuperación y la liberación de entradas si falla un consumidor.
- La cola del motor admite 256 entradas; cada lote de movimiento, hasta 256
  muestras. El registro opcional de latencia usa 4096 posiciones y termina en un
  máximo de 60 s. No se encontró un historial creciente en esas rutas.
- El host mantiene límites adicionales de callbacks/muestras antes de main;
  saturarlos detiene la captura en vez de acumular entrada indefinidamente.
- Macros, repetición, cadencia y acciones de cursor conservan tareas acotadas y
  cancelación al terminar o cambiar de sesión. No se añadieron ráfagas para
  reproducir períodos de repetición perdidos.
- Se conservan las comprobaciones de foco/contexto, generación, modo de puntero,
  identidad de captura, ACKs por entrega y el diario de recuperación. El camino
  directo de cámara sigue sin adelantar una tecla o botón pendiente en main.
- La consulta periódica de ventanas y las comprobaciones de overlays siguen
  siendo necesarias mientras se usan. La consulta de WMS ya suspende sus dumps
  tras vencer la demanda. El monitor FPS consulta SurfaceFlinger únicamente
  mientras se muestra; mostrarlo tiene un coste adicional deliberado.

## Verificación

Se añadieron casos de cámara sin contacto, reinicio pendiente y fase temporal;
10.000 lotes táctiles que regresan a su posición; 100.000 posiciones de cursor;
buffers de latencia tras múltiples vueltas y reinicios; y 1.000 alternancias de
monitor con una muestra bloqueada. También se comprueban entregas antiguas,
cancelación, excepciones y pausa/reanudación de Flutter.

Resultados de la etapa inicial de optimización, antes de los cambios posteriores
de interfaz, cierre de sesión y repetición en bucle:

- `scripts/Build.ps1 -Release`: completado correctamente; incluye análisis
  Flutter, pruebas, lint Android y construcción del APK.
- Flutter: análisis sin incidencias y **81 pruebas correctas**.
- JVM: **858 pruebas, cero fallos y cero omitidas** en los informes de los módulos
  comprobados por el script; Gradle reutilizó tareas que seguían vigentes.
- Herramientas Python: **21 pruebas correctas**; comprobación de arquitectura y
  ABI JSON de producción correctas.
- C++: **16 comprobaciones correctas** con g++ en Debian/WSL. Incluyen espera
  real, parada, renovación, vencimiento sin consumidor y parada previa a la
  espera. El harness también pasó 25 repeticiones consecutivas, sin fallos.
- APK release construido para las tres arquitecturas configuradas. Comprobación
  de sus **15 bibliotecas nativas** correcta, incluido el requisito de páginas
  de 16 KB en las arquitecturas de 64 bits.
- `git diff --check`: correcto.

APK: `build/app/outputs/flutter-apk/app-release.apk`. Logs de la ejecución completa
y del harness: `build/performance-release-build.log` y
`build/performance-native-tests.json`. Un intento inicial de lint encontró rutas
Windows sin escapar en `android/local.properties`; el script oficial las
normalizó y la ejecución final de lint pasó.

### Comprobación posterior de la actualización

La compilación local que incorporó las mejoras de cierre e interfaz y el bucle
de repetición aprobó **103 pruebas Flutter** y **901 pruebas JVM**, con cero
fallos u omitidas en los informes de los módulos. `scripts/Build.ps1 -Release`
completó análisis, pruebas, lint y APK; el guard de ABI JSON inspeccionó 139
clases. La compilación desde cero y los mismos controles se completaron también
con el identificador nuevo `dev.uwumouse.app` de 0.3.4. Las 16 comprobaciones
C++ de captura volvieron a pasar con g++ en Debian/WSL.

El APK se instaló en el teléfono y se comprobó el bucle con un perfil temporal
en memoria y el receptor InputLab independiente. Continuó después de soltar,
venció el temporizador de un segundo, se detuvo con otra pulsación y se canceló
al pausar. El receptor acumuló **47 eventos táctiles**, cero errores de secuencia
y cero contactos pendientes. El motor terminó sin tareas de repetición activas;
los perfiles guardados se conservaron y la app de laboratorio temporal se retiró.
Fue entrada sintética a través del compositor e inyector reales, no una partida
de varias horas ni una medición de latencia hasta la imagen.

## Validación física pendiente

### Cámara reversible y entrada bajo carga: 0.3.6+44

La revisión del 10 de noviembre encontró un residuo de aproximadamente 3,97
píxeles táctiles en una pareja `+100, -100` de Free Fire, debido a la aproximación
por muestra de una ganancia dependiente de la posición. La integración reversible
de la curva y su suma compensada eliminan ese residuo sobre entradas admitidas.
También se consume la primera muestra después de confirmar el DOWN, incluida
la vuelta de Suspender. No se modifica la sensibilidad guardada; el recorrido de
reportes grandes cambia respecto de la aproximación anterior.

Las muestras aisladas ya usan la misma admisión directa que los lotes, sin arrays
por muestra. Bajo carga, el consumidor une hasta 256 muestras contiguas sin
cruzar controles, modo o generación. La cámara descarta movimiento con más de
100 ms desde su timestamp `CLOCK_MONOTONIC`, conserva las muestras recientes
y limpia el historial de precisión sin soltar M1. Los registros de edad tienen
capacidad fija y distinguen muestras vencidas de timestamps inválidos.

`scripts/Build.ps1 -Release` completó análisis, **103 pruebas Flutter y 932 JVM**,
lint de todos los módulos, APK, ABI JSON de 143 clases y alineación de 16 KB.
Un harness del controlador actual procesó **36 millones de muestras**, con dos
horas virtuales por caso a 250/1000 Hz, lotes de 1/4 y precisión activada y
desactivada. Los lotes producen las mismas coordenadas finales. Las parejas
opuestas de 1, 10, 100 y 400 unidades tuvieron residuo táctil cero; se verificaron
además saturación, inversión y renovación. Es una simulación de algoritmo en el
ordenador, sin planificación Android, temperatura ni latencia hasta imagen.

En el Samsung Android 14 con DeX conectado, el diagnóstico `labCamera` utilizó
un perfil RAM y el receptor InputLab independiente en las pantallas **0 y 2**.
Cada prueba recibió **23 eventos**, hasta tres contactos, cero errores y cero
contactos pendientes. Los marcadores de transiciones confirmaron el retorno
exacto tras la pareja opuesta y el lote, descarte de una muestra de 300 ms y
renovación de cámara con el joystick inmóvil. No se inyectó esta secuencia en un
juego ni se modificaron perfiles guardados. Evidencia privada en
`evidence/camera-0.3.6/`; validación local en
`build/camera-optimized-final-validation.log` y
`build/camera-optimized-results.jsonl`.

### Partidas prolongadas

La revisión y las pruebas automáticas no certifican estabilidad durante horas.
Falta ejecutar una partida prolongada con el APK optimizado y el mismo perfil,
frecuencia, periféricos y condiciones térmicas, comparando intervalos equivalentes
con monitor FPS activado y desactivado.

Conviene registrar CPU y memoria de los procesos propios, número de hilos,
profundidad y edad de cola, percentiles de IPC, renovaciones de captura y estado
de contactos. Los scripts existentes `scripts/Measure-OwnProcesses.ps1`,
`scripts/Measure-InputLatency.ps1` y `scripts/Measure-NativeCaptureRate.ps1`
permiten observaciones delimitadas; sus percentiles internos no miden la latencia
desde el ratón hasta la imagen.

En esa sesión deben comprobarse pausas largas, WASD/M1/M2 simultáneos, cambio
cámara/cursor, menú, guardar/cancelar edición, pérdida de foco, desconexión del
periférico y cierre sin contactos pendientes. La instalación y la prueba breve
de repetición descritas arriba no sustituyen esa observación prolongada.
