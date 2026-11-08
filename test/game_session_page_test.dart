import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobimapper/app/mapper_app.dart';

import 'support/profile_fixtures.dart' show displayProfileWire, profileWire;

void main() {
  const channel = MethodChannel('dev.mobimapper/control.v1');

  Map<String, Object?> status({
    bool blocked = false,
    bool backend = true,
    bool captureReady = true,
    int uiDisplayId = 0,
    bool drawOverlays = true,
  }) => {
    'protocol': 1,
    'mode': blocked ? 'blocked' : 'paused',
    'state': blocked ? 'Bloqueada' : 'Pausada',
    'error': blocked ? 'Cancelación anterior no verificada' : '',
    'accessibility': false,
    'captureReady': captureReady,
    'captureBackend': 'native-evdev',
    'captureError': '',
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
    'uiDisplayId': uiDisplayId,
    'drawOverlays': drawOverlays,
    'overlayPermission': drawOverlays,
  };

  for (final displayId in [0, 2]) {
    testWidgets(
      'library prefers display $displayId before a globally selected foreign profile',
      (tester) async {
        final phone = displayProfileWire('phone', 'phone-profile');
        final dex = displayProfileWire('external', 'dex-profile');
        final rows = [phone, dex];
        var selected = displayId == 0 ? dex : phone;
        final expected = displayId == 0 ? phone : dex;
        final starts = <Object?>[];
        tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
          channel,
          (call) async {
            switch (call.method) {
              case 'status':
                return status(uiDisplayId: displayId);
              case 'listApps':
                return [
                  {
                    'packageName': 'com.dts.freefireth',
                    'label': 'Free Fire',
                    'gameHint': true,
                  },
                ];
              case 'listProfiles':
                return rows;
              case 'selectedProfile':
                return selected;
              case 'selectProfile':
                selected = rows.firstWhere(
                  (row) => row['id'] == (call.arguments as Map)['id'],
                );
                return selected;
              case 'startProfile':
                starts.add((call.arguments as Map)['id']);
                return null;
              default:
                return null;
            }
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
        final game = find.byKey(const ValueKey('game-com.dts.freefireth'));
        await tester.tap(game);
        await tester.pumpAndSettle();
        expect(starts, [expected['id']]);
        await tester.longPress(game);
        await tester.pumpAndSettle();
        final picker = tester.widget<DropdownButtonFormField<String>>(
          find.byType(DropdownButtonFormField<String>),
        );
        expect(picker.initialValue, expected['id']);
        final dropdown = tester.widget<DropdownButton<String>>(
          find.descendant(
            of: find.byType(DropdownButtonFormField<String>),
            matching: find.byType(DropdownButton<String>),
          ),
        );
        expect(dropdown.items!.map((item) => item.value), [expected['id']]);
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox.shrink());
      },
    );

    testWidgets(
      'profile sheet actions follow the selected profile on display $displayId',
      (tester) async {
        final scope = displayId == 0 ? 'phone' : 'external';
        final first = displayProfileWire(scope, 'profile-a')
          ..['name'] = 'Perfil A';
        final second = displayProfileWire(scope, 'profile-b')
          ..['name'] = 'Perfil B';
        final foreign = displayProfileWire(
          displayId == 0 ? 'external' : 'phone',
          'foreign-profile',
        );
        final rows = [foreign, first, second];
        Map<Object?, Object?>? selected = first;
        final edited = <Object?>[],
            renamed = <Object?>[],
            deleted = <Object?>[];
        tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
          channel,
          (call) async {
            switch (call.method) {
              case 'status':
                return status(uiDisplayId: displayId);
              case 'listApps':
                return [
                  {
                    'packageName': 'com.dts.freefireth',
                    'label': 'Free Fire',
                    'gameHint': true,
                  },
                ];
              case 'listProfiles':
                return rows;
              case 'selectedProfile':
                return selected;
              case 'selectProfile':
                selected = rows.firstWhere(
                  (row) => row['id'] == (call.arguments as Map)['id'],
                );
                return selected;
              case 'openEditor':
                edited.add((call.arguments as Map)['id']);
                return null;
              case 'renameProfile':
                final args = call.arguments as Map;
                renamed.add(args['id']);
                final row = rows.firstWhere((row) => row['id'] == args['id']);
                row['name'] = args['name'];
                return row;
              case 'deleteProfile':
                final id = (call.arguments as Map)['id'];
                deleted.add(id);
                rows.removeWhere((row) => row['id'] == id);
                if (selected?['id'] == id) selected = null;
                return null;
              default:
                return null;
            }
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
        await tester.longPress(
          find.byKey(const ValueKey('game-com.dts.freefireth')),
        );
        await tester.pumpAndSettle();
        final picker = find.byType(DropdownButtonFormField<String>);
        await tester.ensureVisible(picker);
        await tester.pumpAndSettle();
        await tester.tap(picker);
        await tester.pumpAndSettle();
        await tester.tap(find.text('Perfil B').last);
        await tester.pumpAndSettle();

        final edit = find.text('Editar sobre el juego');
        await tester.ensureVisible(edit);
        await tester.pumpAndSettle();
        await tester.tap(edit);
        await tester.pumpAndSettle();
        expect(edited, ['profile-b']);

        await tester.ensureVisible(find.text('Renombrar'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Renombrar'));
        await tester.pumpAndSettle();
        expect(
          tester.widget<TextField>(find.byType(TextField)).controller!.text,
          'Perfil B',
        );
        await tester.enterText(find.byType(TextField), 'Perfil B nuevo');
        await tester.tap(find.text('Guardar'));
        await tester.pumpAndSettle();
        expect(renamed, ['profile-b']);
        expect(find.text('Perfil B nuevo'), findsWidgets);

        await tester.ensureVisible(find.text('Eliminar'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Eliminar'));
        await tester.pumpAndSettle();
        expect(
          find.text('Se eliminará «Perfil B nuevo» de este teléfono.'),
          findsOneWidget,
        );
        await tester.tap(find.text('Eliminar').last);
        await tester.pumpAndSettle();
        expect(deleted, ['profile-b']);
        expect(rows.map((row) => row['id']), ['foreign-profile', 'profile-a']);
        expect(
          tester.widget<DropdownButtonFormField<String>>(picker).initialValue,
          'profile-a',
        );
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox.shrink());
      },
    );
  }

  testWidgets(
    'first external launch keeps a phone fallback available for native calibration',
    (tester) async {
      final phone = displayProfileWire('phone', 'phone-profile');
      Object? launched;
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
        call,
      ) async {
        return switch (call.method) {
          'status' => status(uiDisplayId: 2),
          'listApps' => [
            {
              'packageName': 'com.dts.freefireth',
              'label': 'Free Fire',
              'gameHint': true,
            },
          ],
          'listProfiles' => [phone],
          'selectedProfile' || 'selectProfile' => phone,
          'startProfile' => (() {
            launched = (call.arguments as Map)['id'];
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
      final game = find.byKey(const ValueKey('game-com.dts.freefireth'));
      await tester.tap(game);
      await tester.pumpAndSettle();
      expect(launched, 'phone-profile');
      await tester.longPress(game);
      await tester.pumpAndSettle();
      final picker = tester.widget<DropdownButtonFormField<String>>(
        find.byType(DropdownButtonFormField<String>),
      );
      expect(picker.initialValue, 'phone-profile');
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

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
    'DeX launch requests windows permission and refreshes after Android settings',
    (tester) async {
      tester.view.physicalSize = const Size(320, 740);
      tester.view.devicePixelRatio = 1;
      tester.platformDispatcher.textScaleFactorTestValue = 1.8;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      final calls = <String>[];
      var allowed = false;
      mockActivation(
        calls,
        () => status(uiDisplayId: 2, drawOverlays: allowed),
      );
      await tester.pumpWidget(const MobiMapperApp());
      await tester.pumpAndSettle();
      final game = find.byKey(const ValueKey('game-com.dts.freefireth'));
      await tester.tap(game);
      await tester.pumpAndSettle();
      expect(find.text('Mostrar sobre otras apps'), findsOneWidget);
      expect(find.text('Listo para abrir tu juego'), findsNothing);
      expect(calls, isNot(contains('startProfile')));
      expect(calls, isNot(contains('openEditor')));
      expect(calls, isNot(contains('activationStartDiscovery')));
      final allow = find.text('Permitir superposición');
      await tester.ensureVisible(allow);
      await tester.pumpAndSettle();
      await tester.tap(allow);
      await tester.pumpAndSettle();
      expect(
        calls.where((call) => call == 'openOverlaySettings'),
        hasLength(1),
      );
      expect(tester.takeException(), isNull);

      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      allowed = true;
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();
      expect(find.text('Permitido'), findsOneWidget);
      expect(find.text('Permitir superposición'), findsNothing);
      expect(calls, isNot(contains('startProfile')));
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      await tester.tap(game);
      await tester.pumpAndSettle();
      expect(calls.where((call) => call == 'startProfile'), hasLength(1));
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets('DeX editing asks permission before opening the native editor', (
    tester,
  ) async {
    final calls = <String>[];
    mockActivation(calls, () => status(uiDisplayId: 2, drawOverlays: false));
    await tester.pumpWidget(const MobiMapperApp());
    await tester.pumpAndSettle();
    await tester.longPress(
      find.byKey(const ValueKey('game-com.dts.freefireth')),
    );
    await tester.pumpAndSettle();
    final edit = find.text('Editar sobre el juego');
    await tester.ensureVisible(edit);
    await tester.pumpAndSettle();
    await tester.tap(edit);
    await tester.pumpAndSettle();
    expect(find.text('Mostrar sobre otras apps'), findsOneWidget);
    expect(find.text('Permitir superposición'), findsOneWidget);
    expect(calls, isNot(contains('openEditor')));
    expect(calls, isNot(contains('startProfile')));
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  for (final displayId in [0, 2]) {
    testWidgets('overlay permission flow follows UI display $displayId', (
      tester,
    ) async {
      final calls = <String>[];
      mockActivation(
        calls,
        () => status(uiDisplayId: displayId, drawOverlays: true),
      );
      await tester.pumpWidget(const MobiMapperApp());
      await tester.pumpAndSettle();
      final game = find.byKey(const ValueKey('game-com.dts.freefireth'));
      await tester.tap(game);
      await tester.pumpAndSettle();
      expect(calls.where((call) => call == 'startProfile'), hasLength(1));
      await tester.longPress(game);
      await tester.pumpAndSettle();
      final edit = find.text('Editar sobre el juego');
      await tester.ensureVisible(edit);
      await tester.pumpAndSettle();
      await tester.tap(edit);
      await tester.pumpAndSettle();
      expect(calls.where((call) => call == 'openEditor'), hasLength(1));
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('connection-settings')));
      await tester.pumpAndSettle();
      expect(
        find.text('Mostrar sobre otras apps'),
        findsOneWidget,
      );
      expect(find.text('Permitir superposición'), findsNothing);
      expect(find.text('Activar accesibilidad'), findsNothing);
      expect(find.text('Activar motor'), findsNothing);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    });
  }

  testWidgets('revoked DeX permission routes a native rejection to settings', (
    tester,
  ) async {
    var allowed = true;
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      return switch (call.method) {
        'status' => status(uiDisplayId: 2, drawOverlays: allowed),
        'listApps' => [
          {
            'packageName': 'com.dts.freefireth',
            'label': 'Free Fire',
            'gameHint': true,
          },
        ],
        'listProfiles' => [profileWire()],
        'selectedProfile' => profileWire(),
        'startProfile' => (() {
          allowed = false;
          throw PlatformException(code: 'EXTERNAL_OVERLAY_PERMISSION_REQUIRED');
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
    await tester.tap(find.byKey(const ValueKey('game-com.dts.freefireth')));
    await tester.pumpAndSettle();
    expect(find.text('Mostrar sobre otras apps'), findsOneWidget);
    expect(find.text('Permitir superposición'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets(
    'connection summary waits for status before reporting inactive services',
    (tester) async {
      final pending = Completer<Map<String, Object?>>();
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        channel,
        (call) async => switch (call.method) {
          'status' => pending.future,
          'listApps' => <Object?>[],
          'listProfiles' => <Object?>[],
          _ => null,
        },
      );
      addTearDown(
        () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
          channel,
          null,
        ),
      );
      await tester.pumpWidget(const MobiMapperApp());
      await tester.pump();
      expect(find.text('Captura nativa: Comprobando'), findsOneWidget);
      expect(find.text('Motor de toques: Comprobando'), findsOneWidget);
      expect(find.textContaining('Desactivad'), findsNothing);
      final settings = find.byKey(const ValueKey('connection-settings'));
      expect(settings, findsOneWidget);
      await tester.tap(settings);
      await tester.pumpAndSettle();
      expect(find.text('Activar accesibilidad'), findsNothing);
      expect(find.text('Activar motor'), findsNothing);
      pending.complete(status());
      await tester.pumpAndSettle();
      expect(find.text('Captura nativa: Activada'), findsOneWidget);
      expect(find.text('Motor de toques: Activado'), findsOneWidget);
      expect(find.text('Activar accesibilidad'), findsNothing);
      expect(find.text('Activar motor'), findsNothing);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'connection settings expose only missing services and update while open',
    (tester) async {
      final calls = <String>[];
      var captureReady = false, backend = false;
      mockActivation(
        calls,
        () => status(captureReady: captureReady, backend: backend),
      );
      await tester.pumpWidget(const MobiMapperApp());
      await tester.pumpAndSettle();
      expect(find.text('Captura nativa: Desactivada'), findsOneWidget);
      expect(find.text('Motor de toques: Desactivado'), findsOneWidget);
      expect(find.text('Preparar conexión'), findsNothing);
      await tester.tap(find.byKey(const ValueKey('connection-settings')));
      await tester.pumpAndSettle();
      expect(find.text('Ajustes de conexión'), findsWidgets);
      expect(find.text('Activar accesibilidad'), findsNothing);
      expect(find.text('Activar motor'), findsOneWidget);
      expect(find.text('Iniciar en laboratorio'), findsNothing);
      expect(calls, isNot(contains('accessibilitySettings')));
      expect(calls, isNot(contains('activationStartDiscovery')));
      captureReady = true;
      await tester.pump(const Duration(seconds: 1));
      await tester.pumpAndSettle();
      expect(find.text('Captura nativa: Activada'), findsOneWidget);
      expect(find.text('Activar accesibilidad'), findsNothing);
      expect(find.text('Activar motor'), findsOneWidget);
      backend = true;
      await tester.pump(const Duration(seconds: 1));
      await tester.pumpAndSettle();
      expect(find.text('Motor de toques: Activado'), findsOneWidget);
      expect(find.text('Activar motor'), findsNothing);
      expect(find.text('Listo para abrir tu juego'), findsOneWidget);
      expect(calls, isNot(contains('startProfile')));
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'connection controls remain usable on a narrow screen with large text',
    (tester) async {
      tester.view.physicalSize = const Size(320, 740);
      tester.view.devicePixelRatio = 1;
      tester.platformDispatcher.textScaleFactorTestValue = 1.8;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      final calls = <String>[];
      mockActivation(calls, () => status(captureReady: false, backend: false, drawOverlays: false));
      await tester.pumpWidget(const MobiMapperApp());
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      await tester.tap(find.byKey(const ValueKey('connection-settings')));
      await tester.pumpAndSettle();
      final overlay = find.text('Permitir superposición');
      await tester.ensureVisible(overlay);
      await tester.pumpAndSettle();
      await tester.tap(overlay);
      await tester.pumpAndSettle();
      expect(calls, contains('openOverlaySettings'));
      expect(calls, isNot(contains('accessibilitySettings')));
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

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
      expect(find.text('Captura nativa: Activada'), findsOneWidget);
      expect(find.text('Motor de toques: Desactivado'), findsOneWidget);
      expect(find.text('Activar motor'), findsOneWidget);
      expect(find.text('Activar accesibilidad'), findsNothing);
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
      expect(find.text('Motor de toques: Activado'), findsOneWidget);
      expect(find.text('Activar motor'), findsNothing);
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
      await tester.tap(find.text('Activar motor'));
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
      expect(find.text('Motor de toques: Activado'), findsOneWidget);
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
      await tester.tap(find.text('Activar motor'));
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

  testWidgets('missing native capture is distinguished from a ready motor', (
    tester,
  ) async {
    final calls = <String>[];
    mockActivation(calls, () => status(captureReady: false));
    await tester.pumpWidget(const MobiMapperApp());
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('game-com.dts.freefireth')));
    await tester.pumpAndSettle();
    expect(find.text('Captura nativa: Desactivada'), findsOneWidget);
    expect(find.text('Motor de toques: Activado'), findsOneWidget);
    expect(find.text('Activar motor'), findsNothing);
    expect(find.textContaining('Activar-UwU-Mouse.cmd'), findsNothing);
    expect(calls, isNot(contains('accessibilitySettings')));
    expect(calls, isNot(contains('startProfile')));
    expect(find.text('Captura nativa: Desactivada'), findsOneWidget);
    expect(find.text('Activar accesibilidad'), findsNothing);
    expect(calls, isNot(contains('accessibilitySettings')));
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
