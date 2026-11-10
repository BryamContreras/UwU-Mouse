# Migrating to UwU Mouse 0.3.4 and later

Version 0.3.4 changed the Android application ID to `dev.uwumouse.app`. Android treats it as a new application, with separate data and permissions; a shared signing certificate does not migrate them. [Spanish guide](MIGRACION_0_3_4.md).

## Before installing

1. In the old app, export **each** profile to keep using **Exportar**. Save the generated JSON files somewhere accessible. Each export contains one profile and its calibration.
2. Close the mapping session from Home and wait for the session card to disappear. Resolve any pending closure before changing the engine.
3. Open **Ajustes → Conexión → Laboratorio y diagnóstico → Cerrar backend ADB** and stop the old backend.

Keep the old installation until the exported files have been imported and checked. Uninstalling it or clearing its data removes its local storage.

## Install and restore

1. Install the APK for the device's CPU architecture. Most recent phones use **arm64-v8a**; check the device if unsure.
2. Open the new app, grant **Mostrar sobre otras apps**, and allow notifications if using the notification-based pairing flow.
3. Activate the engine using the [phone](ACTIVATE_PHONE.md) or [PC](ACTIVATE_PC.md) method.
4. Add the corresponding game and use **Importar perfil** under **Nuevo perfil** to import each saved file. Android permissions are not included in profile exports.
5. Check the selected profile and calibration on the display where you will play. Changes in phone, resolution or window geometry can require recalibration. Reconfigure any general appearance preferences; ADB identity, permissions and all global preferences are not part of the profile export.

Once the new installation and backups are verified, the old application can be removed.

## Later updates

When the application ID and certificate are unchanged, install the new APK over the existing app without uninstalling. Stop the session and backend first, then reactivate the engine after updating.
