# Arquitectura de UwU Mouse

UwU Mouse procesa teclado y ratón localmente en Android. Flutter sirve para la biblioteca, los perfiles y los ajustes generales. Un servicio de accesibilidad y código Kotlin gestionan la captura autorizada, el editor nativo y las transiciones de sesión. El nombre histórico del paquete se conserva para mantener la identidad de las instalaciones existentes.

```mermaid
flowchart LR
    UI[Flutter: juegos y perfiles] --> Coordinator[Coordinador Kotlin]
    Editor[Editor nativo sobre el juego] --> Coordinator
    Input[Teclado y ratón] --> Engine[Motor Kotlin y cola acotada]
    Coordinator --> Engine
    Engine --> Composer[Compositor multitáctil]
    Composer --> Guardian[Guardián UID 2000]
    Guardian --> Worker[Worker de inyección]
    Worker --> Android[Android: destino autorizado]
```

## Perfiles y edición

Cada perfil tiene identidad propia, nombre, paquete de destino, versión de esquema, orientación y calibración. Guarda joystick, región y sensibilidad de cámara, acciones, tamaños, macros y atajos. Varios perfiles pueden pertenecer al mismo juego y conservar mapeos independientes.

El almacén local usa una única representación validada y escritura atómica. Las versiones anteriores se migran al cargar sin sobrescribirlas implícitamente. Antes de iniciar, se compila el perfil a coordenadas y bindings preparados; los eventos de ratón no leen archivos ni bases de datos.

El editor modifica un borrador. Entrar en edición pausa el mapeo y libera contactos; los clics de edición no se convierten a la vez en acciones del juego. Guardar valida y aplica mediante una transición segura; Cancelar conserva el perfil anterior. La paleta incluye los controles y sus comandos de Guardar/Cancelar.

Las teclas opcionales de selección de perfil se validan contra las asignaciones del juego y entre perfiles para evitar que una tecla cambie el perfil y ejecute simultáneamente un mapeo. Cambiar de perfil termina las acciones anteriores y no reproduce teclas que seguían mantenidas.

## Estados y destino

Los modos distinguen detenido, edición, juego, recuperación y bloqueo. El destino debe estar confirmado por el contexto de ventanas, paquete y calibración. Una ventana del editor propio se diferencia de un cambio de aplicación. Salir del contexto, perder la pantalla o invalidar la orientación pausa el motor.

La burbuja y los controles visibles son ventanas de accesibilidad propias. El HUD de mapeos es solo presentación, no recibe toques. Restaurar la presentación al volver al destino no reanuda automáticamente la sesión o las entradas mantenidas.

## Entrada y recuperación

El motor comparte un compositor de hasta diez contactos entre joystick, cámara, acciones, macros y comandos. Repetir funciona mientras se mantiene su activador y cancela las tareas al soltar o pausar. Sus intervalos son nominales: los periodos atrasados se omiten en vez de acumular una ráfaga.

El backend se activa mediante ADB autorizado como UID 2000 y comprueba el permiso efectivo de inyección. En +34, `LocalActivation` implementa descubrimiento local mDNS, emparejamiento TLS mediante el código de Android y un comando fijo que arranca las clases del APK instalado. La notificación permite escribir el código sin cerrar la ventana de emparejamiento; también existe un formulario para pantalla dividida. En dispositivos sin actividad directa para Depuración inalámbrica, el botón abre Opciones de desarrollador. La app sigue requiriendo Android 14/API 34 o posterior, aunque el protocolo de Depuración inalámbrica exista desde Android 11. [Guía local](ACTIVAR_TELEFONO.md), [alternativa PC](ACTIVAR_PC.md).

La identidad ADB se guarda en archivos privados mediante escritura atómica y cifrado AES-GCM con una clave de Android Keystore. El código de seis dígitos solo se usa en memoria. La app restringe los destinos de activación al mismo teléfono; no expone un terminal ni comandos arbitrarios. Un guardián vivo se reconecta antes de intentar arrancar otro, y un bloqueo de arranque evita duplicados. El comando se transporta mediante `shell,v2,raw`, con un parser acotado que valida las tramas de salida y exige el estado de salida. Se exige sesión cerrada, ausencia de contactos y liberación verificada antes de activar; se admite detenido o una pausa vacía después de cerrar la sesión. Cancelar cierra el transporte de activación y no detiene un guardián que ya esté activo.

No se necesita root ni tener InputLab instalado. El arranque deja el guardián inactivo, sin worker ni sesión; el destino real se resuelve y autoriza al iniciar explícitamente un perfil. La activación no altera los perfiles. Tras reiniciar puede hacer falta reactivar; si Android conserva la autorización, se reutiliza la identidad sin pedir otro código.

Al tocar un juego, la app comprueba el backend e intenta reactivarlo con la identidad guardada si hace falta y Depuración inalámbrica continúa activa. Perder el Binder invalida un estado anterior de «Motor activo». Este recorrido requiere la pulsación del usuario; no lanza juegos ni reproduce acciones al arrancar el teléfono.

El guardián conserva la secuencia y el estado necesario para sustituir al worker y emitir la cancelación correspondiente sin reiniciar al receptor. Si se pierde por completo la capacidad de inyección, la app no puede garantizar la cancelación: mantiene la sesión bloqueada y no continúa inyectando. La incorporación de activación no modifica el journal ni la composición de contactos.

Detener retira la captura y las superposiciones de sesión. Que el servicio de accesibilidad siga autorizado no significa que deba consumir teclado o ratón cuando no hay una sesión activa.

## Verificación

Las pruebas Kotlin cubren validación, persistencia, coordenadas, transiciones, composición y recuperación. InputLab es un receptor Android independiente y opcional que permite observar eventos y contactos de una secuencia. Las pruebas Flutter cubren el contrato del canal nativo y recorridos de la biblioteca. En +34 se comprobaron en el Samsung el emparejamiento por notificación, la identidad conservada al actualizar y la reactivación desde la app hasta backend UID2000/protocolo4/INJECT_EVENTS, sin arrancarlo desde la PC. Siguen pendientes reinicio físico, revocación y ejecución con InputLab realmente ausente. Este resultado de activación no equivale a una nueva prueba de juego ni de rendimiento.

Los juegos cerrados se evalúan por comportamiento visible y registros del propio mapeador; no se atribuyen lecturas de eventos internos al juego. Un ACK del inyector no demuestra que haya ocurrido la acción esperada. Las latencias de cola o IPC son internas, no latencia completa hasta imagen. El [workflow Android](CI_ANDROID.md) comprueba la compilación y las pruebas automatizadas, y genera APK separados por ABI.
