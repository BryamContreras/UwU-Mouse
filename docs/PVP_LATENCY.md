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

El drenaje compartido de main preserva el orden de los callbacks nativos. Puede
procesar callbacks añadidos después de mensajes externos de UI que siguen en el
Handler; cede tras 64 callbacks o comprobar 4 ms consumidos. Una operación
síncrona puede superar ese presupuesto. El cambio reduce mensajes de Handler y
mantiene la dependencia de main para los bordes; no elimina la espera de foco,
el ACK ni la inyección de cada evento.
