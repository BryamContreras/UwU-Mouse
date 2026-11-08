# Android presentation

This Android library renders the editor, floating menu, mapped controls and visual
cursor. It depends on the core models and Android Views; it has no capture,
injection, session coordinator or profile-storage implementation.

`EditorPresentationHost` supplies explicit editor admission and detached window
geometry. `BubbleSettingsPort` supplies the existing settings persistence adapter.
Composition must pass both dependencies; presentation does not discover singleton
services or read accessibility windows.

All windows use the standard `TYPE_APPLICATION_OVERLAY` on every display. The host
must request the standard overlay permission and create an appropriate window
context. `OverlayWindows.requirePermission` applies to the phone and external
displays alike. Passive layers retain the combined-opacity budget.

Kotlin package names are preserved for compatibility. Android drawable resources
belong to `dev.mobimapper.presentation.R`; launcher assets remain in the app.
