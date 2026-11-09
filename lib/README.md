# Interfaz Flutter

Flutter es el plano de control: biblioteca de juegos, perfiles, permisos,
activación y estado. Los eventos físicos y la inyección de contactos no cruzan
Flutter ni el canal de métodos.

| Carpeta | Responsabilidad |
|---|---|
| `app/` | Composición de dependencias, aplicación y tema |
| `application/ports/` | Contrato `MapperGateway`, sin dependencias de Flutter |
| `application/models/` | Resúmenes y estado decodificados del plano de control |
| `application/controllers/` | Coordinación de arranque y reactivación de perfiles |
| `application/policies/` | Reglas compartidas de preparación de sesión |
| `platform/android/` | Adaptador `MethodChannelMapperGateway` para Android |
| `features/library/presentation/` | Biblioteca, perfiles y sus diálogos |
| `features/connection/presentation/` | Permisos, diagnóstico y activación local |
| `features/settings/presentation/` | Acceso a Conexión y selección del estilo del cursor |
| `shared/widgets/` | Elementos visuales compartidos |

`UwUMouseApp` es la raíz de composición. Selecciona el adaptador Android por
defecto y admite otro `MapperGateway` por constructor. Las pantallas y los
controladores consumen ese contrato; no crean canales ni importan el adaptador.
Para reemplazar el transporte, implementar el puerto y cambiar esa composición.
El adaptador conserva `dev.uwumouse/control.v1`, nombres de comandos y mapas
del protocolo existente.

`ProfileLauncher` coordina exclusivamente una pulsación explícita del usuario.
El widget conserva la propiedad del ciclo de vida y comunica si la solicitud
sigue vigente. Volver de segundo plano no revive una solicitud cancelada. Una
operación de emparejamiento iniciada por otra acción conserva su autorización.

La preparación de captura procede de `captureReady`, `captureBackend` y
`captureError`. El campo histórico `accessibility` puede recibirse para
diagnóstico, pero no habilita controles ni se ofrece como permiso en la interfaz.
El permiso `overlayPermission` se exige en teléfono y pantallas externas; un
valor explícito tiene prioridad sobre el antiguo `drawOverlays`.

El contrato no presupone que captura, inyección o activación compartan proceso.
Cambiar esos módulos nativos requiere conservar o versionar el plano de control,
no modificar las pantallas para cada implementación.

Inicio muestra accesos a los ajustes reales de Depuración inalámbrica y Mostrar
sobre otras apps. Los indicadores diferencian activo, inactivo y desconocido.
La tarjeta Cerrar sesión aparece solo mientras existe una sesión y mantiene la
acción disponible durante bloqueos o cierres pendientes; una lectura anterior
al cierre no confirma su resultado ni reactiva un inicio cancelado.

La depuración inalámbrica también se configura desde Ajustes → Conexión. Se
abre Android en lugar de representar un interruptor local que no cambie el
estado del sistema. El motor se ofrece para activación cuando falta y la
captura permanece gestionada por el ciclo de vida nativo.

Las pruebas de widgets y protocolo están en `test/`; sus datos comunes están en
`test/support/`. `mapper_gateway_injection_test.dart` comprueba la interfaz sin
canal Android, el rechazo de una captura ausente y el permiso de superposición
también en el teléfono.
