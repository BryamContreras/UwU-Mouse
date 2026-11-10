# Cambios de UwU Mouse

## 0.3.7+45 — 2026-11-10

- Sistema → Resolución personalizada: campos de ancho y alto para adaptar el tamaño lógico de la pantalla del juego, incluida la duplicación HDMI; Aplicar, Restaurar y consulta del tamaño confirmado.
- Conversión de las dimensiones del monitor usando la orientación del juego capturada antes de abrir los ajustes. Se mantiene la resolución física y no se fuerzan densidad ni rotación.
- El cambio detiene el mapeo, invalida preparaciones pendientes y retira la captura de ratón y teclado antes de modificar la pantalla. El perfil guardado no se reescribe; debe revisarse la calibración al volver al juego.
- Motor ADB: operaciones limitadas por pantalla e identidad, verificación del resultado y recuperación de la configuración anterior. Conserva los overrides previos y evita sobrescribir cambios externos posteriores.
- Registro privado para recuperación tras reiniciar el motor y restauración al cerrar el propietario, una vez liberadas las entradas. Sin consultas de resolución por frame ni procesos adicionales.
- Once pruebas de framework: nueva comprobación de cambio y restauración de una pantalla virtual propia sin alterar la principal ni otra pantalla virtual.

El ajuste afecta al tamaño lógico de la pantalla donde se ejecuta el juego. En duplicación puede cambiar también la imagen del teléfono; su efecto está sujeto a las capacidades del dispositivo y del adaptador de vídeo.

## 0.3.6+44 — 2026-11-10

- Cámara Free Fire: integración reversible de la curva de compensación. Las entradas opuestas equivalentes vuelven al mismo punto táctil y dividir un movimiento en muestras no altera su resultado.
- Free Fire consume la primera muestra tras confirmar el contacto, también al volver de Suspender; evita perder el inicio de un giro.
- Renovación del contacto de cámara al agotar el recorrido finito, conservando los demás dedos y la liberación verificada.
- Las muestras aisladas de ratón usan la misma admisión directa que los lotes, sin esperar al hilo de interfaz ni crear arrays por muestra.
- Agrupación acotada de lotes consecutivos bajo carga, conservando cada muestra, su tiempo y las barreras de teclas, botones y cambios de modo.
- Presupuesto de antigüedad de 100 ms para la cámara física: descarta movimiento obsoleto, conserva muestras recientes y expone métricas del recorrido desde el reloj nativo hasta el motor.
- Diagnóstico sintético en InputLab para inversiones, lotes, muestras antiguas y renovación con joystick, disponible en teléfono y pantalla externa sin modificar perfiles guardados.

Los giros grandes pueden sentirse distintos: se conserva la curva local de compensación, pero se elimina la aproximación anterior que dependía del tamaño de cada muestra. La igualdad táctil no garantiza por sí sola igualdad de orientación en cada juego.

## 0.3.5+43 — 2026-11-10

- Corrige el cierre de sesión en Samsung DeX cuando Android conserva únicamente un cursor de ratón con fuente combinada `TOUCHSCREEN | MOUSE`. Se exige que no haya contactos pulsados ni punteros interceptados y que la captura y todos los inyectores estén detenidos, igual que en el teléfono.
- El cierre automático puede usar una consulta válida de tareas aunque el escritorio no tenga una aplicación enfocada. Minimizar, cambiar de foco o mover el juego a otra pantalla conserva la pausa y no se interpreta como salir del juego.
- La geometría se consulta con un contexto de ventana de la pantalla solicitada. Evita heredar el tamaño de DeX al preparar el perfil del teléfono; conserva un único contexto reutilizable y lo descarta al desconectar su pantalla.
- Los diagnósticos conservan el resultado de tareas junto con la instantánea de ventanas para distinguir ausencia, inventario desconocido y pérdida de foco.
- Amplía las regresiones de pantalla principal y externa, y la suite de framework a diez comprobaciones, con cierre de overlays propios y desconexión de una pantalla virtual en Android API 34–37.
- Mantiene el identificador `dev.uwumouse.app` y el formato de perfiles de 0.3.4.

## 0.3.4+42 — 2026-11-09

Actualización de desarrollo con mejoras de rendimiento, cierre de sesiones, ajustes y repetición. Cambia el identificador Android a **`dev.uwumouse.app`**. Android la instala como una aplicación nueva: **exporta los perfiles desde la instalación anterior antes de cambiar, impórtalos en la nueva y vuelve a conceder los permisos**. El certificado compartido no migra datos entre identificadores. [Guía de migración](docs/MIGRACION_0_3_4.md).

### Repetición en bucle

