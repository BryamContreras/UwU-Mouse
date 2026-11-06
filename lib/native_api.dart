import 'package:flutter/services.dart';

import 'game_models.dart';
export 'game_models.dart';

/// Versioned control plane. No input sample crosses this channel.
class NativeStatus {
  NativeStatus.fromWire(Map<Object?, Object?> wire)
    : state = wire['state']! as String,
      error = wire['error']! as String,
      accessibility = wire['accessibility']! as bool,
      backend = wire['backend']! as bool,
      contacts = wire['contacts']! as int,
      keys = wire['keys']! as int,
      mouse = wire['mouse']! as int,
      relativeSamples = wire['relativeSamples']! as int,
      injected = wire['injected']! as int,
      releaseUnverified = wire['releaseUnverified']! as bool,
      probe = wire['probe']! as String,
      backendError = wire['backendError'] as String? ?? '',
      backendUid = wire['backendUid'] as int?,
      targetUid = wire['targetUid'] as int?,
      maxQueueDepth = wire['maxQueueDepth']! as int,
      mergedMoves = wire['mergedMoves']! as int,
      queueMs = _timings(wire['queueMs']),
      ipcMs = _timings(wire['ipcMs']),
      active = wire['active'] == true,
      mode =
          wire['mode'] as String? ??
          (wire['releaseUnverified'] == true
              ? 'blocked'
              : wire['active'] == true
              ? 'playing'
              : 'paused'),
      activeProfileId = wire['activeProfileId'] as String?,
      activePackage = wire['activePackage'] as String?,
      profileName = wire['profileName'] as String?,
      editorVisible = wire['editorVisible'] == true,
      pointerMode = wire['pointerMode'] as String? ?? 'none',
      contextError = wire['contextError'] as String? ?? '',
      devices = (wire['devices']! as List<Object?>)
          .cast<Map<Object?, Object?>>()
          .map((value) => value['name']! as String)
          .toList() {
    if (wire['protocol'] != 1) {
      throw const FormatException('Protocolo nativo incompatible');
    }
  }
  final String state,
      error,
      probe,
      backendError,
      mode,
      contextError,
      pointerMode;
  final bool accessibility, backend, releaseUnverified, active, editorVisible;
  final String? activeProfileId, activePackage, profileName;
  final int contacts,
      keys,
      mouse,
      relativeSamples,
      injected,
      maxQueueDepth,
      mergedMoves;
  final int? backendUid, targetUid;
  final List<double> queueMs, ipcMs;
  final List<String> devices;

  bool get recovering => mode == 'recovering';
  bool get blocked => mode == 'blocked' || releaseUnverified;
  bool get editing => mode == 'editing' || editorVisible;

  static List<double> _timings(Object? value) => (value! as List<Object?>)
      .map((sample) => (sample! as num).toDouble())
      .toList(growable: false);
}

enum NativeCommand {
  accessibilitySettings,
  openLab,
  startLab,
  probe,
  pause,
  stop,
  shutdownBackend,
}

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

class NativeApi {
  const NativeApi();
  static const _channel = MethodChannel('dev.mobimapper/control.v1');

  Future<NativeStatus> status() async {
    final wire = await _channel.invokeMapMethod<Object?, Object?>('status');
    if (wire == null) {
      throw const FormatException('Respuesta nativa vacía');
    }
    return NativeStatus.fromWire(wire);
  }

  Future<LocalActivationStatus> activationStatus() async {
    final wire = await _channel.invokeMapMethod<Object?, Object?>(
      'activationStatus',
    );
    if (wire == null) throw const FormatException('Sin estado de activación');
    return LocalActivationStatus.fromWire(wire);
  }

  Future<void> activationStartDiscovery() =>
      _channel.invokeMethod<void>('activationStartDiscovery');

  Future<void> activationPair({
    required String host,
    required int port,
    required String code,
  }) => _channel.invokeMethod<void>('activationPair', {
    'host': host,
    'port': port,
    'code': code,
  });

  Future<void> activationConnect({String? host, int? port}) => _channel
      .invokeMethod<void>('activationConnect', {'host': ?host, 'port': ?port});

  Future<void> activationCancel() =>
      _channel.invokeMethod<void>('activationCancel');

  Future<void> wirelessDebuggingSettings() =>
      _channel.invokeMethod<void>('wirelessDebuggingSettings');

  Future<List<LaunchableApp>> listApps() async {
    final values = await _channel.invokeListMethod<Object?>('listApps');
    if (values == null) {
      throw const FormatException('Lista de aplicaciones vacía');
    }
    return values
        .map((value) => LaunchableApp.fromWire(_objectMap(value)))
        .toList(growable: false);
  }

  Future<List<LocalProfile>> listProfiles() async {
    final values = await _channel.invokeListMethod<Object?>('listProfiles');
    if (values == null) {
      throw const FormatException('Respuesta de perfiles vacía');
    }
    return values
        .map((value) => LocalProfile.fromWire(_objectMap(value)))
        .toList(growable: false);
  }

  Future<LocalProfile?> selectedProfile() async {
    final wire = await _channel.invokeMapMethod<Object?, Object?>(
      'selectedProfile',
    );
    return wire == null ? null : LocalProfile.fromWire(wire);
  }

  Future<LocalProfile> createProfile(String packageName, String name) =>
      _profile('createProfile', {'packageName': packageName, 'name': name});

  Future<LocalProfile> selectProfile(String id) =>
      _profile('selectProfile', {'id': id});

  Future<LocalProfile> renameProfile(String id, String name) =>
      _profile('renameProfile', {'id': id, 'name': name});

  Future<void> deleteProfile(String id) =>
      _channel.invokeMethod<void>('deleteProfile', {'id': id});

  Future<void> openEditor(String id) =>
      _channel.invokeMethod<void>('openEditor', {'id': id});

  Future<void> startProfile(String id) =>
      _channel.invokeMethod<void>('startProfile', {'id': id});

  Future<void> execute(NativeCommand command) =>
      _channel.invokeMethod<void>(command.name);

  Future<LocalProfile> _profile(
    String method,
    Map<String, Object?> arguments,
  ) async {
    final wire = await _channel.invokeMapMethod<Object?, Object?>(
      method,
      arguments,
    );
    if (wire == null) {
      throw const FormatException('No se recibió el perfil guardado');
    }
    return LocalProfile.fromWire(wire);
  }

  static Map<Object?, Object?> _objectMap(Object? value) {
    if (value is! Map) throw const FormatException('Objeto nativo no válido');
    return Map<Object?, Object?>.from(value);
  }
}
