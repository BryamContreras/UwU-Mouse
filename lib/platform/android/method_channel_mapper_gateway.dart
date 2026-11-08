import 'package:flutter/services.dart';

import '../../application/ports/mapper_gateway.dart';

/// Android adapter for the existing versioned control protocol.
class MethodChannelMapperGateway implements MapperGateway {
  const MethodChannelMapperGateway();
  static const _channel = MethodChannel('dev.mobimapper/control.v1');

  @override
  Future<NativeStatus> status() async {
    final wire = await _channel.invokeMapMethod<Object?, Object?>('status');
    if (wire == null) {
      throw const FormatException('Respuesta nativa vacía');
    }
    return NativeStatus.fromWire(wire);
  }

  @override
  Future<LocalActivationStatus> activationStatus() async {
    final wire = await _channel.invokeMapMethod<Object?, Object?>(
      'activationStatus',
    );
    if (wire == null) throw const FormatException('Sin estado de activación');
    return LocalActivationStatus.fromWire(wire);
  }

  @override
  Future<void> activationStartDiscovery() =>
      _channel.invokeMethod<void>('activationStartDiscovery');

  @override
  Future<void> activationPair({
    required String host,
    required int port,
    required String code,
  }) => _channel.invokeMethod<void>('activationPair', {
    'host': host,
    'port': port,
    'code': code,
  });

  @override
  Future<void> activationConnect({String? host, int? port}) => _channel
      .invokeMethod<void>('activationConnect', {'host': ?host, 'port': ?port});

  @override
  Future<void> activationCancel() =>
      _channel.invokeMethod<void>('activationCancel');

  @override
  Future<void> wirelessDebuggingSettings() =>
      _channel.invokeMethod<void>('wirelessDebuggingSettings');

  @override
  Future<List<LaunchableApp>> listApps() async {
    final values = await _channel.invokeListMethod<Object?>('listApps');
    if (values == null) {
      throw const FormatException('Lista de aplicaciones vacía');
    }
    return values
        .map((value) => LaunchableApp.fromWire(_objectMap(value)))
        .toList(growable: false);
  }

  @override
  Future<List<LocalProfile>> listProfiles() async {
    final values = await _channel.invokeListMethod<Object?>('listProfiles');
    if (values == null) {
      throw const FormatException('Respuesta de perfiles vacía');
    }
    return values
        .map((value) => LocalProfile.fromWire(_objectMap(value)))
        .toList(growable: false);
  }

  @override
  Future<LocalProfile?> selectedProfile() async {
    final wire = await _channel.invokeMapMethod<Object?, Object?>(
      'selectedProfile',
    );
    return wire == null ? null : LocalProfile.fromWire(wire);
  }

  @override
  Future<LocalProfile> createProfile(String packageName, String name) =>
      _profile('createProfile', {'packageName': packageName, 'name': name});

  @override
  Future<LocalProfile> selectProfile(String id) =>
      _profile('selectProfile', {'id': id});

  @override
  Future<LocalProfile> renameProfile(String id, String name) =>
      _profile('renameProfile', {'id': id, 'name': name});

  @override
  Future<void> deleteProfile(String id) =>
      _channel.invokeMethod<void>('deleteProfile', {'id': id});

  @override
  Future<bool> exportProfileFile(String id) async =>
      await _channel.invokeMethod<bool>('exportProfileFile', {'id': id}) ??
      false;

  @override
  Future<bool> importProfileFile() async =>
      await _channel.invokeMethod<bool>('importProfileFile') ?? false;

  @override
  Future<void> openEditor(String id) =>
      _channel.invokeMethod<void>('openEditor', {'id': id});

  @override
  Future<void> startProfile(String id) =>
      _channel.invokeMethod<void>('startProfile', {'id': id});

  @override
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
