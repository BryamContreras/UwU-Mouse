import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../application/ports/mapper_gateway.dart';
import '../../../shared/widgets/uwu_icon.dart';

class LocalActivationSheet extends StatefulWidget {
  const LocalActivationSheet({super.key, required this.gateway});
  final MapperGateway gateway;

  @override
  State<LocalActivationSheet> createState() => LocalActivationSheetState();
}

class LocalActivationSheetState extends State<LocalActivationSheet>
    with WidgetsBindingObserver {
  MapperGateway get _api => widget.gateway;
  final _code = TextEditingController();
  final _pairingPort = TextEditingController();
  final _connectPort = TextEditingController();
  LocalActivationStatus? _status;
  Timer? _timer;
  String? _error;
  bool _reading = false, _sending = false, _foreground = true;
  bool _pairingPortEdited = false, _connectPortEdited = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    unawaited(_perform(_api.activationStartDiscovery));
    _startPolling();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground = state == AppLifecycleState.resumed;
    _timer?.cancel();
    _timer = null;
    if (_foreground) {
      unawaited(_poll());
      _startPolling();
    }
  }

  void _startPolling() {
    _timer?.cancel();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) => _poll());
  }

  @override
  void dispose() {
    _timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    _code.dispose();
    _pairingPort.dispose();
    _connectPort.dispose();
    // Stops discovery and pending activation work; an activated backend stays up.
    unawaited(_api.activationCancel().catchError((Object _) {}));
    super.dispose();
  }

  String _message(Object error) => error is PlatformException
      ? error.message ?? 'No se pudo activar el motor.'
      : error is FormatException
      ? error.message
      : 'No se pudo comprobar la activación.';

  Future<void> _poll() async {
    if (_reading || !_foreground || !mounted) return;
    _reading = true;
    try {
      final next = await _api.activationStatus();
      if (!mounted) return;
      if (!_pairingPortEdited && next.pairingPort != null) {
        _pairingPort.text = '${next.pairingPort}';
      }
      if (!_connectPortEdited && next.connectPort != null) {
        _connectPort.text = '${next.connectPort}';
      }
      setState(() => _status = next);
    } catch (error) {
      if (mounted) setState(() => _error = _message(error));
    } finally {
      _reading = false;
    }
  }

  Future<void> _perform(Future<void> Function() operation) async {
    if (_sending) return;
    setState(() {
      _sending = true;
      _error = null;
    });
    try {
      await operation();
      if (mounted) await _poll();
    } catch (error) {
      if (mounted) setState(() => _error = _message(error));
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  int? _port(TextEditingController controller) {
    final value = int.tryParse(controller.text.trim());
    return value != null && value > 0 && value <= 65535 ? value : null;
  }

  Future<void> _pair() async {
    final code = _code.text.trim();
    final port = _port(_pairingPort);
    if (!RegExp(r'^\d{6}$').hasMatch(code)) {
      setState(() => _error = 'Escribe el código de 6 dígitos de Android.');
      return;
    }
    if (port == null) {
      setState(() => _error = 'Escribe el puerto de emparejamiento.');
      return;
    }
    await _perform(() async {
      await _api.activationPair(
        host: _status?.host ?? '127.0.0.1',
        port: port,
        code: code,
      );
      if (mounted) _code.clear();
    });
  }

  Future<void> _connect() async {
    final rawPort = _connectPort.text.trim();
    final port = _port(_connectPort);
    if (rawPort.isNotEmpty && port == null) {
      setState(
        () => _error = 'El puerto de conexión debe estar entre 1 y 65535.',
      );
      return;
    }
    await _perform(
      () => _api.activationConnect(host: _status?.host, port: port),
    );
  }

  @override
  Widget build(BuildContext context) {
    final status = _status;
    final busy = _sending || status?.busy == true;
    return SafeArea(
      top: false,
      child: SizedBox(
        height: MediaQuery.sizeOf(context).height * .85,
        child: ListView(
          key: const ValueKey('activation-list'),
          padding: EdgeInsets.fromLTRB(
            20,
            4,
            20,
            24 + MediaQuery.viewInsetsOf(context).bottom,
          ),
          children: [
            Row(
              children: [
                const Expanded(
                  child: Text(
                    'Activar en este teléfono',
                    style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800),
                  ),
                ),
                IconButton(
                  tooltip: 'Cerrar activación',
                  onPressed: () => Navigator.pop(context),
                  icon: const UwuIcon(Icons.close_rounded, size: 26),
                ),
              ],
            ),
            const SizedBox(height: 12),
            const Text(
              '1. Activa Depuración inalámbrica.\n'
              '2. Abre «Emparejar dispositivo con código».\n'
              '3. Escribe sus 6 dígitos en la notificación UwU Mouse.',
            ),
            const SizedBox(height: 8),
            const Text(
              'También puedes usar pantalla dividida y este formulario. '
              'Mantén abierta la ventana del código de Android.',
              style: TextStyle(fontSize: 12, color: Color(0xff527394)),
            ),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: busy
                  ? null
                  : () => _perform(_api.wirelessDebuggingSettings),
              icon: const UwuIcon(Icons.settings_outlined, size: 25),
              label: const Text('Abrir Depuración inalámbrica'),
            ),
            const SizedBox(height: 16),
            if (busy) ...[
              const LinearProgressIndicator(minHeight: 2),
              const SizedBox(height: 10),
            ],
            Text(
              status?.message.isNotEmpty == true
                  ? status!.message
                  : 'Buscando el servicio local…',
              key: const ValueKey('activation-message'),
              style: TextStyle(
                fontWeight: FontWeight.w600,
                color: status?.ready == true
                    ? const Color(0xff23714e)
                    : const Color(0xff1966ab),
              ),
            ),
            if (_error != null) ...[
              const SizedBox(height: 8),
              Text(_error!, style: const TextStyle(color: Color(0xffb94f09))),
            ],
            if (status?.ready != true) ...[
              const SizedBox(height: 16),
              TextField(
                key: const ValueKey('activation-code'),
                controller: _code,
                enabled: !busy,
                keyboardType: TextInputType.number,
                inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                maxLength: 6,
                autofillHints: const [],
                enableSuggestions: false,
                autocorrect: false,
                decoration: const InputDecoration(
                  labelText: 'Código de 6 dígitos',
                ),
              ),
              const SizedBox(height: 8),
              TextField(
                key: const ValueKey('activation-pairing-port'),
                controller: _pairingPort,
                enabled: !busy,
                keyboardType: TextInputType.number,
                inputFormatters: [
                  FilteringTextInputFormatter.digitsOnly,
                  LengthLimitingTextInputFormatter(5),
                ],
                onChanged: (_) => _pairingPortEdited = true,
                decoration: const InputDecoration(
                  labelText: 'Puerto de emparejamiento',
                  helperText: 'Se detecta solo; si falta, copia el de la ventana del código.',
                  helperMaxLines: 2,
                ),
              ),
              const SizedBox(height: 12),
              FilledButton.icon(
                key: const ValueKey('activation-pair'),
                onPressed: busy ? null : _pair,
                icon: const UwuIcon(Icons.link_rounded, size: 23),
                label: const Text('Emparejar y activar'),
              ),
              if (status?.paired == true) ...[
                const SizedBox(height: 8),
                OutlinedButton.icon(
                  key: const ValueKey('activation-connect'),
                  onPressed: busy ? null : _connect,
                  icon: const UwuIcon(Icons.refresh_rounded, size: 23),
                  label: const Text('Reactivar sin código'),
                ),
              ],
              const SizedBox(height: 8),
              ExpansionTile(
                leading: const UwuIcon(Icons.search_rounded, size: 23),
                title: const Text('Si no detecta el servicio'),
                tilePadding: EdgeInsets.zero,
                children: [
                  TextField(
                    key: const ValueKey('activation-connect-port'),
                    controller: _connectPort,
                    enabled: !busy,
                    keyboardType: TextInputType.number,
                    inputFormatters: [
                      FilteringTextInputFormatter.digitsOnly,
                      LengthLimitingTextInputFormatter(5),
                    ],
                    onChanged: (_) => _connectPortEdited = true,
                    decoration: const InputDecoration(
                      labelText: 'Puerto de conexión',
                      helperText:
                          'En la pantalla principal de Depuración inalámbrica.',
                      helperMaxLines: 2,
                    ),
                  ),
                  const SizedBox(height: 10),
                  TextButton.icon(
                    onPressed: busy
                        ? null
                        : () => _perform(_api.activationStartDiscovery),
                    icon: const UwuIcon(Icons.refresh_rounded, size: 23),
                    label: const Text('Buscar de nuevo'),
                  ),
                  const Text(
                    'También puedes usar Activar-UwU-Mouse.cmd desde una PC.',
                    style: TextStyle(fontSize: 12, color: Color(0xff527394)),
                  ),
                ],
              ),
            ] else ...[
              const SizedBox(height: 16),
              const Text(
                'Motor activo. Vuelve a tu biblioteca y toca el juego.',
              ),
              const SizedBox(height: 12),
              FilledButton.icon(
                onPressed: () => Navigator.pop(context),
                icon: const UwuIcon(Icons.check_rounded, size: 23),
                label: const Text('Listo'),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
