# Activar UwU Mouse desde la PC

UwU Mouse necesita **Motor de toques**, **Captura nativa** y el permiso **Mostrar sobre otras apps**. Puedes iniciar el motor en el propio teléfono mediante Depuración inalámbrica y un emparejamiento visible de Android: [activar sin PC](ACTIVAR_TELEFONO.md). Este documento describe la alternativa desde Windows. Encender Depuración inalámbrica no inicia el motor por sí solo; debes completar una de las dos rutas de activación. La aplicación actual no requiere accesibilidad.

## Pasos

1. Desbloquea el teléfono y activa **Opciones de desarrollador → Depuración inalámbrica**. El teléfono y la PC deben estar en la misma red Wi-Fi y tener el emparejamiento ADB autorizado.
2. En Windows, abre el acceso directo **Activar UwU Mouse** si está preparado en esa PC. También puedes hacer doble clic en **Activar-UwU-Mouse.cmd**, dentro de la carpeta del proyecto. Espera **Motor de toques activo y verificado** y **Captura nativa preparada**. El activador abre UwU Mouse.
3. En **Ajustes → Conexión → Comprobar servicio**, verifica motor, captura nativa y **Mostrar sobre otras apps**, también en el teléfono. Si el APK se acaba de actualizar, cierra el backend anterior y vuelve a activarlo. **Comprobar servicio solo consulta el estado; no arranca el motor ni inicia una sesión.**
4. Vuelve a la biblioteca y toca **Free Fire**, o el juego que tengas configurado. Se utilizará el perfil seleccionado; si aún no tiene calibración, se abrirá el editor para configurarlo.

El motor corre localmente en el teléfono; la PC se utiliza para arrancarlo. No hace falta repetir la activación cada vez que conectas el teclado o el ratón. Repítela cuando el servicio esté apagado, por ejemplo después de un reinicio o si el proceso se pierde.

Desde +34, **InputLab no es obligatorio** para arrancar el motor ni para jugar. Es un receptor independiente utilizado en las pruebas de desarrollo.

## Conservar tus perfiles

Para actualizar, instala el APK nuevo con la misma firma **encima de la versión existente, sin desinstalarla**. Desinstalar o borrar los datos elimina los perfiles locales. En ese caso hace falta recuperar un respaldo disponible o configurarlos de nuevo; activar el servicio no recupera esos datos automáticamente.

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
