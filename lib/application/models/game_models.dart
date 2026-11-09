import 'dart:typed_data';

/// Read-only control-plane DTOs. The native profile store owns all persistence.
class LaunchableApp {
  const LaunchableApp({
    required this.packageName,
    required this.label,
    required this.gameHint,
    this.icon,
  });

  factory LaunchableApp.fromWire(Map<Object?, Object?> wire) => LaunchableApp(
    packageName: _text(wire, 'packageName'),
    label: _text(wire, 'label'),
    gameHint: wire['gameHint'] == true,
    icon: wire['icon'] as Uint8List?,
  );

  final String packageName, label;
  final bool gameHint;
  final Uint8List? icon;
}

class LocalProfile {
  LocalProfile.fromWire(Map<Object?, Object?> wire)
    : id = _text(wire, 'id'),
      name = _text(wire, 'name'),
      packageName = _text(wire, 'packageName'),
      schemaVersion = _integer(wire, 'schemaVersion'),
      switchKey = _integer(wire, 'schemaVersion') >= 8
          ? _integer(wire, 'switchKey')
          : 0,
      orientation = _text(wire, 'orientation'),
      displayScope = _integer(wire, 'schemaVersion') >= 9
          ? _text(_map(wire, 'calibration'), 'displayScope')
          : 'phone',
      calibrated = _map(wire, 'calibration')['confirmed'] == true,
      screenWidth = _integer(_map(wire, 'calibration'), 'screenWidth'),
      screenHeight = _integer(_map(wire, 'calibration'), 'screenHeight'),
      upKey = _integer(_map(wire, 'joystick'), 'upKey'),
      downKey = _integer(_map(wire, 'joystick'), 'downKey'),
      leftKey = _integer(_map(wire, 'joystick'), 'leftKey'),
      rightKey = _integer(_map(wire, 'joystick'), 'rightKey'),
      joystickEnabled = _controlEnabled(wire, 'joystick'),
      cameraEnabled = _controlEnabled(wire, 'camera'),
      actionEnabled = _controlEnabled(wire, 'action'),
      sensitivityX = _number(_map(wire, 'camera'), 'sensitivityX'),
      sensitivityY = _number(_map(wire, 'camera'), 'sensitivityY'),
      cameraMode = _integer(wire, 'schemaVersion') >= 3
          ? _text(_map(wire, 'camera'), 'mode')
          : 'bounded',
      cameraUpdateRateHz = _integer(wire, 'schemaVersion') >= 4
          ? _integer(_map(wire, 'camera'), 'updateRateHz')
          : 0,
      cameraMarkerSizeDp = _integer(wire, 'schemaVersion') >= 4
          ? _number(_map(wire, 'camera'), 'markerSizeDp')
          : 52,
      precisionAim = _integer(wire, 'schemaVersion') >= 5
          ? _boolean(_map(wire, 'camera'), 'precisionAim')
          : false,
      cameraCustomRegion =
          _integer(wire, 'schemaVersion') >= 9 &&
              _map(wire, 'camera').containsKey('customRegion')
          ? _boolean(_map(wire, 'camera'), 'customRegion')
          : false,
      cameraSuspendKey = _integer(wire, 'schemaVersion') >= 6
          ? _integer(_map(wire, 'camera'), 'suspendKey')
          : 0,
      cameraSuspendMouseButton =
          _integer(wire, 'schemaVersion') >= 6 &&
              _map(wire, 'camera').containsKey('suspendMouseButton')
          ? _integer(_map(wire, 'camera'), 'suspendMouseButton')
          : 0,
      actionMarkerSizeDp = _integer(wire, 'schemaVersion') >= 4
          ? _number(_map(wire, 'action'), 'markerSizeDp')
          : 52,
      actionKind = _text(_map(wire, 'action'), 'triggerKind'),
      actionCode = _integer(_map(wire, 'action'), 'code'),
      actionBehavior = _integer(wire, 'schemaVersion') >= 7
          ? _text(_map(wire, 'action'), 'behavior')
          : 'tap',
      repeatIntervalMs = _integer(wire, 'schemaVersion') >= 7
          ? _integer(_map(wire, 'action'), 'repeatIntervalMs')
          : 100,
      repeatToggle = _repeatToggle(_map(wire, 'action')),
      repeatDurationSeconds = _repeatDurationSeconds(_map(wire, 'action')),
      actionCadenceEnabled = _actionCadenceEnabled(_map(wire, 'action')),
      actionCadenceYPercent = _actionCadenceYPercent(_map(wire, 'action')),
      actionCadenceStartMs = _actionCadenceStartMs(_map(wire, 'action')),
      actionCadenceDurationMs = _actionCadenceDurationMs(_map(wire, 'action')),
      actionCadenceDurationEnabled = _actionCadenceDurationEnabled(
        _map(wire, 'action'),
      ),
      actionCount = _activeActionCount(wire) + _macroCount(wire),
      macroCount = _macroCount(wire),
      cameraToggle = _integer(_map(wire, 'shortcuts'), 'cameraToggle'),
      stopKey = _integer(_map(wire, 'shortcuts'), 'stop'),
      editKey = _integer(_map(wire, 'shortcuts'), 'edit') {
    if (schemaVersion < 1 || schemaVersion > 9) {
      throw const FormatException('Versión de perfil incompatible');
    }
    if (schemaVersion >= 9) _validateDisplayCalibration(wire, displayScope);
    if (orientation != 'landscape' && orientation != 'portrait') {
      throw const FormatException('Orientación de perfil no válida');
    }
    if (actionKind != 'key' && actionKind != 'mouse') {
      throw const FormatException('Asignación de acción no válida');
    }
    if (cameraMode != 'bounded' &&
        cameraMode != 'continuous' &&
        cameraMode != 'smooth') {
      throw const FormatException('Modo de cámara incompatible');
    }
    if (!const [
      0,
      60,
      90,
      100,
      120,
      125,
      144,
      240,
      250,
      500,
      1000,
    ].contains(cameraUpdateRateHz)) {
      throw const FormatException('Frecuencia de captura incompatible');
    }
    if (cameraMarkerSizeDp < 24 ||
        cameraMarkerSizeDp > 120 ||
        actionMarkerSizeDp < 24 ||
        actionMarkerSizeDp > 120) {
      throw const FormatException('Tamaño de control incompatible');
    }
    if (actionCount < 0 || actionCount > 24) {
      throw const FormatException('Cantidad de acciones incompatible');
    }
    if (cameraSuspendKey < 0 || cameraSuspendKey > 340) {
      throw const FormatException('Tecla de suspensión incompatible');
    }
    if (!const [0, 1, 2, 4, 8, 16].contains(cameraSuspendMouseButton)) {
      throw const FormatException('Botón de suspensión incompatible');
    }
    if (switchKey < 0 || switchKey > 340) {
      throw const FormatException('Tecla de perfil incompatible');
    }
  }

