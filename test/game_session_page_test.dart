import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobimapper/main.dart';

import 'native_api_test.dart' show profileWire;

void main() {
  const channel = MethodChannel('dev.mobimapper/control.v1');

  Map<String, Object?> status({
    bool blocked = false,
    bool backend = true,
    bool accessibility = true,
  }) => {
    'protocol': 1,
    'mode': blocked ? 'blocked' : 'paused',
    'state': blocked ? 'Bloqueada' : 'Pausada',
    'error': blocked ? 'Cancelación anterior no verificada' : '',
    'accessibility': accessibility,
    'backend': backend,
    'active': false,
    'contacts': blocked ? 3 : 0,
    'keys': 0,
    'mouse': 0,
    'relativeSamples': 0,
    'injected': 0,
    'releaseUnverified': blocked,
    'probe': 'Sin ejecutar',
    'maxQueueDepth': 0,
    'mergedMoves': 0,
    'queueMs': <Object?>[],
    'ipcMs': <Object?>[],
    'devices': <Object?>[],
  };

  testWidgets('returning from native editor reloads the persisted profile', (
    tester,
  ) async {
    var calibrated = false;
    var reads = 0;
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      return switch (call.method) {
        'status' => status(),
        'listApps' => [
          {
            'packageName': 'com.dts.freefireth',
            'label': 'Free Fire',
            'gameHint': true,
          },
        ],
        'listProfiles' => (() {
          reads++;
          return [profileWire(calibrated: calibrated)];
        })(),
        'selectedProfile' => profileWire(calibrated: calibrated),
        _ => null,
      };
    });
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        channel,
        null,
      ),
    );
    await tester.pumpWidget(const MobiMapperApp());
    await tester.pumpAndSettle();
    expect(find.text('Free Fire'), findsOneWidget);
    final initialReads = reads;
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    calibrated = true;
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();
    expect(find.text('Jugar'), findsOneWidget);
    expect(find.text('Colocar controles'), findsNothing);
    expect(reads, greaterThan(initialReads));
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('blocked release disables play while leaving stop available', (
    tester,
  ) async {
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      return switch (call.method) {
        'status' => status(blocked: true),
        'listApps' => [
          {
            'packageName': 'com.dts.freefireth',
            'label': 'Free Fire',
            'gameHint': true,
          },
        ],
        'listProfiles' => [profileWire()],
        'selectedProfile' => profileWire(),
        _ => null,
      };
    });
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        channel,
        null,
      ),
    );
    await tester.pumpWidget(const MobiMapperApp());
    await tester.pumpAndSettle();
    final play = find.byKey(const ValueKey('game-com.dts.freefireth'));
    expect(tester.widget<InkWell>(play).onTap, isNull);
    final stop = find.ancestor(
      of: find.text('Cerrar sesión'),
      matching: find.byWidgetPredicate((widget) => widget is ButtonStyleButton),
    );
    expect(tester.widget<ButtonStyleButton>(stop.first).onPressed, isNotNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });
  for (final calibrated in [false, true]) {
    testWidgets(
      'game tap ${calibrated ? 'starts saved profile' : 'opens calibration'}',
      (tester) async {
        final calls = <String>[];
        tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
          channel,
          (call) async {
            calls.add(call.method);
            return switch (call.method) {
              'status' => status(),
              'listApps' => [
                {
                  'packageName': 'com.dts.freefireth',
                  'label': 'Free Fire',
                  'gameHint': true,
                },
              ],
              'listProfiles' => [profileWire(calibrated: calibrated)],
              'selectedProfile' => profileWire(calibrated: calibrated),
              _ => null,
            };
          },
        );
        addTearDown(
          () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
            channel,
            null,
          ),
        );
        await tester.pumpWidget(const MobiMapperApp());
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const ValueKey('game-com.dts.freefireth')));
        await tester.pumpAndSettle();
        expect(
          calls.where((c) => c == (calibrated ? 'startProfile' : 'openEditor')),
          hasLength(1),
        );
        expect(
          calls,
          isNot(contains(calibrated ? 'openEditor' : 'startProfile')),
        );
        await tester.pumpWidget(const SizedBox.shrink());
      },
    );
  }

  void mockActivation(
    List<String> calls,
    Map<String, Object?> Function() nextStatus,
  ) {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call.method);
      return switch (call.method) {
        'status' => nextStatus(),
        'listApps' => [
          {
            'packageName': 'com.dts.freefireth',
            'label': 'Free Fire',
            'gameHint': true,
          },
        ],
        'listProfiles' => [profileWire(calibrated: true)],
        'selectedProfile' => profileWire(calibrated: true),
        _ => null,
      };
    });
    addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
  }

  testWidgets('missing motor explains PC activation and checks a live service', (
    tester,
  ) async {
    var backend = false;
    final calls = <String>[];
    mockActivation(calls, () => status(backend: backend));
    await tester.pumpWidget(const MobiMapperApp());
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('game-com.dts.freefireth')));
    // The launch action allows a bounded time for an existing Binder to attach.
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pumpAndSettle();
    expect(find.text('Accesibilidad: activa'), findsOneWidget);
    expect(find.text('Motor de toques: apagado'), findsOneWidget);
    expect(
      find.text(
        'Activa el servicio desde la PC con Activar-UwU-Mouse.cmd. '
        'Depuración inalámbrica y accesibilidad no lo arrancan.',
      ),
      findsOneWidget,
    );
    expect(calls, isNot(contains('startProfile')));
    expect(calls, isNot(contains('openEditor')));
    final check = find.text('Comprobar servicio');
    await tester.ensureVisible(check);
    final readsBefore = calls.where((method) => method == 'status').length;
    backend = true;
    await tester.tap(check);
    await tester.pumpAndSettle();
    expect(
      calls.where((method) => method == 'status').length,
      greaterThan(readsBefore),
    );
    expect(find.text('Motor de toques: activo'), findsOneWidget);
    expect(find.text('Listo para abrir tu juego'), findsOneWidget);
    expect(calls, isNot(contains('startProfile')));
    expect(calls, isNot(contains('openEditor')));
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('launch rechecks stale missing-backend state before routing', (
    tester,
  ) async {
    var backend = false;
    final calls = <String>[];
    mockActivation(calls, () => status(backend: backend));
    await tester.pumpWidget(const MobiMapperApp());
    await tester.pumpAndSettle();
    backend = true;
    await tester.tap(find.byKey(const ValueKey('game-com.dts.freefireth')));
    await tester.pumpAndSettle();
    expect(calls.where((method) => method == 'startProfile'), hasLength(1));
    expect(find.text('Comprobar servicio'), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('missing accessibility is distinguished from a ready motor', (
    tester,
  ) async {
    final calls = <String>[];
    mockActivation(calls, () => status(accessibility: false));
    await tester.pumpWidget(const MobiMapperApp());
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('game-com.dts.freefireth')));
    await tester.pumpAndSettle();
    expect(find.text('Accesibilidad: inactiva'), findsOneWidget);
    expect(find.text('Motor de toques: activo'), findsOneWidget);
    expect(find.textContaining('Activar-UwU-Mouse.cmd'), findsNothing);
    expect(calls, isNot(contains('accessibilitySettings')));
    expect(calls, isNot(contains('startProfile')));
    final accessibility = find.text('Abrir ajustes de accesibilidad');
    await tester.ensureVisible(accessibility);
    await tester.tap(accessibility);
    await tester.pumpAndSettle();
    expect(
      calls.where((method) => method == 'accessibilitySettings'),
      hasLength(1),
    );
    expect(calls, isNot(contains('startProfile')));
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
