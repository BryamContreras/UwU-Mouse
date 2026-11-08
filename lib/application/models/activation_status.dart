class LocalActivationStatus {
  LocalActivationStatus.fromWire(Map<Object?, Object?> wire)
    : phase = wire['phase'] as String? ?? 'idle',
      message = wire['message'] as String? ?? '',
      busy = wire['busy'] == true,
      paired = wire['paired'] == true,
      pairingPort = _port(wire['pairingPort']),
      connectPort = _port(wire['connectPort']),
      host = wire['host'] as String? ?? '127.0.0.1';

  final String phase, message, host;
  final bool busy, paired;
  final int? pairingPort, connectPort;
  bool get ready => phase == 'ready';

  static int? _port(Object? value) =>
      value is int && value > 0 && value <= 65535 ? value : null;
}