  final String id,
      name,
      packageName,
      orientation,
      displayScope,
      actionKind,
      cameraMode,
      actionBehavior;
  final int schemaVersion, screenWidth, screenHeight, switchKey;
  final bool calibrated,
      precisionAim,
      cameraCustomRegion,
      joystickEnabled,
      cameraEnabled,
      actionEnabled,
      repeatToggle,
      actionCadenceEnabled,
      actionCadenceDurationEnabled;
  final int repeatIntervalMs,
      repeatDurationSeconds,
      actionCadenceYPercent,
      actionCadenceStartMs,
      actionCadenceDurationMs;
  final int upKey,
      downKey,
      leftKey,
      rightKey,
      actionCode,
      actionCount,
      macroCount;
  final int cameraToggle,
      stopKey,
      editKey,
      cameraUpdateRateHz,
      cameraSuspendKey,
      cameraSuspendMouseButton;
  final double sensitivityX,
      sensitivityY,
      cameraMarkerSizeDp,
      actionMarkerSizeDp;

  String get cameraModeLabel => 'Región suave';
  String get cameraRateLabel =>
      cameraUpdateRateHz == 0 ? 'Libre' : '$cameraUpdateRateHz Hz';

  String get orientationLabel =>
      orientation == 'landscape' ? 'Horizontal' : 'Vertical';
  String get joystickLabel =>
      '${keyLabel(upKey)} / ${keyLabel(leftKey)} / '
      '${keyLabel(downKey)} / ${keyLabel(rightKey)}';
  String get actionLabel =>
      actionKind == 'key' ? keyLabel(actionCode) : mouseButtonLabel(actionCode);
}

