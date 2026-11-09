/// Versioned control plane. No input sample crosses this channel.
class NativeStatus {
  NativeStatus.fromWire(Map<Object?, Object?> wire)
    : state = wire['state']! as String,
      error = wire['error']! as String,
      accessibility = wire['accessibility']! as bool,
      captureReady = wire['captureReady'] == true,
      captureBackend = wire['captureBackend'] as String? ?? '',
      captureError = wire['captureError'] as String? ?? '',
      backend = wire['backend']! as bool,
      contacts = wire['contacts']! as int,
      keys = wire['keys']! as int,
      mouse = wire['mouse']! as int,
      relativeSamples = wire['relativeSamples']! as int,
      injected = wire['injected']! as int,
      releaseUnverified = wire['releaseUnverified']! as bool,
      stopRequested = wire['stopRequested'] == true,
      guardianClosurePending = wire['guardianClosurePending'] == true,
      guardianSessionId = wire['guardianSessionId'] as int? ?? 0,
      guardianTargetPackage = wire['guardianTargetPackage'] as String? ?? '',
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
      uiDisplayId = wire['uiDisplayId'] as int? ?? 0,
      drawOverlays = wire['drawOverlays'] == true,
      overlayPermission = wire.containsKey('overlayPermission')
          ? wire['overlayPermission'] == true
          : wire['drawOverlays'] == true,
      devices = (wire['devices']! as List<Object?>)
          .cast<Map<Object?, Object?>>()
          .map((value) => value['name']! as String)
          .toList() {
    if (wire['protocol'] != 1) {
      throw const FormatException('Protocolo nativo incompatible');
    }
    if (uiDisplayId < 0) {
      throw const FormatException('Pantalla de interfaz incompatible');
    }
  }
  final String state,
      error,
      probe,
      backendError,
      mode,
      contextError,
      pointerMode,
      captureBackend,
      captureError,
      guardianTargetPackage;
  final bool accessibility,
      backend,
      releaseUnverified,
      stopRequested,
      guardianClosurePending,
      active,
      editorVisible,
      drawOverlays,
      overlayPermission,
      captureReady;
  final String? activeProfileId, activePackage, profileName;
  final int contacts,
      keys,
      mouse,
      relativeSamples,
      injected,
      maxQueueDepth,
      mergedMoves;
  final int? backendUid, targetUid;
  final int uiDisplayId, guardianSessionId;
  final List<double> queueMs, ipcMs;
  final List<String> devices;

  bool get recovering => mode == 'recovering';
  bool get blocked => mode == 'blocked' || releaseUnverified;
  bool get editing => mode == 'editing' || editorVisible;
  String get uiDisplayScope => uiDisplayId == 0 ? 'phone' : 'external';

  static List<double> _timings(Object? value) => (value! as List<Object?>)
      .map((sample) => (sample! as num).toDouble())
      .toList(growable: false);
}