- **Mantener en bucle** inicia Repetir con una pulsación y lo detiene con otra, sin mantener la tecla o botón pulsado.
- El **Temporizador (segundos)** aparece al habilitar el bucle. Admite enteros de **0 a 86400**; `0` deja el bucle sin límite. Cada nueva activación empieza el plazo de nuevo.
- Se conserva el intervalo de **30–5000 ms** y el modo anterior de mantener pulsado por defecto.
- Pausar, cerrar, cambiar de perfil o entrar al cursor cancela las tareas y libera los contactos. El temporizador usa la programación de los pulsos, sin añadir hilos ni ráfagas para recuperar intervalos perdidos.
- Los perfiles anteriores mantienen sus asignaciones y usan los valores predeterminados de los campos nuevos.

### Cierre de sesiones

- **Cerrar sesión** se encuentra en la tarjeta de sesión de Inicio y aparece únicamente mientras existe una sesión, incluido un cierre pendiente o una sesión bloqueada. La barra inferior conserva Inicio y Ajustes.
- El cierre puede solicitarse aunque haya una lectura de estado o un inicio pendiente; las respuestas anteriores no reactivan el mapeo.
- Se refuerza la reconciliación cuando un toque físico sustituye la secuencia inyectada. El cierre sigue pendiente hasta confirmar que Android no conserva contactos.
- La desaparición confirmada de la tarea y ventana del juego cierra la sesión automáticamente. Perder foco o minimizar conserva la pausa; si no puede comprobarse la salida, queda disponible el cierre manual.

### Interfaz y personalización

- Inicio muestra filas compactas de **Depuración inalámbrica** y **Mostrar sobre otras apps**, con check verde, X roja o estado neutral mientras se consulta Android.
- **Ajustes → Conexión** incluye el acceso a Android para encender o apagar la depuración inalámbrica. La activación del motor solo se ofrece cuando hace falta; se retiran los indicadores técnicos redundantes de captura y motor.
- La pestaña Ajustes permite elegir los estilos de cursor **Clásico, Oscuro, Cian y Rosa**.
- La burbuja ofrece **No molestar**, con acceso al permiso de Android y consulta de su estado.
- Se mejoran dibujo, posición y opacidad de controles, burbuja y monitor opcional de FPS y batería, manteniendo los límites de opacidad para que los toques lleguen al juego.

### Rendimiento y tareas de fondo

- Captura y watchdog esperan hasta un vencimiento o una señal, reduciendo despertares periódicos sin entrada.
- El cursor agrupa posiciones con un buzón y conserva una única entrega visual pendiente por frame.
- Se omiten relayouts, invalidaciones y movimientos táctiles redundantes, y se reutiliza geometría del editor.
- La cámara deja de programar comprobaciones de inactividad cuando no conserva contacto ni reinicio pendiente.
- El monitor limita su muestreo a una consulta en vuelo y cancela trabajo futuro al ocultarse; los timers de activación se detienen en segundo plano.
- Los buffers de latencia mantienen índices y tamaños acotados durante usos prolongados.

Estos cambios eliminan trabajo identificado en el código; no se publica una mejora medida de latencia hasta la imagen, temperatura o consumo. La estabilidad durante partidas de varias horas sigue requiriendo validación física. [Revisión de rendimiento](PERFORMANCE_REVIEW.md).

### Verificación

La validación local del identificador nuevo aprobó **103 pruebas Flutter**, **901 pruebas JVM**, análisis Flutter, lint Android y construcción release. Las **16 pruebas C++ de captura** pasaron con g++ en Linux. También pasaron los guards de arquitectura, ABI JSON y alineación nativa; sus alcances se describen en la documentación de CI.

En el teléfono se comprobó el bucle con un receptor InputLab independiente: continuación después de soltar, temporizador de un segundo, segunda pulsación y pausa; recibió **47 eventos táctiles**, con **cero errores de secuencia y cero contactos pendientes**. Es una prueba de entrada sintética sobre el recorrido real de inyección; no sustituye una prueba de teclado/ratón físico en todos los juegos. Los perfiles originales se conservaron.

La prueba se repitió desde un arranque en frío con el identificador nuevo: **42 eventos válidos**, cero errores y cero contactos o repeticiones pendientes. El diagnóstico espera a que termine de prepararse la captura antes de enviar sus entradas sintéticas. La migración mantuvo exactamente los perfiles y el perfil seleccionado.

GitHub Actions verifica los tres APK de desarrollo y la compatibilidad de framework en Android API 34, 35, 36 y 37. El release utiliza los APK y sus sumas SHA-256 de la ejecución del commit publicado; además de firma y arquitectura, se comprueban su identificador Android y versión.