void _validateDisplayCalibration(
  Map<Object?, Object?> wire,
  String displayScope,
) {
  if (!const ['phone', 'external'].contains(displayScope)) {
    throw const FormatException('Pantalla de calibración incompatible');
  }
  final calibration = _map(wire, 'calibration');
  if (!calibration.containsKey('windowFrame')) {
    throw const FormatException('Falta la ventana de calibración');
  }
  final frame = calibration['windowFrame'];
  if (frame == null) {
    if (displayScope == 'external' && _boolean(calibration, 'confirmed')) {
      throw const FormatException('Falta confirmar la ventana externa');
    }
    return;
  }
  if (frame is! Map) {
    throw const FormatException('Ventana de calibración incompatible');
  }
  final bounds = Map<Object?, Object?>.from(frame);
  final left = _number(bounds, 'left'), top = _number(bounds, 'top');
  final right = _number(bounds, 'right'), bottom = _number(bounds, 'bottom');
  final width = _integer(calibration, 'screenWidth');
  final height = _integer(calibration, 'screenHeight');
  final area = _map(calibration, 'area');
  final areaLeft = _number(area, 'left'), areaTop = _number(area, 'top');
  final areaRight = _number(area, 'right'),
      areaBottom = _number(area, 'bottom');
  if (width < 100 ||
      width > 8192 ||
      height < 100 ||
      height > 8192 ||
      left < 0 ||
      top < 0 ||
      right > width ||
      bottom > height ||
      right - left < 32 ||
      bottom - top < 32 ||
      areaRight - areaLeft < 32 ||
      areaBottom - areaTop < 32 ||
      areaLeft < left ||
      areaTop < top ||
      areaRight > right ||
      areaBottom > bottom) {
    throw const FormatException('Área fuera de la ventana calibrada');
  }
}

bool _controlEnabled(Map<Object?, Object?> wire, String section) =>
    _integer(wire, 'schemaVersion') < 7 ||
    _boolean(_map(wire, section), 'enabled');

int _activeActionCount(Map<Object?, Object?> wire) {
  final current = _integer(wire, 'schemaVersion') >= 7;
  bool enabled(Map<Object?, Object?> action, {required bool base}) {
    final active = current ? _boolean(action, 'enabled') : true;
    final behavior = current ? _text(action, 'behavior') : 'tap';
    final repeatToggle = _repeatToggle(action);
    final repeatDurationSeconds = _repeatDurationSeconds(action);
    if ((repeatToggle || repeatDurationSeconds != 0) && behavior != 'repeat') {
      throw const FormatException('El bucle requiere una acción Repetir');
    }
    final cadenceEnabled = _actionCadenceEnabled(action);
    final cadenceYPercent = _actionCadenceYPercent(action);
    final cadenceStartMs = _actionCadenceStartMs(action);
    final cadenceDurationMs = _actionCadenceDurationMs(action);
    final cadenceDurationEnabled = _actionCadenceDurationEnabled(action);
    if (cadenceDurationEnabled && cadenceDurationMs == 0) {
      throw const FormatException('La duración debe ser mayor que 0 ms');
    }
    if ((cadenceEnabled ||
            cadenceYPercent != 0 ||
            cadenceStartMs != 500 ||
            cadenceDurationMs != 0 ||
            cadenceDurationEnabled) &&
        (!active ||
            behavior != 'tap' ||
            _text(action, 'triggerKind') != 'mouse' ||
            _integer(action, 'code') != 1)) {
      throw const FormatException('Cadencia requiere Disparo M1');
    }
    if (!current) return true;
    final interval = _integer(action, 'repeatIntervalMs');
    if ((!base && !active) ||
        !const ['tap', 'repeat', 'cursorToggle'].contains(behavior) ||
        interval < 30 ||
        interval > 5000) {
      throw const FormatException('Comportamiento de acción incompatible');
    }
    return active;
  }

  var count = enabled(_map(wire, 'action'), base: true) ? 1 : 0;
  final rows = wire['additionalActions'];
  if (rows is! List && (current || rows != null)) {
    throw const FormatException('Lista de acciones incompatible');
  }
  for (final row in rows is List ? rows : const []) {
    if (row is! Map) throw const FormatException('Acción incompatible');
    if (enabled(Map<Object?, Object?>.from(row), base: false)) count++;
  }
  return count;
}

bool _repeatToggle(Map<Object?, Object?> action) =>
    action.containsKey('repeatToggle')
    ? _boolean(action, 'repeatToggle')
    : false;

int _repeatDurationSeconds(Map<Object?, Object?> action) {
  final value = action.containsKey('repeatDurationSeconds')
      ? _integer(action, 'repeatDurationSeconds')
      : 0;
  if (value < 0 || value > 86400) {
    throw const FormatException('Temporizador de repetición incompatible');
  }
  return value;
}

bool _actionCadenceEnabled(Map<Object?, Object?> action) =>
    action.containsKey('cadenceEnabled')
    ? _boolean(action, 'cadenceEnabled')
    : false;

int _actionCadenceYPercent(Map<Object?, Object?> action) {
  final value = action.containsKey('cadenceYPercent')
      ? _integer(action, 'cadenceYPercent')
      : 0;
  if (value < -100 || value > 100) {
    throw const FormatException('Intensidad de cadencia incompatible');
  }
  return value;
}

int _actionCadenceStartMs(Map<Object?, Object?> action) {
  final value = action.containsKey('cadenceStartMs')
      ? _integer(action, 'cadenceStartMs')
      : 500;
  if (value < 0 || value > 5000) {
    throw const FormatException('Tiempo de inicio de cadencia incompatible');
  }
  return value;
}

