# Activar UwU Mouse desde la PC

UwU Mouse necesita su motor local autorizado y el permiso **Mostrar sobre otras apps**. La captura de teclado y ratón se gestiona automáticamente durante la sesión. Puedes iniciar el motor en el propio teléfono mediante Depuración inalámbrica y un emparejamiento visible de Android: [activar sin PC](ACTIVAR_TELEFONO.md). Este documento describe la alternativa desde Windows. Encender la depuración no inicia el motor por sí solo; debes completar una ruta de activación. La aplicación actual no requiere accesibilidad.

## Pasos

1. Desbloquea el teléfono y prepara una conexión ADB autorizada: **Depuración USB** con un cable de datos a la PC, o **Depuración inalámbrica** con teléfono y PC en la misma red Wi-Fi. Acepta la autorización que muestre Android; para Wi-Fi completa también el emparejamiento si hace falta.
2. En Windows, abre el acceso directo **Activar UwU Mouse** si está preparado en esa PC. También puedes hacer doble clic en **Activar-UwU-Mouse.cmd**, dentro de la carpeta del proyecto. Espera **Motor de toques activo y verificado** y **Captura nativa preparada**. El activador abre UwU Mouse.
3. En **Ajustes → Conexión**, permite **Mostrar sobre otras apps** y usa **Comprobar servicio** para consultar el estado. La pantalla indica si falta algún requisito y muestra **Listo para abrir tu juego** cuando corresponde. Si el APK se acaba de actualizar, cierra el backend anterior y vuelve a activarlo. **Comprobar servicio no arranca el motor ni inicia una sesión.**
4. Vuelve a la biblioteca y toca **Free Fire**, o el juego que tengas configurado. Se utilizará el perfil seleccionado; si aún no tiene calibración, se abrirá el editor para configurarlo.

El motor corre localmente en el teléfono; la PC se utiliza para arrancarlo. No hace falta repetir la activación cada vez que conectas el teclado o el ratón. Repítela cuando el servicio esté apagado, por ejemplo después de un reinicio o si el proceso se pierde.

Si activas por USB, después puedes desconectar la PC y conectar el hub con el teclado y el ratón para jugar. Un hub con esos periféricos no ejecuta ADB por sí solo: activar **Depuración USB** no sustituye al equipo que envía el comando. Si quieres mantener el hub conectado y activar sin PC, usa la [ruta inalámbrica local](ACTIVAR_TELEFONO.md).

Desde +34, **InputLab no es obligatorio** para arrancar el motor ni para jugar. Es un receptor independiente utilizado en las pruebas de desarrollo.

## Conservar tus perfiles

Para cambiar a **0.3.4**, exporta primero los perfiles de la instalación anterior y sigue la [guía de migración](MIGRACION_0_3_4.md): el nuevo identificador `dev.uwumouse.app` tiene datos y permisos separados.

En actualizaciones posteriores del mismo identificador, instala el APK nuevo con la misma firma **encima de la versión existente, sin desinstalarla**. Desinstalar o borrar los datos elimina los perfiles locales. En ese caso hace falta recuperar un respaldo disponible o configurarlos de nuevo; activar el servicio no recupera esos datos automáticamente.

## Si no conecta

Si no aparece ningún teléfono, comprueba la red y la autorización. Puedes usar `adb connect <IP>:<puerto>` con la dirección de la pantalla principal de Depuración inalámbrica, no con el puerto de emparejamiento. Si es la primera conexión de esa PC, completa antes el emparejamiento ADB.

Si hay varios teléfonos, el activador no elige uno al azar. Desde PowerShell puedes indicar el dispositivo:

```powershell
.\scripts\Start-UwUMouse.ps1 -Serial '<serial-del-telefono>'
```

Para consultar el estado sin abrir pantallas ni iniciar el motor:

```powershell
.\scripts\Start-UwUMouse.ps1 -CheckOnly
```

El activador ignora emuladores al seleccionar automáticamente y reconoce anuncios mDNS duplicados por la identidad de hardware. Conserva una sesión o editor en curso y no reemplaza un motor existente al que no pueda reconectar. Una liberación pendiente impide activar otra copia. No modifica perfiles ni permisos, y no comienza acciones del juego.
