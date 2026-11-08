Map<Object?, Object?> macroWire(int index) => {
  'id': 'macro-$index',
  'name': 'Macro ${index + 1}',
  'x': .5,
  'y': .5,
  'triggerKind': 'key',
  'code': 200 + index,
  'markerSizeDp': 52.0,
  'steps': [
    <Object?, Object?>{'x': .4, 'y': .6, 'waitMs': 100},
  ],
};

Map<Object?, Object?> displayProfileWire(String scope, String id) {
  final wire = profileWire()
    ..['schemaVersion'] = 9
    ..['switchKey'] = 0
    ..['id'] = id
    ..['name'] = scope == 'external' ? 'Perfil DeX' : 'Perfil teléfono';
  wire['calibration'] = <Object?, Object?>{
    ...Map<Object?, Object?>.from(wire['calibration']! as Map),
    'displayScope': scope,
    'windowFrame': scope == 'external'
        ? {'left': 0.0, 'top': 0.0, 'right': 2316.0, 'bottom': 1080.0}
        : null,
  };
  return wire;
}

Map<Object?, Object?> profileWire({bool calibrated = true}) => {
  'schemaVersion': 7,
  'id': 'profile-1',
  'name': 'Entrenamiento',
  'packageName': 'com.dts.freefireth',
  'orientation': 'landscape',
  'calibration': {
    'screenWidth': 2316,
    'screenHeight': 1080,
    'rotation': 1,
    'confirmed': calibrated,
    'area': {'left': 75.0, 'top': 0.0, 'right': 2241.0, 'bottom': 1080.0},
  },
  'joystick': {
    'x': .2,
    'y': .7,
    'radius': .12,
    'upKey': 51,
    'downKey': 47,
    'leftKey': 29,
    'rightKey': 32,
    'enabled': true,
  },
  'camera': {
    'x': .68,
    'y': .45,
    'width': .3,
    'height': .3,
    'sensitivityX': 1.0,
    'sensitivityY': 1.25,
    'mode': 'continuous',
    'updateRateHz': 0,
    'markerSizeDp': 52.0,
    'precisionAim': false,
    'suspendKey': 0,
    'enabled': true,
  },
  'action': {
    'x': .85,
    'y': .75,
    'triggerKind': 'mouse',
    'code': 1,
    'markerSizeDp': 52.0,
    'enabled': true,
    'behavior': 'tap',
    'repeatIntervalMs': 100,
  },
  'additionalActions': <Object?>[],
  'macros': <Object?>[],
  'shortcuts': {'cameraToggle': 138, 'stop': 139, 'edit': 137},
};
