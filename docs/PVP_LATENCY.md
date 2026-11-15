# Medición de cámara y clics

Las comparaciones usan el mismo teléfono, perfil, pantalla, escena del juego y
frecuencia de captura. Registrar también la versión/firma del APK, batería y
estado térmico. Una partida física y una simulación de algoritmo tienen alcances
distintos.

Con una sesión estable de cámara, ejecutar una ventana por carga:

```powershell
.\scripts\Measure-CameraLatencyWindow.ps1 -Serial <serial> -Workload camera-only -TrialLabel before -Seconds 10 -PhysicalInputConfirmed -Destination evidence\before-camera
.\scripts\Measure-CameraLatencyWindow.ps1 -Serial <serial> -Workload camera-clicks -TrialLabel before -Seconds 10 -PhysicalInputConfirmed -Destination evidence\before-clicks
```

La primera carga requiere mover únicamente el ratón; la segunda añade clics M1.
Mantener el teclado sin pulsar para aislar el efecto. La herramienta no genera
entrada. Repetir después de actualizar y comparar directorios de la misma carga:

```powershell
python tools/analyze_camera_latency.py evidence\before-clicks evidence\after-clicks --output evidence\comparison.json
```

El análisis separa el timestamp original hasta el motor, recepción Binder hasta
el motor y envío sintético hasta ACK. Empareja ACKs por secuencia, acción y tiempo
del frame, sin confundir varios eventos creados en el mismo milisegundo. Ninguna
de esas cifras mide la presentación de imagen. Los percentiles de foco, pipe y
cola describen buffers independientes y no deben sumarse.

El recorder conserva 4096 filas. Bajo carga, una ventana de diez segundos puede
retener sólo su tramo final; el informe expone cuántas filas se sobrescribieron.
Los contadores entre snapshots cubren un intervalo mayor que ese tramo retenido.
La tasa de clics de esos contadores no se atribuye automáticamente a sus últimos
milisegundos. Una carga mal rotulada conserva sus tiempos útiles, pero se marca
como contaminada para la comparación.

Desde 0.3.11, el drenaje compartido de main preserva el orden de los callbacks nativos. Puede
procesar callbacks añadidos después de mensajes externos de UI que siguen en el
Handler; cede tras 64 callbacks o comprobar 4 ms consumidos. Una operación
síncrona puede superar ese presupuesto. El cambio reduce mensajes de Handler y
mantiene la dependencia de main para los bordes diferidos; no elimina la espera de foco,
el ACK ni la inyección de cada evento.

Desde 0.3.12, botones y teclas ordinarios del juego pueden pasar directamente a
la cola del motor. Una transición pendiente de menú, cursor o captura mantiene
la barrera de main. La admisión de un informe de mouse conserva juntos sus
muestras y bordes; el worker no espera a completar un lote futuro.

Si ya existe un MOVE pendiente antes de añadir o retirar un contacto, una sola
llamada Binder puede transportar los dos eventos. El worker los inyecta por
separado y confirma el primero antes de intentar el segundo. Los movimientos de
otro contacto, como un cambio diagonal del joystick, conservan el MOVE anterior
antes de cambiar coordenadas. No se elimina la confirmación ni se fusionan los
dos eventos Android.

`touchTransitionPairing` mide la llamada compuesta y se informa aparte de
`injectionPairing`. Al cambiar la población de las llamadas ordinarias, una
reducción en su mediana no demuestra por sí sola una mejora del recorrido
completo. La reutilización de foco tiene un límite de 8 ms desde el inicio de
la consulta y sólo dura esa operación; la identidad de la sesión y los metadatos
de ventana se comprueban otra vez para cada envío.