int _actionCadenceDurationMs(Map<Object?, Object?> action) {
  final value = action.containsKey('cadenceDurationMs')
      ? _integer(action, 'cadenceDurationMs')
      : 0;
  if (value < 0 || value > 10000) {
    throw const FormatException('Duración de cadencia incompatible');
  }
  return value;
}

bool _actionCadenceDurationEnabled(Map<Object?, Object?> action) =>
    action.containsKey('cadenceDurationEnabled')
    ? _boolean(action, 'cadenceDurationEnabled')
    : _actionCadenceDurationMs(action) > 0;

/// Schema 6 exposes only a summary in Flutter; native code owns execution and persistence.
int _macroCount(Map<Object?, Object?> wire) {
  if (_integer(wire, 'schemaVersion') < 6) return 0;
  final rows = wire['macros'];
  if (rows is! List || rows.length > 24) {
    throw const FormatException('Lista de macros incompatible');
  }
  final ids = <String>{};
  final triggers = <String>{};
  for (final row in rows) {
    if (row is! Map) throw const FormatException('Macro incompatible');
    final macro = Map<Object?, Object?>.from(row);
    final id = _text(macro, 'id');
    final name = _text(macro, 'name');
    final kind = _text(macro, 'triggerKind');
    final code = _integer(macro, 'code');
    final size = _number(macro, 'markerSizeDp');
    final steps = macro['steps'];
    if (!RegExp(r'^[A-Za-z0-9._-]{1,80}$').hasMatch(id) ||
        !ids.add(id) ||
        name.length > 80 ||
        !triggers.add('$kind:$code') ||
        (kind != 'key' && kind != 'mouse') ||
        (kind == 'key'
            ? code < 1 || code > 340
            : !const [1, 2, 4, 8, 16].contains(code)) ||
        size < 24 ||
        size > 120 ||
        steps is! List ||
        steps.isEmpty ||
        steps.length > 16) {
      throw const FormatException('Macro incompatible');
    }
    _macroPosition(macro);
    var durationMs = 0;
    for (final point in steps) {
      if (point is! Map) {
        throw const FormatException('Punto de macro incompatible');
      }
      final step = Map<Object?, Object?>.from(point);
      _macroPosition(step);
      final wait = _integer(step, 'waitMs');
      if (wait < 0 || wait > 5000) {
        throw const FormatException('Espera de macro incompatible');
      }
      durationMs += 60 + wait;
    }
    if (durationMs > 30000) {
      throw const FormatException('Macro demasiado larga');
    }
  }
  return rows.length;
}

void _macroPosition(Map<Object?, Object?> value) {
  final x = _number(value, 'x');
  final y = _number(value, 'y');
  if (x < 0 || x >= 1 || y < 0 || y >= 1) {
    throw const FormatException('Punto de macro fuera del área');
  }
}

Map<Object?, Object?> _map(Map<Object?, Object?> wire, String key) {
  final value = wire[key];
  if (value is! Map) throw FormatException('Falta $key en el perfil');
  return Map<Object?, Object?>.from(value);
}

String _text(Map<Object?, Object?> wire, String key) {
  final value = wire[key];
  if (value is! String || value.trim().isEmpty) {
    throw FormatException('Falta $key en la respuesta nativa');
  }
  return value;
}

int _integer(Map<Object?, Object?> wire, String key) {
  final value = wire[key];
  if (value is! int) throw FormatException('$key debe ser un entero');
  return value;
}

bool _boolean(Map<Object?, Object?> wire, String key) {
  final value = wire[key];
  if (value is! bool) throw FormatException('$key debe ser un booleano');
  return value;
}

double _number(Map<Object?, Object?> wire, String key) {
  final value = wire[key];
  if (value is! num || !value.isFinite) {
    throw FormatException('$key debe ser un número finito');
  }
  return value.toDouble();
}

String keyLabel(int code) {
  if (code >= 29 && code <= 54) return String.fromCharCode(65 + code - 29);
  if (code >= 131 && code <= 142) return 'F${code - 130}';
  return switch (code) {
    19 => '↑',
    20 => '↓',
    21 => '←',
    22 => '→',
    57 || 58 => 'Alt',
    59 || 60 => 'Mayús',
    61 => 'Tab',
    62 => 'Espacio',
    66 => 'Intro',
    111 => 'Esc',
    113 || 114 => 'Ctrl',
    _ => 'Tecla $code',
  };
}

String mouseButtonLabel(int code) => switch (code) {
  1 => 'Clic izquierdo',
  2 => 'Clic derecho',
  4 => 'Clic central',
  8 => 'Botón atrás',
  16 => 'Botón adelante',
  _ => 'Botón $code',
};
