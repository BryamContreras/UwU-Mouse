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

Android drawable resources belong to the presentation module; launcher assets
remain in the app. Version 0.3.4 uses the UwU Mouse namespace consistently.

Repeat action settings expose **Mantener en bucle** and, only when enabled,
**Temporizador (segundos)**. The timer accepts integer values 0–86400 (zero means
no timer); invalid text blocks Save and returns focus to the owning action.
Disabling the loop keeps its valid timer value for later use. The numeric pad,
interval input and marker drawing stay inside this native editor.

The controls, cursor and optional FPS/battery monitor use bounded drawing and
position updates. Presentation does not create an injection or capture loop.
