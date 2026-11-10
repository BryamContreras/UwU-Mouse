# Activate UwU Mouse from a Windows PC

The engine runs on the Android device; the computer supplies an authorized ADB connection to start it. Wireless on-device activation is also available: see the [phone activation guide](ACTIVATE_PHONE.md). [Spanish guide](ACTIVAR_PC.md).

1. Unlock the phone. Connect it to the PC with a data-capable USB cable and enable USB debugging, or pair the phone and PC over Wi-Fi with wireless debugging. Accept Android's authorization prompts.
2. On Windows, run the **Activar UwU Mouse** shortcut if configured, or open **Activar-UwU-Mouse.cmd** in the project directory. Wait for **Motor de toques activo y verificado** and **Captura nativa preparada**. The activator opens the app.
3. In **Ajustes → Conexión**, grant **Mostrar sobre otras apps** and use **Comprobar servicio** to check readiness. Checking does not start the engine or a session. If the APK was just updated, close the old backend and activate it again.
4. Return to the library and tap the game. The selected profile is used; the editor opens if calibration is needed.

If activated by USB, the PC can be disconnected afterward and the hub with keyboard and mouse connected for play. A hub by itself does not run ADB; simply enabling USB debugging does not start the engine. A restart or lost backend can require activation again. InputLab is a separate testing receiver and is not needed to play.

For upgrades from 0.3.3 or earlier, export profiles first and follow the [migration guide](MIGRATION_0_3_4.md).
