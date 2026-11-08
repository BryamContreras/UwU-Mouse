# Activar UwU Mouse en el teléfono

Desde **0.2.0+34**, la app incorpora emparejamiento ADB y arranque del motor desde el propio teléfono. Requiere **Android 14 o posterior**, conexión Wi-Fi y acceso a **Opciones de desarrollador → Depuración inalámbrica**. En el Samsung se comprobaron el código introducido desde la notificación, la autorización conservada al actualizar y el arranque local mediante **Reactivar sin código**. InputLab y una PC son opcionales.

## Primera activación

1. Cierra cualquier sesión de mapeo y deja todas las teclas y botones sueltos. En UwU Mouse abre **Ajustes → Conexión → Activar en este teléfono**.
2. Permite las notificaciones si Android lo solicita. Toca **Abrir Depuración inalámbrica**, actívala y acepta la autorización de red que muestre Android. En algunos Samsung se abre **Opciones de desarrollador**; entra desde allí en **Depuración inalámbrica**.
3. En esa pantalla elige **Emparejar dispositivo con código**. Mantén abierta la ventana que muestra los seis dígitos y su puerto.
4. Abre la notificación **UwU Mouse · activar sin PC**, toca **Introducir código**, escribe los seis dígitos actuales y envíalos. UwU Mouse detecta el puerto, empareja e inicia el motor local.
5. Vuelve a UwU Mouse y espera **Motor activo**. En Conexión comprueba **Captura nativa** y permite **Mostrar sobre otras apps** en el teléfono o en DeX. Esta versión no requiere activar accesibilidad. Si actualizaste el APK, reinicia el backend anterior antes de probar la captura nueva.
6. Vuelve a la biblioteca y toca el juego. La activación por sí sola no inicia una sesión ni ejecuta controles.

Si prefieres no usar la notificación, abre UwU Mouse y los ajustes de Android en **pantalla dividida**. Conserva visible la ventana del código, escribe sus seis dígitos y toca **Emparejar y activar** en el formulario de la app. Si el puerto no se detecta, copia **Puerto de emparejamiento** de esa misma ventana.

## Reactivar después

Después de emparejar una vez, **toca el juego en tu biblioteca**. Si el motor está apagado y Depuración inalámbrica está activa, UwU Mouse intenta reconectar y activarlo antes de abrir el juego con tu perfil. También puedes usar **Activar en este teléfono → Reactivar sin código**. Se reutiliza la identidad guardada mientras Android conserve su autorización. Si Android la olvidó o revocaste el emparejamiento, genera un código nuevo y repite la primera activación.

El motor corre localmente. No necesitas reactivar cada vez que conectas el teclado o el ratón. Una reactivación no reproduce las teclas que estaban mantenidas. El juego solo se abre si lo tocas; no se lanza por sí solo tras reiniciar ni al detectar los periféricos.

La secuencia completa después de reiniciar físicamente el teléfono y después de revocar el emparejamiento sigue pendiente de prueba en esta entrega. La autorización guardada se comprobó al actualizar la app; no se presenta como una prueba de reinicio.

## Si no detecta o no conecta

- Comprueba que Depuración inalámbrica sigue activa y el teléfono está conectado a Wi-Fi. En el panel de UwU Mouse usa **Si no detecta el servicio → Buscar de nuevo**.
- Para emparejar, usa el código y el puerto de **Emparejar dispositivo con código**; ambos pueden cambiar al abrir otra ventana. Para reactivar, usa **Puerto de conexión**, mostrado en la pantalla principal de Depuración inalámbrica. Son puertos distintos.
- Si caducó el código, abre uno nuevo. Si cancelas, se cierra el intento; el motor ya activo no se detiene. Una sesión o una liberación de contactos pendiente impide iniciar otro intento.
- También está disponible el [activador desde la PC](ACTIVAR_PC.md).

## Perfiles y actualizaciones

La activación conserva los perfiles y las asignaciones. Actualiza instalando el APK con el mismo certificado **encima de la versión existente, sin desinstalarla**. Desinstalar o borrar los datos elimina perfiles e identidad ADB local; una activación nueva no recupera esos datos por sí sola.
