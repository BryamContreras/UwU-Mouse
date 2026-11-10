# Activate UwU Mouse on the phone

UwU Mouse can pair with wireless debugging and start its local engine without a PC. Android 14 or later, Wi-Fi, and access to **Developer options → Wireless debugging** are required. The app currently uses Spanish labels, shown below in bold. [Spanish guide](ACTIVAR_TELEFONO.md).

## First activation

1. Close the mapping session from Home and release all keys and mouse buttons. In UwU Mouse, open **Ajustes → Conexión → Activar motor** and choose **Activar en este teléfono**.
2. Allow notifications if prompted. Open wireless debugging with **Abrir Depuración inalámbrica**, enable it and approve the network authorization. On some Samsung devices this opens Developer options first; enter Wireless debugging from there.
3. Choose the Android option to pair a device with a pairing code. Keep the screen showing the six-digit code and pairing port open.
4. Open the **UwU Mouse · activar sin PC** notification, tap **Introducir código**, enter the current six digits and submit. The app detects the port, pairs and starts the local engine.
5. Return to the app and wait for **Motor activo**. Grant **Mostrar sobre otras apps** from Home or Connection, including when using DeX. **Comprobar servicio** checks the state; it does not start the engine or a game session.
6. Return to the library and tap a game. Activation itself does not launch a game or execute controls.

Alternatively, use Android split screen to keep the pairing code visible while entering it in the app. If the port is not detected, copy the pairing port shown by Android into the form.

## Reactivation

After pairing, tapping a game can reconnect and activate the engine if wireless debugging remains available. You can also choose **Activar en este teléfono → Reactivar sin código**. If Android has revoked or forgotten the authorization, pair again with a new code. Restarting the phone or losing the backend may require reactivation. After updating the APK, close the old backend and activate it again so it loads the new code.

Input capture is managed automatically during a session. The app does not require an accessibility service, and reactivation does not replay held keys or launch a game without a user action.