El método de diagnóstico `labTouchTransitions` dirige exclusivamente InputLab
con un perfil temporal en memoria. Genera cámara, M1 y WASD durante un intervalo
acotado, con limpieza final de contactos. La fuente intenta 250 callbacks por
segundo con cuatro marcas sintéticas por callback; expone retrasos y ticks
omitidos sin reproducirlos en ráfaga. Estos tiempos comienzan en la app y no
atraviesan USB ni captura evdev. El receptor independiente debe confirmar la
secuencia y los contactos finales; el estado interno del motor no lo sustituye.

Con InputLab ya instalado y el backend autorizado, ejecutar:

```powershell
.\scripts\Measure-TouchTransitionsLab.ps1 -Serial <serial> -DisplayId <id> -DurationMs 4000 -Destination evidence\lab-transitions
```

El script detiene el gesto anterior, abre únicamente InputLab en la pantalla
indicada y guarda su observación, los percentiles internos, temperatura y
contadores. No instala APK ni restaura el juego. Añadir `-Precision` para probar
mira precisa. No mezclar estos resultados con las ventanas físicas anteriores.

El vencimiento de sesión se cuenta desde la última entrada aceptada, con el
mismo presupuesto de 30 minutos. Los heartbeats no lo renuevan. Las pruebas con
reloj virtual cubren dos horas de actividad y el vencimiento exacto tras 30
minutos de inactividad; no equivalen a dos horas de juego físico.

Validación de 0.3.12+50 en S22 Ultra / Android 14 / DeX: ocho ventanas sintéticas
de cuatro segundos, tres con el motor 0.3.11 y cinco con 0.3.12, en orden
nuevo → anterior → nuevo. El control conserva 0.3.11 y añade exactamente la
misma fuente de laboratorio. Hubo 31.980 muestras generadas, 320 ciclos M1 y
512 bordes de tecla, sin entradas físicas durante las ventanas. Los cinco
ticks omitidos pertenecen al planificador de la fuente; no se reprodujeron en
ráfaga. Ninguna muestra admitida venció por antigüedad.

| Motor / mira precisa | Ventanas | Llamadas Binder / eventos táctiles planificados |
| --- | ---: | ---: |
| 0.3.11 / apagada | 2 | 935 / 935; 954 / 954 |
| 0.3.11 / encendida | 1 | 944 / 944 |
| 0.3.12 / apagada | 3 | 842 / 897; 879 / 933; 875 / 928 |
| 0.3.12 / encendida | 2 | 897 / 950; 893 / 949 |

Las llamadas compuestas evitaron 271 llamadas separadas: 5,8 % respecto a
una llamada por cada evento de esas mismas ejecuciones nuevas. Compartieron
250 observaciones de foco. Las ocho ventanas terminaron con cero errores de
secuencia y cero contactos en el receptor independiente, sin incremento neto
de descriptores abiertos; los buffers completos quedaron retenidos. Esto no
acredita ausencia de fugas durante horas.

Los tiempos variaron y no demuestran una reducción general de latencia:
la mediana de los grupos MOVE + borde estuvo entre 6,29–6,81 ms en el control
y 5,96–7,33 ms en el nuevo; sus p95 fueron 13,06–15,49 ms y 12,84–17,45 ms,
respectivamente. Los grupos no son pares causales de eventos idénticos. La
batería estuvo entre 31,4 y 32,8 °C; las pruebas no midieron USB, juego ni
presentación. No atribuir el cambio de una mediana de llamadas ordinarias al
recorrido completo.

El APK ARM64 procede de [Actions 43664185140](https://github.com/BryamContreras/UwU-Mouse/actions/runs/43664185140),
commit `24869fe`, con 1.112 pruebas JVM y 104 Flutter correctas. La
[matriz Android 14–17](https://github.com/BryamContreras/UwU-Mouse/actions/runs/43664187763)
aprobó catorce casos por API y uno adicional de permisos en API 37. La primera
ejecución perdió el emulador de API 37 tras cuatro casos, sin una aserción
fallida registrada; un único reintento reutilizó el mismo par de APK y aprobó
la suite completa. Las modificaciones posteriores son del script y esta
documentación; no cambian el código del APK instalado.
