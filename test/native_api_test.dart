import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/services.dart';
import 'package:mobimapper/application/ports/mapper_gateway.dart';
import 'package:mobimapper/platform/android/method_channel_mapper_gateway.dart';

import 'support/profile_fixtures.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  Map<Object?, Object?> status() => {
    'protocol': 1,
    'state': 'Pausada',
    'error': 'Backend perdido',
    'accessibility': true,
    'backend': false,
    'contacts': 3,
    'keys': 12,
    'mouse': 28,
    'relativeSamples': 0,
    'injected': 4,
    'releaseUnverified': true,
    'probe': 'Sin ejecutar',
    'maxQueueDepth': 2,
    'mergedMoves': 1,
    'queueMs': <Object?>[],
    'ipcMs': <Object?>[1.0, 2.0, 3.0],
    'devices': <Object?>[],
  };
  test('backend loss preserves unverified release', () {
    final decoded = NativeStatus.fromWire(status());
    expect(decoded.releaseUnverified, isTrue);
    expect(decoded.contacts, 3);
    expect(decoded.backend, isFalse);
  });
  test('native capture readiness is independent from legacy accessibility', () {
    expect(NativeStatus.fromWire(status()).captureReady, isFalse);
    for (final value in [null, false, 1, 'true']) {
      expect(
        NativeStatus.fromWire(status()..['captureReady'] = value).captureReady,
        isFalse,
      );
    }
    final decoded = NativeStatus.fromWire(
      status()
        ..['accessibility'] = false
        ..['captureReady'] = true
        ..['captureBackend'] = 'native-evdev'
        ..['captureError'] = '',
    );
    expect(decoded.captureReady, isTrue);
    expect(decoded.captureBackend, 'native-evdev');
    expect(decoded.accessibility, isFalse);
  });
  test('explicit overlay denial overrides a legacy granted status', () {
    final wire = status()..['drawOverlays'] = true;
    expect(NativeStatus.fromWire(wire).overlayPermission, isTrue);
    wire['overlayPermission'] = false;
    expect(NativeStatus.fromWire(wire).overlayPermission, isFalse);
    wire['overlayPermission'] = true;
    expect(NativeStatus.fromWire(wire).overlayPermission, isTrue);
  });
  test('incompatible native protocol is rejected', () {
    expect(
      () => NativeStatus.fromWire(status()..['protocol'] = 2),
      throwsFormatException,
    );
  });
  test('new session fields preserve explicit recovery and profile context', () {
    final decoded = NativeStatus.fromWire(
      status()
        ..['mode'] = 'recovering'
        ..['activeProfileId'] = 'profile-1'
        ..['activePackage'] = 'com.dts.freefireth'
        ..['editorVisible'] = false,
    );
    expect(decoded.recovering, isTrue);
    expect(decoded.activeProfileId, 'profile-1');
    expect(decoded.activePackage, 'com.dts.freefireth');
    expect(decoded.releaseUnverified, isTrue);
  });
  test('launcher category is a hint and may be missing', () {
    final app = LaunchableApp.fromWire({
      'packageName': 'com.dts.freefireth',
      'label': 'Free Fire',
    });
    expect(app.label, 'Free Fire');
    expect(app.gameHint, isFalse);
  });
  test('schema two profile summaries accept additional persisted actions', () {
    final wire = profileWire()
      ..['schemaVersion'] = 2
      ..['additionalActions'] = [
        {'x': .6, 'y': .7, 'triggerKind': 'key', 'code': 35},
        {'x': .8, 'y': .6, 'triggerKind': 'mouse', 'code': 1},
      ];
    final profile = LocalProfile.fromWire(wire);
    expect(profile.actionCount, 3);
    expect(profile.packageName, 'com.dts.freefireth');
  });
  test(
    'profile summary decodes calibration and mouse action from native store',
    () {
      final profile = LocalProfile.fromWire(profileWire());
      expect(profile.calibrated, isTrue);
      expect(profile.orientationLabel, 'Horizontal');
      expect(profile.joystickLabel, 'W / A / S / D');
      expect(profile.actionLabel, 'Clic izquierdo');
      expect(profile.cameraToggle, 138);
      expect(profile.sensitivityY, 1.25);
    },
  );
  test('unsupported profile schema is rejected before use', () {
    expect(
      () => LocalProfile.fromWire(profileWire()..['schemaVersion'] = 10),
      throwsFormatException,
    );
  });
  test(
    'UI display defaults to phone and reports the actual external scope',
    () {
      expect(NativeStatus.fromWire(status()).uiDisplayId, 0);
      expect(NativeStatus.fromWire(status()).uiDisplayScope, 'phone');
      expect(
        NativeStatus.fromWire(status()..['uiDisplayId'] = 2).uiDisplayScope,
        'external',
      );
      expect(
        () => NativeStatus.fromWire(status()..['uiDisplayId'] = -1),
        throwsFormatException,
      );
    },
  );
  test('overlay authorization requires an explicit native grant', () {
    expect(NativeStatus.fromWire(status()).drawOverlays, isFalse);
    expect(
      NativeStatus.fromWire(status()..['drawOverlays'] = true).drawOverlays,
      isTrue,
    );
    for (final value in [false, null, 'true', 1]) {
      expect(
        NativeStatus.fromWire(status()..['drawOverlays'] = value).drawOverlays,
        isFalse,
      );
    }
  });
  test('overlay settings command uses the explicit native action', () async {
    const channel = MethodChannel('dev.mobimapper/control.v1');
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          return null;
        });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null),
    );
    await const MethodChannelMapperGateway().execute(
      NativeCommand.openOverlaySettings,
    );
    expect(calls.single.method, 'openOverlaySettings');
    expect(calls.single.arguments, isNull);
  });
  test(
    'schema 9 calibration is strict and legacy ignores external metadata',
    () {
      final wire = displayProfileWire('external', 'dex');
      expect(LocalProfile.fromWire(wire).displayScope, 'external');
      expect(
        LocalProfile.fromWire({...wire, 'schemaVersion': 8}).displayScope,
        'phone',
      );
      final calibration = wire['calibration']! as Map;
      for (final invalid in [null, 1, true, 'desktop']) {
        calibration['displayScope'] = invalid;
        expect(() => LocalProfile.fromWire(wire), throwsFormatException);
      }
      calibration['displayScope'] = 'external';
      calibration['windowFrame'] = null;
      expect(() => LocalProfile.fromWire(wire), throwsFormatException);
      calibration['confirmed'] = false;
      expect(LocalProfile.fromWire(wire).displayScope, 'external');
      calibration.remove('windowFrame');
      expect(() => LocalProfile.fromWire(wire), throwsFormatException);
    },
  );
  test(
    'legacy profile ignores unversioned switch key and keeps it unassigned',
    () {
      final decoded = LocalProfile.fromWire(
        profileWire()..['switchKey'] = 'ignored',
      );
      expect(decoded.switchKey, 0);
      expect(decoded.schemaVersion, 7);
    },
  );
  test('schema 8 requires a bounded integer switch key', () {
    final wire = profileWire()
      ..['schemaVersion'] = 8
      ..['switchKey'] = 35;
    expect(LocalProfile.fromWire(wire).switchKey, 35);
    expect(LocalProfile.fromWire({...wire, 'switchKey': 0}).switchKey, 0);
    for (final invalid in [null, '35', true, 35.5, -1, 341]) {
      expect(
        () => LocalProfile.fromWire({...wire, 'switchKey': invalid}),
        throwsFormatException,
      );
    }
    wire.remove('switchKey');
    expect(() => LocalProfile.fromWire(wire), throwsFormatException);
  });
  test(
    'camera modes retain their identity without changing legacy profiles',
    () {
      for (final entry in const {
        'continuous': 'Región suave',
        'smooth': 'Región suave',
        'bounded': 'Región suave',
      }.entries) {
        final wire = profileWire()..['schemaVersion'] = 3;
        (wire['camera']! as Map)['mode'] = entry.key;
        final current = LocalProfile.fromWire(wire);
        expect(current.schemaVersion, 3);
        expect(current.cameraMode, entry.key);
        expect(current.cameraModeLabel, entry.value);
        expect(current.packageName, 'com.dts.freefireth');
        expect(current.cameraToggle, 138);
        for (final legacy in [1, 2]) {
          final decoded = LocalProfile.fromWire({
            ...wire,
            'schemaVersion': legacy,
          });
          expect(decoded.cameraMode, 'bounded');
          expect(decoded.cameraModeLabel, 'Región suave');
        }
        expect((wire['camera']! as Map)['mode'], entry.key);
      }
    },
  );
  test(
    'smooth mode preserves independent camera settings in the current schema',
    () {
      final wire = displayProfileWire('external', 'smooth-dex');
      final camera = wire['camera']! as Map;
      camera
        ..['mode'] = 'smooth'
        ..['sensitivityX'] = 2.5
        ..['sensitivityY'] = .75
        ..['updateRateHz'] = 100
        ..['suspendKey'] = 0
        ..['suspendMouseButton'] = 16;
      final before = Map<Object?, Object?>.from(camera);
      final decoded = LocalProfile.fromWire(wire);
      expect(decoded.schemaVersion, 9);
      expect(decoded.cameraMode, 'smooth');
      expect(decoded.cameraModeLabel, 'Región suave');
      expect(decoded.sensitivityX, 2.5);
      expect(decoded.sensitivityY, .75);
      expect(decoded.cameraUpdateRateHz, 100);
      expect(decoded.cameraSuspendKey, 0);
      expect(decoded.cameraSuspendMouseButton, 16);
      expect(camera, before);
    },
  );
  test('custom camera region is optional and strictly typed in schema 9', () {
    final wire = displayProfileWire('phone', 'smooth-region');
    final camera = Map<Object?, Object?>.from(wire['camera']! as Map);
    wire['camera'] = camera;
    expect(LocalProfile.fromWire(wire).cameraCustomRegion, isFalse);
    camera['customRegion'] = true;
    expect(LocalProfile.fromWire(wire).cameraCustomRegion, isTrue);
    for (final value in [null, 'true', 0, 1]) {
      camera['customRegion'] = value;
      expect(() => LocalProfile.fromWire(wire), throwsFormatException);
    }
    expect(
      LocalProfile.fromWire({...wire, 'schemaVersion': 8}).cameraCustomRegion,
      isFalse,
    );
  });
  test('GG capture rates are supported without changing saved rates', () {
    for (final rate in [125, 250, 100, 1000]) {
      final wire = displayProfileWire('phone', 'rate-$rate');
      (wire['camera']! as Map)['updateRateHz'] = rate;
      expect(LocalProfile.fromWire(wire).cameraUpdateRateHz, rate);
    }
  });
  test('schema 3 requires a known camera mode', () {
    final missing = profileWire()..['schemaVersion'] = 3;
    (missing['camera']! as Map).remove('mode');
    expect(() => LocalProfile.fromWire(missing), throwsFormatException);
    for (final mode in ['unknown', 'deferred', 'SMOOTH', 'smooth ', '']) {
      final invalid = profileWire()..['schemaVersion'] = 3;
      (invalid['camera']! as Map)['mode'] = mode;
      expect(() => LocalProfile.fromWire(invalid), throwsFormatException);
    }
  });
  test('cadence fields are optional and preserve signed M1 intensity', () {
    for (final schema in [1, 2, 3, 4, 5, 6, 7, 8, 9]) {
      final wire = displayProfileWire('external', 'cadence-$schema')
        ..['schemaVersion'] = schema;
      final defaults = LocalProfile.fromWire(wire);
      expect(defaults.actionCadenceEnabled, isFalse);
      expect(defaults.actionCadenceYPercent, 0);
      final action = wire['action']! as Map;
      for (final percent in [-100, -1, 0, 1, 100]) {
        action
          ..['cadenceEnabled'] = true
          ..['cadenceYPercent'] = percent;
        final before = Map<Object?, Object?>.from(action);
        final decoded = LocalProfile.fromWire(wire);
        expect(decoded.actionCadenceEnabled, isTrue);
        expect(decoded.actionCadenceYPercent, percent);
        expect(decoded.actionBehavior, 'tap');
        expect(action, before);
      }
      action['cadenceEnabled'] = false;
      final disabled = LocalProfile.fromWire(wire);
      expect(disabled.actionCadenceEnabled, isFalse);
      expect(disabled.actionCadenceYPercent, 100);
      action.remove('cadenceYPercent');
      expect(LocalProfile.fromWire(wire).actionCadenceYPercent, 0);
      action
        ..remove('cadenceEnabled')
        ..['cadenceYPercent'] = -35;
      final partial = LocalProfile.fromWire(wire);
      expect(partial.actionCadenceEnabled, isFalse);
      expect(partial.actionCadenceYPercent, -35);
    }
  });
  test('cadence metadata rejects malformed base and additional actions', () {
    for (final field in ['cadenceEnabled', 'cadenceYPercent']) {
      final invalidValues = field == 'cadenceEnabled'
          ? [null, 0, 1, 'true']
          : [null, true, '10', 1.5, -101, 101];
      for (final value in invalidValues) {
        for (final additional in [false, true]) {
          final wire = profileWire();
          final action = Map<Object?, Object?>.from(wire['action']! as Map)
            ..[field] = value;
          if (additional) {
            wire['additionalActions'] = [action];
          } else {
            wire['action'] = action;
          }
          expect(() => LocalProfile.fromWire(wire), throwsFormatException);
        }
      }
    }
  });
  test(
    'cadence requires an enabled M1 tap and cannot hide on another action',
    () {
      for (final invalidAction in [
        {'triggerKind': 'key', 'code': 35},
        {'code': 2},
        {'behavior': 'repeat'},
        {'behavior': 'cursorToggle'},
        {'enabled': false},
      ]) {
        for (final additional in [false, true]) {
          for (final cadence in [
            {'cadenceEnabled': true, 'cadenceYPercent': 0},
            {'cadenceEnabled': false, 'cadenceYPercent': 1},
          ]) {
            final wire = profileWire();
            final action = <Object?, Object?>{
              ...Map<Object?, Object?>.from(wire['action']! as Map),
              ...invalidAction,
              ...cadence,
            };
            if (additional) {
              wire['additionalActions'] = [action];
            } else {
              wire['action'] = action;
            }
            expect(() => LocalProfile.fromWire(wire), throwsFormatException);
          }
        }
      }
      final wire = profileWire();
      final fire = Map<Object?, Object?>.from(wire['action']! as Map)
        ..['cadenceEnabled'] = true
        ..['cadenceYPercent'] = -25;
      wire['additionalActions'] = [fire];
      (wire['action']! as Map)
        ..['enabled'] = false
        ..['code'] = 0;
      final before = Map<Object?, Object?>.from(fire);
      final decoded = LocalProfile.fromWire(wire);
      expect(decoded.actionCount, 1);
      expect(decoded.actionCadenceEnabled, isFalse);
      expect(fire, before);
    },
  );
  test(
    'schema 4 reads capture and marker settings while legacy stays compatible',
    () {
      final wire = profileWire()..['schemaVersion'] = 4;
      (wire['camera']! as Map)
        ..['updateRateHz'] = 144
        ..['markerSizeDp'] = 80.0;
      (wire['action']! as Map)['markerSizeDp'] = 36.0;
      final current = LocalProfile.fromWire(wire);
      expect(current.schemaVersion, 4);
      expect(current.cameraMode, 'continuous');
      expect(current.cameraUpdateRateHz, 144);
      expect(current.cameraMarkerSizeDp, 80);
      expect(current.actionMarkerSizeDp, 36);
      for (final version in [1, 2, 3]) {
        final legacy = LocalProfile.fromWire({
          ...wire,
          'schemaVersion': version,
        });
        expect(legacy.cameraUpdateRateHz, 0);
        expect(legacy.cameraMarkerSizeDp, 52);
        expect(legacy.actionMarkerSizeDp, 52);
        expect(legacy.cameraMode, version == 3 ? 'continuous' : 'bounded');
      }
    },
  );
  test(
    'schema 4 rejects unsupported rates, missing style, and out of range sizes',
    () {
      for (final rate in [0, 60, 90, 100, 120, 144, 240, 500, 1000]) {
        final wire = profileWire();
        (wire['camera']! as Map)['updateRateHz'] = rate;
        expect(LocalProfile.fromWire(wire).cameraUpdateRateHz, rate);
      }
      for (final rate in [-1, 30, 61, 999, 1001, '120']) {
        final wire = profileWire();
        (wire['camera']! as Map)['updateRateHz'] = rate;
        expect(() => LocalProfile.fromWire(wire), throwsFormatException);
      }
      for (final section in ['camera', 'action']) {
        final missing = profileWire();
        (missing[section]! as Map).remove('markerSizeDp');
        expect(() => LocalProfile.fromWire(missing), throwsFormatException);
        for (final size in [23, 121, double.nan, double.infinity]) {
          final wire = profileWire();
          (wire[section]! as Map)['markerSizeDp'] = size;
          expect(() => LocalProfile.fromWire(wire), throwsFormatException);
        }
      }
    },
  );
  test('schema 4 library accepts 24 configured actions and rejects a 25th', () {
    final wire = profileWire()
      ..['schemaVersion'] = 4
      ..['additionalActions'] = List.generate(
        23,
        (index) => {
          'x': .5,
          'y': .5,
          'triggerKind': 'key',
          'code': 100 + index,
          'markerSizeDp': 52.0,
        },
      );
    expect(LocalProfile.fromWire(wire).actionCount, 24);
    (wire['additionalActions']! as List).add({
      'x': .5,
      'y': .5,
      'triggerKind': 'key',
      'code': 123,
      'markerSizeDp': 52.0,
    });
    expect(() => LocalProfile.fromWire(wire), throwsFormatException);
  });
  test('schema 5 precision is explicit and older profiles stay disabled', () {
    final wire = profileWire()..['schemaVersion'] = 5;
    expect(LocalProfile.fromWire(wire).schemaVersion, 5);
    expect(LocalProfile.fromWire(wire).precisionAim, isFalse);
    (wire['camera']! as Map)['precisionAim'] = true;
    expect(LocalProfile.fromWire(wire).precisionAim, isTrue);
    for (final version in [1, 2, 3, 4]) {
      expect(
        LocalProfile.fromWire({...wire, 'schemaVersion': version}).precisionAim,
        isFalse,
      );
    }
    for (final value in [null, 1, 'true']) {
      final invalid = profileWire();
      invalid['camera'] = Map<Object?, Object?>.from(invalid['camera']! as Map)
        ..['precisionAim'] = value;
      expect(() => LocalProfile.fromWire(invalid), throwsFormatException);
    }
    (wire['camera']! as Map).remove('precisionAim');
    expect(() => LocalProfile.fromWire(wire), throwsFormatException);
  });
  test('schema 6 requires explicit suspend and macro metadata', () {
    final current = LocalProfile.fromWire(profileWire()..['schemaVersion'] = 6);
    expect(current.schemaVersion, 6);
    expect(current.cameraSuspendKey, 0);
    expect(current.macroCount, 0);
    for (final value in [null, 57.5, '57', true, -1, 341]) {
      final invalid = profileWire();
      invalid['camera'] = Map<Object?, Object?>.from(invalid['camera']! as Map)
        ..['suspendKey'] = value;
      expect(() => LocalProfile.fromWire(invalid), throwsFormatException);
    }
    final missingSuspend = profileWire();
    (missingSuspend['camera']! as Map).remove('suspendKey');
    expect(() => LocalProfile.fromWire(missingSuspend), throwsFormatException);
    for (final value in [null, '[]', <Object?, Object?>{}]) {
      expect(
        () => LocalProfile.fromWire(profileWire()..['macros'] = value),
        throwsFormatException,
      );
    }
    expect(
      () => LocalProfile.fromWire(profileWire()..remove('macros')),
      throwsFormatException,
    );
    for (final version in [1, 2, 3, 4, 5]) {
      final legacy = profileWire()
        ..['schemaVersion'] = version
        ..['macros'] = 'ignored';
      legacy['camera'] = Map<Object?, Object?>.from(legacy['camera']! as Map)
        ..['suspendKey'] = 'ignored';
      final decoded = LocalProfile.fromWire(legacy);
      expect(decoded.cameraSuspendKey, 0);
      expect(decoded.macroCount, 0);
      expect(decoded.actionCount, 1);
    }
  });
  test('schema 6 summary includes macros in the shared 24 control budget', () {
    final wire = profileWire()
      ..['schemaVersion'] = 6
      ..['macros'] = List.generate(8, (index) => macroWire(index))
      ..['additionalActions'] = List.generate(
        15,
        (index) => <Object?, Object?>{'code': 100 + index},
      );
    (wire['camera']! as Map)['suspendKey'] = 57;
    final decoded = LocalProfile.fromWire(wire);
    expect(decoded.macroCount, 8);
    expect(decoded.actionCount, 24);
    expect(decoded.cameraSuspendKey, 57);
    (wire['additionalActions']! as List).add(<Object?, Object?>{'code': 115});
    expect(() => LocalProfile.fromWire(wire), throwsFormatException);
    expect(
      () => LocalProfile.fromWire(
        profileWire()..['macros'] = List.generate(24, macroWire),
      ),
      throwsFormatException,
    );
  });
  test('legacy suspend key 125 stays a key when mouse metadata is absent', () {
    final wire = profileWire();
    final camera = wire['camera']! as Map;
    camera['suspendKey'] = 125;
    camera.remove('suspendMouseButton');
    final decoded = LocalProfile.fromWire(
      jsonDecode(jsonEncode(wire)) as Map<String, dynamic>,
    );
    expect(decoded.cameraSuspendKey, 125);
    expect(decoded.cameraSuspendMouseButton, 0);
  });
  test('mouse suspend button survives the profile JSON round trip', () {
    final wire = profileWire();
    (wire['camera']! as Map)
      ..['suspendKey'] = 0
      ..['suspendMouseButton'] = 16;
    final decoded = LocalProfile.fromWire(
      jsonDecode(jsonEncode(wire)) as Map<String, dynamic>,
    );
    expect(decoded.cameraSuspendKey, 0);
    expect(decoded.cameraSuspendMouseButton, 16);
  });
  test(
    'optional mouse suspend metadata accepts only supported button masks',
    () {
      for (final button in [0, 1, 2, 4, 8, 16]) {
        final wire = profileWire();
        (wire['camera']! as Map)['suspendMouseButton'] = button;
        expect(LocalProfile.fromWire(wire).cameraSuspendMouseButton, button);
      }
      for (final value in [null, true, 16.0, '16', -1, 3, 32]) {
        final wire = profileWire();
        wire['camera'] = Map<Object?, Object?>.from(wire['camera']! as Map)
          ..['suspendMouseButton'] = value;
        expect(() => LocalProfile.fromWire(wire), throwsFormatException);
      }
      for (final schema in [1, 2, 3, 4, 5]) {
        final wire = profileWire()..['schemaVersion'] = schema;
        (wire['camera']! as Map)['suspendMouseButton'] = 'ignored';
        expect(LocalProfile.fromWire(wire).cameraSuspendMouseButton, 0);
      }
    },
  );
  test('schema 6 rejects malformed or unbounded macro sequences', () {
    final exact = macroWire(0)
      ..['steps'] = List.generate(
        16,
        (_) => <Object?, Object?>{'x': .5, 'y': .5, 'waitMs': 1815},
      );
    expect(
      LocalProfile.fromWire(profileWire()..['macros'] = [exact]).macroCount,
      1,
    );
    final badSteps = <Object?>[
      null,
      <Object?>[],
      List.generate(
        17,
        (_) => <Object?, Object?>{'x': .5, 'y': .5, 'waitMs': 0},
      ),
      List.generate(
        16,
        (_) => <Object?, Object?>{'x': .5, 'y': .5, 'waitMs': 1816},
      ),
      [
        <Object?, Object?>{'x': double.nan, 'y': .5, 'waitMs': 100},
      ],
      [
        <Object?, Object?>{'x': .5, 'y': 1.0, 'waitMs': 100},
      ],
      [
        <Object?, Object?>{'x': .5, 'y': .5, 'waitMs': 5001},
      ],
      [
        <Object?, Object?>{'x': .5, 'y': .5, 'waitMs': '100'},
      ],
    ];
    for (final steps in badSteps) {
      final macro = macroWire(0)..['steps'] = steps;
      expect(
        () => LocalProfile.fromWire(profileWire()..['macros'] = [macro]),
        throwsFormatException,
      );
    }
  });
  test(
    'schema 7 preserves action behavior and excludes the disabled base from the budget',
    () {
      final wire = profileWire();
      (wire['joystick']! as Map)['enabled'] = false;
      (wire['camera']! as Map)['enabled'] = false;
      (wire['action']! as Map)
        ..['enabled'] = false
        ..['code'] = 0;
      final repeat = Map<Object?, Object?>.from(wire['action']! as Map)
        ..['enabled'] = true
        ..['triggerKind'] = 'key'
        ..['code'] = 35
        ..['behavior'] = 'repeat'
        ..['repeatIntervalMs'] = 50;
      final cursor = Map<Object?, Object?>.from(repeat)
        ..['code'] = 36
        ..['behavior'] = 'cursorToggle';
      wire['additionalActions'] = [repeat, cursor];
      final decoded = LocalProfile.fromWire(wire);
      expect(decoded.schemaVersion, 7);
      expect(decoded.joystickEnabled, isFalse);
      expect(decoded.cameraEnabled, isFalse);
      expect(decoded.actionEnabled, isFalse);
      expect(decoded.actionCount, 2);
      wire['additionalActions'] = <Object?>[];
      expect(LocalProfile.fromWire(wire).actionCount, 0);
      wire['additionalActions'] = List.generate(
        24,
        (index) => Map<Object?, Object?>.from(repeat)..['code'] = 100 + index,
      );
      expect(LocalProfile.fromWire(wire).actionCount, 24);
      (wire['additionalActions']! as List).add(cursor);
      expect(() => LocalProfile.fromWire(wire), throwsFormatException);
    },
  );
  test(
    'schema 7 metadata is typed and legacy metadata cannot silently activate new behavior',
    () {
      for (final version in [1, 2, 3, 4, 5, 6]) {
        final wire = profileWire()..['schemaVersion'] = version;
        (wire['joystick']! as Map)['enabled'] = false;
        (wire['camera']! as Map)['enabled'] = false;
        (wire['action']! as Map)
          ..['enabled'] = false
          ..['behavior'] = 'repeat'
          ..['repeatIntervalMs'] = 50;
        final legacy = LocalProfile.fromWire(wire);
        expect(legacy.joystickEnabled, isTrue);
        expect(legacy.cameraEnabled, isTrue);
        expect(legacy.actionEnabled, isTrue);
        expect(legacy.actionBehavior, 'tap');
        expect(legacy.repeatIntervalMs, 100);
        expect(legacy.actionCount, 1);
      }
      for (final section in ['joystick', 'camera', 'action']) {
        final missing = profileWire();
        (missing[section]! as Map).remove('enabled');
        expect(() => LocalProfile.fromWire(missing), throwsFormatException);
        for (final invalidValue in [null, 0, 'true']) {
          final wire = profileWire();
          wire[section] = Map<Object?, Object?>.from(wire[section]! as Map)
            ..['enabled'] = invalidValue;
          expect(() => LocalProfile.fromWire(wire), throwsFormatException);
        }
      }
      final extra = Map<Object?, Object?>.from(profileWire()['action']! as Map)
        ..['enabled'] = false;
      expect(
        () => LocalProfile.fromWire(
          profileWire()..['additionalActions'] = [extra],
        ),
        throwsFormatException,
      );
    },
  );
  test(
    'schema 7 action behavior and interval reject missing or unsafe metadata',
    () {
      for (final behavior in ['tap', 'repeat', 'cursorToggle']) {
        final wire = profileWire();
        (wire['action']! as Map)['behavior'] = behavior;
        expect(LocalProfile.fromWire(wire).actionBehavior, behavior);
      }
      for (final interval in [50, 100, 5000]) {
        final wire = profileWire();
        (wire['action']! as Map)['repeatIntervalMs'] = interval;
        expect(LocalProfile.fromWire(wire).repeatIntervalMs, interval);
      }
      for (final field in ['behavior', 'repeatIntervalMs']) {
        final missing = profileWire();
        (missing['action']! as Map).remove(field);
        expect(() => LocalProfile.fromWire(missing), throwsFormatException);
      }
      for (final interval in [null, 49, 5001, 100.5, '100', true]) {
        final wire = profileWire();
        wire['action'] = Map<Object?, Object?>.from(wire['action']! as Map)
          ..['repeatIntervalMs'] = interval;
        expect(() => LocalProfile.fromWire(wire), throwsFormatException);
      }
      for (final behavior in ['', 'Repeat', 'hold']) {
        final wire = profileWire();
        (wire['action']! as Map)['behavior'] = behavior;
        expect(() => LocalProfile.fromWire(wire), throwsFormatException);
      }
    },
  );
  test('editor and play commands transfer profile identity only', () async {
    const channel = MethodChannel('dev.mobimapper/control.v1');
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          return call.method == 'createProfile' ? profileWire() : null;
        });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null),
    );
    const api = MethodChannelMapperGateway();
    final created = await api.createProfile(
      'com.dts.freefireth',
      'Entrenamiento',
    );
    await api.openEditor(created.id);
    await api.startProfile(created.id);
    expect(calls.map((call) => call.method), [
      'createProfile',
      'openEditor',
      'startProfile',
    ]);
    expect(calls[0].arguments, {
      'packageName': 'com.dts.freefireth',
      'name': 'Entrenamiento',
    });
    expect(calls[1].arguments, {'id': 'profile-1'});
    expect(calls[2].arguments, {'id': 'profile-1'});
  });
  test(
    'local activation transfers pairing once and supports discovered reconnect',
    () async {
      const channel = MethodChannel('dev.mobimapper/control.v1');
      final calls = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            calls.add(call);
            return call.method == 'activationStatus'
                ? {
                    'phase': 'discovering',
                    'message': 'Emparejado',
                    'busy': false,
                    'paired': true,
                    'pairingPort': 40321,
                    'connectPort': 37001,
                  }
                : null;
          });
      addTearDown(
        () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(channel, null),
      );
      const api = MethodChannelMapperGateway();
      final status = await api.activationStatus();
      expect(status.paired, isTrue);
      expect(status.host, '127.0.0.1');
      expect(status.pairingPort, 40321);
      expect(status.connectPort, 37001);
      await api.activationStartDiscovery();
      await api.activationPair(
        host: status.host,
        port: status.pairingPort!,
        code: '123456',
      );
      await api.activationConnect();
      await api.wirelessDebuggingSettings();
      await api.activationCancel();
      expect(calls[2].arguments, {
        'host': '127.0.0.1',
        'port': 40321,
        'code': '123456',
      });
      expect(calls[3].arguments, isEmpty);
      expect(calls.map((c) => c.method), [
        'activationStatus',
        'activationStartDiscovery',
        'activationPair',
        'activationConnect',
        'wirelessDebuggingSettings',
        'activationCancel',
      ]);
    },
  );
}
