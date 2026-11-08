import 'package:flutter/material.dart';

import '../../../application/ports/mapper_gateway.dart';
import '../../../application/policies/session_readiness.dart';
import '../../../shared/widgets/mobi_icon.dart';
import 'connection_indicators.dart';

/// Connection and diagnostics presentation. Commands are provided by the host.
class ConnectionSettingsPanel extends StatelessWidget {
  const ConnectionSettingsPanel({
    super.key,
    required this.status,
    required this.onCheck,
    required this.onActivate,
    required this.onCommand,
    required this.busy,
    this.checking = false,
    this.error,
  });

  final NativeStatus? status;
  final VoidCallback onCheck;
  final VoidCallback onActivate;
  final ValueChanged<NativeCommand> onCommand;
  final bool busy, checking;
  final String? error;

  bool _safeStatus(NativeStatus? value) => SessionReadiness.mayConfigure(value);
  bool _needsOverlayPermission(NativeStatus value) =>
      SessionReadiness.needsOverlayPermission(value);

  Widget _command(String label, IconData icon, NativeCommand command) =>
      OutlinedButton.icon(
        onPressed: busy ? null : () => onCommand(command),
        icon: MobiIcon(icon, size: 25),
        label: Text(label),
      );

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      _details(status),
      const SizedBox(height: 10),
      _laboratory(status),
    ],
  );

  Widget _details(NativeStatus? status) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      _connectionBlock('Captura nativa', status?.captureReady, feminine: true),
      const SizedBox(height: 10),
      _connectionBlock(
        'Motor de toques',
        status?.backend,
        action: status != null && !status.backend
            ? FilledButton.icon(
                onPressed: !_safeStatus(status) || status.active
                    ? null
                    : onActivate,
                icon: const MobiIcon(Icons.cable_rounded, size: 23),
                label: const Text('Activar motor'),
              )
            : null,
      ),
      if (status != null) ...[
        const SizedBox(height: 10),
        _overlayPermission(status),
      ],
      const SizedBox(height: 14),
      Align(
        alignment: Alignment.centerLeft,
        child: OutlinedButton.icon(
          style: OutlinedButton.styleFrom(
            visualDensity: VisualDensity.compact,
            textStyle: const TextStyle(fontSize: 13),
          ),
          onPressed: checking ? null : onCheck,
          icon: const MobiIcon(Icons.refresh_rounded, size: 22),
          label: Text(checking ? 'Comprobando…' : 'Comprobar servicio'),
        ),
      ),
      if (status != null &&
          status.captureReady &&
          status.backend &&
          !_needsOverlayPermission(status) &&
          _safeStatus(status) &&
          !status.active)
        const Text(
          'Listo para abrir tu juego',
          style: TextStyle(fontSize: 12, color: Color(0xff1f7a4f)),
        ),
      if (error != null) Text(error!),
      if (status?.backendError.isNotEmpty == true) Text(status!.backendError),
      if (status?.captureError.isNotEmpty == true) Text(status!.captureError),
      const SizedBox(height: 8),
      Text(
        status == null || status.devices.isEmpty
            ? 'Conecta teclado y ratón por OTG o Bluetooth.'
            : 'Dispositivos · ${status.devices.join(' · ')}',
        style: const TextStyle(fontSize: 12, color: Color(0xff527394)),
      ),
    ],
  );

  Widget _overlayPermission(NativeStatus status) => Container(
    key: const ValueKey('overlay-permission'),
    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
    decoration: BoxDecoration(
      color: const Color(0xfff0f8ff),
      border: Border.all(color: const Color(0xffc4e0f5)),
      borderRadius: BorderRadius.circular(16),
    ),
    child: Row(
      children: [
        connectionIcon(status.overlayPermission, size: 22),
        const SizedBox(width: 10),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Mostrar sobre otras apps',
                style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700),
              ),
              Text(
                status.overlayPermission ? 'Permitido' : 'Sin permiso',
                style: TextStyle(
                  fontSize: 12,
                  color: connectionColor(status.overlayPermission),
                ),
              ),
            ],
          ),
        ),
        if (!status.overlayPermission) ...[
          const SizedBox(width: 8),
          SizedBox(
            width: 112,
            child: TextButton(
              style: TextButton.styleFrom(
                minimumSize: const Size(44, 44),
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 6),
                textStyle: const TextStyle(fontSize: 12),
              ),
              onPressed: busy
                  ? null
                  : () => onCommand(NativeCommand.openOverlaySettings),
              child: const Text(
                'Permitir superposición',
                textAlign: TextAlign.center,
              ),
            ),
          ),
        ],
      ],
    ),
  );

  Widget _connectionBlock(
    String label,
    bool? enabled, {
    bool feminine = false,
    Widget? action,
  }) => Container(
    padding: const EdgeInsets.all(14),
    decoration: BoxDecoration(
      color: const Color(0xfff0f8ff),
      border: Border.all(color: const Color(0xffc4e0f5)),
      borderRadius: BorderRadius.circular(16),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            connectionIcon(enabled, size: 24),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    label,
                    style: const TextStyle(
                      fontWeight: FontWeight.w700,
                      fontSize: 15,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    connectionState(enabled, feminine: feminine),
                    style: TextStyle(
                      color: connectionColor(enabled),
                      fontSize: 13,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
        if (action != null) ...[const SizedBox(height: 10), action],
      ],
    ),
  );

  Widget _laboratory(NativeStatus? status) {
    String timing(List<double>? data) => data == null || data.isEmpty
        ? 'Sin muestras'
        : data.map((value) => '${value.toStringAsFixed(3)} ms').join(' / ');
    return ExpansionTile(
      leading: const MobiIcon(Icons.science_outlined, size: 27),
      trailing: const MobiIcon(Icons.expand_more_rounded, size: 26),
      title: const Text('Laboratorio y diagnóstico'),
      childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      expandedCrossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text('InputLab · pruebas del mapeador.'),
        const SizedBox(height: 10),
        _command(
          'Iniciar en laboratorio',
          Icons.play_arrow,
          NativeCommand.startLab,
        ),
        _command(
          'Probar inyección sintética',
          Icons.science_outlined,
          NativeCommand.probe,
        ),
        _command(
          'Abrir receptor sin mapear',
          Icons.open_in_new,
          NativeCommand.openLab,
        ),
        _command(
          'Detener y liberar entradas',
          Icons.stop_circle_outlined,
          NativeCommand.stop,
        ),
        Text(status?.probe ?? 'Sin ejecutar'),
        const SizedBox(height: 14),
        Text(
          'Teclas: ${status?.keys ?? 0} · muestras ratón: ${status?.mouse ?? 0}\n'
          'Deltas relativos: ${status?.relativeSamples ?? 0}\n'
          'Inyecciones admitidas: ${status?.injected ?? 0}\n'
          'Profundidad máxima: ${status?.maxQueueDepth ?? 0} · '
          'combinados: ${status?.mergedMoves ?? 0}',
        ),
        const SizedBox(height: 10),
        Text(
          'p50 / p95 / p99\nCola: ${timing(status?.queueMs)}\n'
          'IPC + admisión: ${timing(status?.ipcMs)}',
        ),
        const SizedBox(height: 8),
        const Text('Tiempos internos; no incluyen la imagen.'),
        const SizedBox(height: 10),
        _command(
          'Cerrar backend ADB',
          Icons.power_settings_new,
          NativeCommand.shutdownBackend,
        ),
      ],
    );
  }
}
