# Prueba de retorno de cámara en Free Fire

Este informe registra una comparación controlada del APK instalado de UwU Mouse,
con **Optimización Mejorada FreeFire** desactivada y activada.

| Desviación de retorno del minimapa | Normal, apagada | Optimización, activada |
|---|---:|---:|
| Giro y regreso rápidos | 3,02° (2,61–3,83°) | 2,34° (1,69–3,48°) |
| Recorrido previo acumulado | 24,84° (23,18–25,95°) | 3,58° (2,18–5,80°) |

Los valores muestran la media del error angular absoluto del minimapa y el rango
observado en tres repeticiones por condición. Los rangos del giro rápido se
solapan, así que esa diferencia pequeña no es concluyente. Los rangos del
recorrido acumulado quedaron separados en estas pruebas. La muestra es
descriptiva y no establece un resultado universal ni estadístico.

## Configuración de la prueba

- Samsung S25 Ultra, Android 17, Samsung DeX a 2560×1440.
- UwU Mouse **0.3.14+53** instalado, SHA-256:
  `ecbd8b176e2853aa460b49938a30d375451be19dcce625614dfea20d67b25be6`.
- Mismo perfil, sensibilidad X 0,45 / Y 1,35, densidad efectiva 160 DPI,
  captura solicitada de cámara a 500 Hz y mira precisa desactivada en ambos modos.
- El único ajuste del perfil que cambió entre condiciones fue el switch de
  optimización mejorada.
- Los movimientos provinieron de un dispositivo UHID sintético temporal y
  pasaron por la captura, el procesamiento y la inyección del APK instalado
  hacia Free Fire. Esto no mide la latencia de un mouse USB físico.
- La secuencia rápida usó 12 informes; la secuencia acumulada usó 340. Ambas
  terminaron con desplazamiento neto X/Y igual a cero. Tras cada movimiento,
  se salió del modo cámara con el atajo real y se capturó el minimapa una vez
  asentado.
- Los 2.112 informes se procesaron sin pérdidas ni expiraciones. Dos estimadores
  visuales independientes y congelados aprobaron sus controles de calidad en
  las 12 pruebas válidas; la discrepancia máxima fue 0,32°.
- Se abortó un intento de preparación al detectar movimiento físico antes de
  iniciar la secuencia. Se conservó como inválido, se excluyó del cálculo y se
  repitió con una etiqueta distinta. Se incluyeron tres pruebas válidas por
  condición.

Estas cifras miden error de rotación del minimapa; no representan yaw 3D
calibrado, latencia desde el mouse hasta los fotones, todos los dispositivos ni
una partida larga. No miden retraso: cero grados significa regresar a la
orientación visual inicial.

La tabla de referencia anterior (3,8° / 3,0° en giro rápido y 23,4° / 8,1° con
recorrido acumulado) provino de reproducir fórmulas de coordenadas con otro
inyector a 600 DPI. No se midió con el APK de GG Mouse. Esta prueba usa el APK
completo de UwU a 160 DPI; por ello no replica numéricamente la tabla ni es una
comparación directa con GG Mouse.

Al terminar, se restauraron los perfiles originales con la optimización apagada;
se retiraron el dispositivo UHID y el proceso temporal, se liberaron todos los
contactos y Free Fire quedó en Entrenamiento con el cursor de UwU libre.

Las capturas y manifiestos completos permanecen en la carpeta local ignorada
`evidence/camera-installed-return-20261116/` del equipo de prueba.
