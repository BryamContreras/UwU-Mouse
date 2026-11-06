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
        'activationStatus' => {
          'phase': 'idle',
          'message': '',
          'busy': false,
          'paired': false,
        },
        _ => null,
      };
    });
    addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
  }

  testWidgets(
    'missing motor offers local activation and checks a live service',
    (tester) async {
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
          'Activa el motor con Depuración inalámbrica. '
          'El emparejamiento se realiza en este teléfono.',
        ),
        findsOneWidget,
      );
      expect(calls, isNot(contains('startProfile')));
      expect(calls, isNot(contains('openEditor')));
      expect(calls, isNot(contains('activationStartDiscovery')));
      expect(calls, isNot(contains('activationConnect')));
      expect(calls, isNot(contains('activationCancel')));
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
    },
  );

  testWidgets(
    'local activation validates code then pairs without starting play',
    (tester) async {
      final calls = <MethodCall>[];
      var ready = false;
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
        call,
      ) async {
        calls.add(call);
        return switch (call.method) {
          'status' => status(backend: ready),
          'listApps' => [
            {'packageName': 'com.dts.freefireth', 'label': 'Free Fire'},
          ],
          'listProfiles' => [profileWire()],
          'selectedProfile' => profileWire(),
          'activationStatus' => {
            'phase': ready ? 'ready' : 'discovering',
            'message': ready ? 'Motor activo' : 'Buscando servicio local',
            'busy': false,
            'paired': ready,
            'pairingPort': 37123,
            'host': '127.0.0.1',
          },
          'activationPair' => (() {
            ready = true;
            return null;
          })(),
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
      await tester.tap(find.byTooltip('Ajustes'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Activar en este teléfono'));
      await tester.pumpAndSettle();
      expect(
        calls.where((c) => c.method == 'activationStartDiscovery'),
        hasLength(1),
      );
      final pair = find.byKey(const ValueKey('activation-pair'));
      final activationScroll = find
          .descendant(
            of: find.byKey(const ValueKey('activation-list')),
            matching: find.byType(Scrollable),
          )
          .first;
      await tester.scrollUntilVisible(pair, 150, scrollable: activationScroll);
      await tester.pumpAndSettle();
      await tester.tap(pair);
      await tester.pumpAndSettle();
      expect(
        find.text('Escribe el código de 6 dígitos de Android.'),
        findsOneWidget,
      );
      expect(calls.where((c) => c.method == 'activationPair'), isEmpty);
      await tester.scrollUntilVisible(
        find.byKey(const ValueKey('activation-code')),
        -150,
        scrollable: activationScroll,
      );
      await tester.enterText(
        find.byKey(const ValueKey('activation-code')),
        '123456',
      );
      await tester.enterText(
        find.byKey(const ValueKey('activation-pairing-port')),
        '40219',
      );
      await tester.ensureVisible(pair);
      await tester.pumpAndSettle();
      await tester.tap(pair);
      await tester.pumpAndSettle();
      expect(calls.singleWhere((c) => c.method == 'activationPair').arguments, {
        'host': '127.0.0.1',
        'port': 40219,
        'code': '123456',
      });
      expect(find.text('Motor activo'), findsOneWidget);
      expect(calls.map((c) => c.method), isNot(contains('startProfile')));
      expect(calls.map((c) => c.method), isNot(contains('openEditor')));
      await tester.tap(find.text('Listo'));
      await tester.pumpAndSettle();
      expect(calls.where((c) => c.method == 'activationCancel'), hasLength(1));
      expect(find.text('Motor de toques: activo'), findsOneWidget);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'paired phone can reactivate and closing stops activation polling',
    (tester) async {
      final calls = <String>[];
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
        call,
      ) async {
        calls.add(call.method);
        return switch (call.method) {
          'status' => status(backend: false),
          'listApps' => <Object?>[],
          'listProfiles' => <Object?>[],
          'selectedProfile' => null,
          'activationStatus' => {
            'phase': 'discovering',
            'message': 'Emparejado',
            'busy': false,
            'paired': true,
          },
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
      await tester.tap(find.byTooltip('Ajustes'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Activar en este teléfono'));
      await tester.pumpAndSettle();
      final connect = find.byKey(const ValueKey('activation-connect'));
      final activationScroll = find
          .descendant(
            of: find.byKey(const ValueKey('activation-list')),
            matching: find.byType(Scrollable),
          )
          .first;
      await tester.scrollUntilVisible(
        connect,
        150,
        scrollable: activationScroll,
      );
      await tester.pumpAndSettle();
      await tester.tap(connect);
      await tester.pumpAndSettle();
      expect(calls.where((c) => c == 'activationConnect'), hasLength(1));
      expect(calls, isNot(contains('activationPair')));
      await tester.pump(const Duration(seconds: 2));
      await tester.scrollUntilVisible(
        find.byTooltip('Cerrar activación'),
        -150,
        scrollable: activationScroll,
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Cerrar activación'));
      await tester.pumpAndSettle();
      expect(calls.where((c) => c == 'activationCancel'), hasLength(1));
      final reads = calls.where((c) => c == 'activationStatus').length;
      await tester.pump(const Duration(seconds: 3));
      expect(calls.where((c) => c == 'activationStatus').length, reads);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

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

  void mockColdLaunch(
    List<String> calls, {
    required Map<String, Object?> Function() activation,
    required bool Function() backend,
    void Function(String method)? onMethod,
  }) {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call.method);
      onMethod?.call(call.method);
      return switch (call.method) {
        'status' => status(backend: backend()),
        'activationStatus' => activation(),
        'listApps' => [
          {'packageName': 'com.dts.freefireth', 'label': 'Free Fire'},
        ],
        'listProfiles' => [profileWire()],
        'selectedProfile' => profileWire(),
        _ => null,
      };
    });
    addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
  }

  Future<void> tapColdGame(WidgetTester tester) async {
    await tester.pumpWidget(const MobiMapperApp());
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('game-com.dts.freefireth')));
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pump();
  }

  testWidgets(
    'explicit game tap reactivates paired motor before opening profile',
    (tester) async {
      final calls = <String>[];
      var connected = false;
      mockColdLaunch(
        calls,
        activation: () => {
          'phase': connected ? 'ready' : 'idle',
          'message': '',
          'busy': false,
          'paired': true,
        },
        backend: () => connected,
        onMethod: (method) {
          if (method == 'activationConnect') connected = true;
        },
      );
      await tapColdGame(tester);
      await tester.pumpAndSettle();
      expect(calls.where((c) => c == 'activationStartDiscovery'), hasLength(1));
      expect(calls.where((c) => c == 'activationConnect'), hasLength(1));
      expect(calls.where((c) => c == 'startProfile'), hasLength(1));
      expect(
        calls.indexOf('activationConnect'),
        lessThan(calls.indexOf('startProfile')),
      );
      expect(calls.where((c) => c == 'activationCancel'), hasLength(1));
      expect(calls, isNot(contains('activationPair')));
      expect(find.text('Comprobar servicio'), findsNothing);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'background and return invalidates pending game tap after activation',
    (tester) async {
      final calls = <String>[];
      var connecting = false, ready = false;
      mockColdLaunch(
        calls,
        activation: () => {
          'phase': ready
              ? 'ready'
              : connecting
              ? 'connecting'
              : 'idle',
          'message': '',
          'busy': connecting && !ready,
          'paired': true,
        },
        backend: () => ready,
        onMethod: (method) {
          if (method == 'activationConnect') connecting = true;
        },
      );
      await tapColdGame(tester);
      expect(calls, contains('activationConnect'));
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      ready = true;
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pump(const Duration(milliseconds: 300));
      await tester.pumpAndSettle();
      expect(calls.where((c) => c == 'activationCancel'), hasLength(1));
      expect(calls, isNot(contains('startProfile')));
      expect(calls, isNot(contains('openEditor')));
      expect(find.text('Comprobar servicio'), findsNothing);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets('game tap leaves an existing pairing operation untouched', (
    tester,
  ) async {
    final calls = <String>[];
    mockColdLaunch(
      calls,
      activation: () => {
        'phase': 'pairing',
        'message': 'Emparejando',
        'busy': true,
        'paired': true,
      },
      backend: () => false,
    );
    await tapColdGame(tester);
    await tester.pumpAndSettle();
    expect(calls, isNot(contains('activationStartDiscovery')));
    expect(calls, isNot(contains('activationConnect')));
    expect(calls, isNot(contains('activationCancel')));
    expect(calls, isNot(contains('startProfile')));
    expect(find.text('Comprobar servicio'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  for (final timedOut in [false, true]) {
    testWidgets(
      'failed cold activation ${timedOut ? 'times out' : 'reports error'} and cleans up its attempt',
      (tester) async {
        final calls = <String>[];
        var connecting = false;
        mockColdLaunch(
          calls,
          activation: () => {
            'phase': connecting
                ? timedOut
                      ? 'connecting'
                      : 'error'
                : 'idle',
            'message': connecting && !timedOut
                ? 'Activa Depuración inalámbrica'
                : '',
            'busy': connecting && timedOut,
            'paired': true,
          },
          backend: () => false,
          onMethod: (method) {
            if (method == 'activationConnect') connecting = true;
          },
        );
        await tapColdGame(tester);
        if (timedOut) await tester.pump(const Duration(seconds: 21));
        await tester.pumpAndSettle();
        expect(calls.where((c) => c == 'activationCancel'), hasLength(1));
        expect(calls, isNot(contains('startProfile')));
        expect(calls, isNot(contains('openEditor')));
        expect(find.text('Comprobar servicio'), findsOneWidget);
        expect(
          find.text(
            timedOut
                ? 'No se pudo reactivar a tiempo. Revisa Depuración inalámbrica.'
                : 'Activa Depuración inalámbrica',
          ),
          findsWidgets,
        );
        await tester.pumpWidget(const SizedBox.shrink());
      },
    );
  }

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
