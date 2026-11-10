import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:uwu_mouse/app/mapper_app.dart';

import 'support/profile_fixtures.dart' show displayProfileWire, profileWire;

void main() {
  const channel = MethodChannel('dev.uwumouse/control.v1');
  Future<void> openConnectionSettings(WidgetTester tester) async {
    await tester.tap(find.text('Ajustes'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Conexión'));
    await tester.pumpAndSettle();
  }

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
        await tester.pumpWidget(const UwUMouseApp());
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
        await tester.pumpWidget(const UwUMouseApp());
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
      await tester.pumpWidget(const UwUMouseApp());
      await tester.pumpAndSettle();
      final game = find.byKey(const ValueKey('game-com.dts.freefireth'));
      await tester.ensureVisible(game);
      await tester.pumpAndSettle();
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
    await tester.pumpWidget(const UwUMouseApp());
    await tester.pumpAndSettle();
    expect(find.text('Free Fire'), findsOneWidget);
    final initialReads = reads;
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    calibrated = true;
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();
    expect(find.text('Jugar'), findsNothing);
    expect(find.bySemanticsLabel('Jugar Free Fire'), findsOneWidget);
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
    await tester.pumpWidget(const UwUMouseApp());
    await tester.pumpAndSettle();
    final play = find.byKey(const ValueKey('game-com.dts.freefireth'));
    await tester.scrollUntilVisible(
      play,
      150,
      scrollable: find.descendant(
        of: find.byType(RefreshIndicator),
        matching: find.byType(Scrollable),
      ),
    );
    expect(tester.widget<InkWell>(play).onTap, isNull);
    final stop = find.ancestor(
      of: find.text('Cerrar sesión'),
      matching: find.byWidgetPredicate((widget) => widget is ButtonStyleButton),
    );
    expect(tester.widget<ButtonStyleButton>(stop.first).onPressed, isNotNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('idle home has no session close or bottom menu action', (
    tester,
  ) async {
    var wire = status()
      ..['mode'] = 'stopped'
      ..['stopRequested'] = true;
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      channel,
      (call) async => switch (call.method) {
        'status' => wire,
        'listApps' || 'listProfiles' => <Object?>[],
        _ => null,
      },
    );
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        channel,
        null,
      ),
    );
    await tester.pumpWidget(const UwUMouseApp());
    await tester.pumpAndSettle();
    final close = find.byKey(const ValueKey('close-session'));
    expect(close, findsNothing);
    expect(find.byType(NavigationDestination), findsNWidgets(2));
    wire = status()..['sessionOpen'] = true;
    await tester.pump(const Duration(seconds: 1));
    await tester.pumpAndSettle();
    expect(close, findsOneWidget);
    expect(
      tester.getTopLeft(close).dy,
      lessThan(
        tester.getTopLeft(find.byKey(const ValueKey('connection-summary'))).dy,
      ),
    );
    wire = status()
      ..['mode'] = 'stopped'
      ..['stopRequested'] = true;
    await tester.pump(const Duration(seconds: 1));
    await tester.pumpAndSettle();
    expect(close, findsNothing);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  for (final enabled in <bool?>[true, false, null]) {
    testWidgets(
      'home system options open Android with real wireless state $enabled',
      (tester) async {
        final calls = <String>[];
        var wire = status(drawOverlays: enabled == true)
          ..['wirelessDebuggingEnabled'] = enabled;
        tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
          channel,
          (call) async {
            calls.add(call.method);
            return switch (call.method) {
              'status' => wire,
              'listApps' || 'listProfiles' => <Object?>[],
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
        await tester.pumpWidget(const UwUMouseApp());
        await tester.pumpAndSettle();
        final wireless = find.byKey(
          const ValueKey('wireless-debugging-settings'),
        );
        final overlay = find.byKey(const ValueKey('overlay-settings'));
        expect(find.text('Mostrar sobre otras apps'), findsOneWidget);
        expect(tester.getSize(wireless).height, inInclusiveRange(48, 56));
        expect(tester.getSize(overlay).height, inInclusiveRange(48, 56));
        final expected = enabled == null
            ? 'Configurar en Android'
            : enabled
            ? 'Activada'
            : 'Desactivada';
        expect(
          (tester.widget<ListTile>(wireless).subtitle as Text).data,
          expected,
        );
        expect(
          (tester.widget<ListTile>(overlay).subtitle as Text).data,
          enabled == true ? 'Permitida' : 'Necesita permiso',
        );
        expect(find.text('Captura nativa'), findsNothing);
        expect(find.text('Motor de toques'), findsNothing);
        expect(find.byKey(const ValueKey('connection-settings')), findsNothing);
        final wirelessIcon =
            (tester.widget<ListTile>(wireless).trailing! as ExcludeSemantics)
                    .child
                as Icon;
        expect(
          wirelessIcon.icon,
          enabled == null
              ? Icons.more_horiz_rounded
              : enabled
              ? Icons.check_circle_rounded
              : Icons.cancel_rounded,
        );
        expect(
          wirelessIcon.color,
          enabled == null
              ? const Color(0xff62758a)
              : enabled
              ? const Color(0xff1f7a4f)
              : const Color(0xffb83537),
        );
        final overlayIcon =
            (tester.widget<ListTile>(overlay).trailing! as ExcludeSemantics)
                    .child
                as Icon;
        expect(
          overlayIcon.icon,
          enabled == true ? Icons.check_circle_rounded : Icons.cancel_rounded,
        );
        expect(
          overlayIcon.color,
          enabled == true ? const Color(0xff1f7a4f) : const Color(0xffb83537),
        );
        expect(find.byType(Switch), findsNothing);
        await tester.tap(wireless);
        await tester.pumpAndSettle();
        expect(
          calls.where((method) => method == 'wirelessDebuggingSettings'),
          hasLength(1),
        );
        expect(
          (tester.widget<ListTile>(wireless).subtitle as Text).data,
          expected,
        );
        await tester.tap(overlay);
        await tester.pumpAndSettle();
        expect(
          calls.where((method) => method == 'openOverlaySettings'),
          hasLength(1),
        );
        expect(calls, isNot(contains('activationStartDiscovery')));
        expect(calls, isNot(contains('stop')));
        wire = status()..['wirelessDebuggingEnabled'] = true;
        await tester.pump(const Duration(seconds: 1));
        await tester.pumpAndSettle();
        expect(
          (tester.widget<ListTile>(wireless).subtitle as Text).data,
          'Activada',
        );
        expect(
          (tester.widget<ListTile>(overlay).subtitle as Text).data,
          'Permitida',
        );
        expect(find.byKey(const ValueKey('close-session')), findsNothing);
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox.shrink());
      },
    );
  }

  testWidgets('landscape home keeps system options side by side', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(850, 400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      channel,
      (call) async => switch (call.method) {
        'status' => status(),
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
      },
    );
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        channel,
        null,
      ),
    );
    await tester.pumpWidget(const UwUMouseApp());
    await tester.pumpAndSettle();
    final wireless = find.byKey(const ValueKey('wireless-debugging-settings'));
    final overlay = find.byKey(const ValueKey('overlay-settings'));
    expect(tester.getTopLeft(wireless).dy, tester.getTopLeft(overlay).dy);
    final game = find.byKey(const ValueKey('game-com.dts.freefireth'));
    expect(
      tester.getCenter(game).dy,
      lessThan(tester.getTopLeft(find.byType(NavigationBar)).dy),
    );
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  for (final (name, fields) in <(String, Map<String, Object?>)>[
    ('playing', {'mode': 'playing', 'active': true, 'sessionOpen': true}),
    ('paused without profile', {'sessionOpen': true}),
    ('closing without profile', {'stopRequested': true, 'mode': 'stopping'}),
    ('transitioning without profile', {'transitioning': true}),
    ('blocked', {'mode': 'blocked', 'releaseUnverified': true, 'contacts': 3}),
    ('recovering', {'mode': 'recovering', 'guardianClosurePending': true}),
  ]) {
    testWidgets('session card keeps close while $name outside navigation', (
      tester,
    ) async {
      var wire = status()..addAll(fields);
      final calls = <String>[];
      final stopped = Completer<void>();
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
        call,
      ) async {
        calls.add(call.method);
        return switch (call.method) {
          'status' => wire,
          'listApps' || 'listProfiles' => <Object?>[],
          'selectedProfile' => null,
          'cursorStyle' => 'classic',
          'stop' => stopped.future,
          _ => null,
        };
      });
      addTearDown(
        () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
          channel,
          null,
        ),
      );
      await tester.pumpWidget(const UwUMouseApp());
      await tester.pumpAndSettle();
      final close = find.byKey(const ValueKey('close-session'));
      expect(tester.widget<OutlinedButton>(close).onPressed, isNotNull);
      expect(
        find.ancestor(of: close, matching: find.byType(Card)),
        findsOneWidget,
      );
      expect(
        find.descendant(of: find.byType(NavigationBar), matching: close),
        findsNothing,
      );
      await tester.tap(
        find.descendant(
          of: find.byType(NavigationBar),
          matching: find.text('Ajustes'),
        ),
      );
      await tester.pumpAndSettle();
      expect(close, findsNothing);
      await tester.tap(
        find.descendant(
          of: find.byType(NavigationBar),
          matching: find.text('Inicio'),
        ),
      );
      await tester.pumpAndSettle();
      expect(tester.widget<OutlinedButton>(close).onPressed, isNotNull);
      await tester.tap(close);
      await tester.pump();
      expect(tester.widget<OutlinedButton>(close).onPressed, isNull);
      await tester.tap(close);
      expect(calls.where((method) => method == 'stop'), hasLength(1));
      expect(calls, isNot(contains('recoverPendingSession')));
      expect(calls, isNot(contains('startProfile')));
      wire = status()..['mode'] = 'stopped';
      stopped.complete();
      await tester.pumpAndSettle();
      expect(close, findsNothing);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    });
  }

  for (final calibrated in [true, false]) {
    testWidgets(
      'close bypasses pending ${calibrated ? 'launch' : 'mapping'} read without replay',
      (tester) async {
        var holdRead = false;
        final pendingRead = Completer<Map<String, Object?>>();
        final stopReply = Completer<void>();
        final calls = <String>[];
        var wire = status()..['sessionOpen'] = true;
        tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
          channel,
          (call) async {
            calls.add(call.method);
            return switch (call.method) {
              'status' => holdRead ? pendingRead.future : wire,
              'listApps' => [
                {
                  'packageName': 'com.dts.freefireth',
                  'label': 'Free Fire',
                  'gameHint': true,
                },
              ],
              'listProfiles' => [profileWire(calibrated: calibrated)],
              'selectedProfile' => profileWire(calibrated: calibrated),
              'stop' => stopReply.future,
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
        await tester.pumpWidget(const UwUMouseApp());
        await tester.pumpAndSettle();
        calls.clear();
        holdRead = true;
        final game = find.byKey(const ValueKey('game-com.dts.freefireth'));
        await tester.tap(game);
        await tester.pump();
        expect(calls, ['status']);
        final close = find.byKey(const ValueKey('close-session'));
        expect(tester.widget<OutlinedButton>(close).onPressed, isNotNull);
        await tester.tap(close);
        await tester.pump();
        expect(calls, ['status', 'stop']);
        expect(tester.widget<InkWell>(game).onTap, isNull);
        stopReply.complete();
        await tester.pump();
        expect(tester.widget<OutlinedButton>(close).onPressed, isNotNull);
        expect(tester.widget<InkWell>(game).onTap, isNull);
        wire = status()
          ..['mode'] = 'stopped'
          ..['stopRequested'] = true;
        holdRead = false;
        pendingRead.complete(
          status()
            ..['active'] = true
            ..['sessionOpen'] = true
            ..['mode'] = 'playing',
        );
        await tester.pumpAndSettle();
        expect(calls.where((method) => method == 'stop'), hasLength(1));
        expect(calls, isNot(contains('startProfile')));
        expect(calls, isNot(contains('openEditor')));
        expect(calls, isNot(contains('recoverPendingSession')));
        expect(tester.widget<InkWell>(game).onTap, isNotNull);
        expect(find.text('En control'), findsNothing);
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox.shrink());
      },
    );
  }

  testWidgets(
    'transient status replies during close retry without a sticky error',
    (tester) async {
      final pendingRead = Completer<Map<String, Object?>>();
      final calls = <String>[];
      var reads = 0;
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
        call,
      ) async {
        calls.add(call.method);
        if (call.method == 'status') {
          return switch (reads++) {
            0 => status()..['sessionOpen'] = true,
            1 => pendingRead.future,
            2 => throw PlatformException(
              code: 'STATUS_IN_PROGRESS',
              message: 'snapshot ocupado',
            ),
            3 => throw PlatformException(
              code: 'STATUS_STALE',
              message: 'snapshot anterior',
            ),
            _ =>
              status()
                ..['mode'] = 'stopped'
                ..['stopRequested'] = true,
          };
        }
        return switch (call.method) {
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
      await tester.pumpWidget(const UwUMouseApp());
      await tester.pumpAndSettle();
      final game = find.byKey(const ValueKey('game-com.dts.freefireth'));
      await tester.tap(game);
      await tester.pump();
      await tester.pump(const Duration(seconds: 1));
      await tester.tap(find.byKey(const ValueKey('close-session')));
      await tester.pump();
      pendingRead.completeError(
        PlatformException(code: 'STATUS_STALE', message: 'snapshot anterior'),
      );
      await tester.pumpAndSettle();
      expect(find.text('snapshot ocupado'), findsNothing);
      expect(find.text('snapshot anterior'), findsNothing);
      expect(tester.widget<InkWell>(game).onTap, isNull);
      await tester.pump(const Duration(seconds: 1));
      await tester.pumpAndSettle();
      expect(find.text('snapshot anterior'), findsNothing);
      await tester.pump(const Duration(seconds: 1));
      await tester.pumpAndSettle();
      expect(tester.widget<InkWell>(game).onTap, isNotNull);
      expect(find.text('Cerrando sesión'), findsNothing);
      expect(find.text('snapshot ocupado'), findsNothing);
      expect(find.text('snapshot anterior'), findsNothing);
      expect(reads, greaterThanOrEqualTo(5));
      expect(calls.where((method) => method == 'stop'), hasLength(1));
      expect(calls, isNot(contains('startProfile')));
      expect(calls, isNot(contains('openEditor')));
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets('last known session keeps close when native status fails', (
    tester,
  ) async {
    final calls = <String>[];
    var firstRead = true;
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      calls.add(call.method);
      if (call.method == 'status') {
        if (firstRead) {
          firstRead = false;
          return status()..['sessionOpen'] = true;
        }
        throw PlatformException(code: 'STATUS_UNAVAILABLE');
      }
      return switch (call.method) {
        'listApps' || 'listProfiles' => <Object?>[],
        _ => null,
      };
    });
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        channel,
        null,
      ),
    );
    await tester.pumpWidget(const UwUMouseApp());
    await tester.pumpAndSettle();
    await tester.pump(const Duration(seconds: 1));
    await tester.pumpAndSettle();
    final close = find.byKey(const ValueKey('close-session'));
    expect(tester.widget<OutlinedButton>(close).onPressed, isNotNull);
    await tester.tap(close);
    await tester.pumpAndSettle();
    expect(calls.where((method) => method == 'stop'), hasLength(1));
    expect(tester.widget<OutlinedButton>(close).onPressed, isNotNull);
    expect(calls, isNot(contains('recoverPendingSession')));
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  for (final (mode, releaseUnverified) in [
    ('blocked', false),
    ('recovering', false),
    ('paused', true),
  ]) {
    testWidgets(
      '$mode with releaseUnverified=$releaseUnverified recovers only the previous session',
      (tester) async {
        final calls = <String>[];
        final recovery = Completer<bool>();
        var wire = status()
          ..['mode'] = mode
          ..['releaseUnverified'] = releaseUnverified
          ..['guardianClosurePending'] = true
          ..['stopRequested'] = true
          ..['guardianSessionId'] = 123
          ..['guardianTargetPackage'] = 'com.dts.freefireth';
        tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
          channel,
          (call) async {
            calls.add(call.method);
            return switch (call.method) {
              'status' => wire,
              'listApps' => [
                {
                  'packageName': 'com.dts.freefireth',
                  'label': 'Free Fire',
                  'gameHint': true,
                },
              ],
              'listProfiles' => [profileWire()],
              'selectedProfile' => profileWire(),
              'recoverPendingSession' => recovery.future,
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
        await tester.pumpWidget(const UwUMouseApp());
        await tester.pumpAndSettle();
        final game = find.byKey(const ValueKey('game-com.dts.freefireth'));
        final recover = find.byKey(const ValueKey('recover-pending-session'));
        final mainScroll = find.descendant(
          of: find.byType(RefreshIndicator),
          matching: find.byType(Scrollable),
        );
        await tester.scrollUntilVisible(game, 150, scrollable: mainScroll);
        expect(tester.widget<InkWell>(game).onTap, isNull);
        expect(tester.widget<InkWell>(game).onLongPress, isNull);
        expect(find.text('Cerrar sesión anterior'), findsOneWidget);
        calls.clear();
        await tester.scrollUntilVisible(recover, -150, scrollable: mainScroll);
        await tester.tap(recover);
        await tester.pump();
        expect(tester.widget<FilledButton>(recover).onPressed, isNull);
        expect(calls, ['recoverPendingSession']);

        recovery.complete(true);
        await tester.pumpAndSettle();
        await tester.scrollUntilVisible(game, 150, scrollable: mainScroll);
        expect(tester.widget<InkWell>(game).onTap, isNull);
        expect(tester.widget<InkWell>(game).onLongPress, isNull);
        expect(find.byKey(const ValueKey('close-session')), findsOneWidget);
        expect(calls, isNot(contains('startProfile')));
        expect(calls, isNot(contains('openEditor')));
        expect(calls, isNot(contains('stop')));
        expect(
          calls.where((call) => call == 'recoverPendingSession'),
          hasLength(1),
        );

        // A focus request alone does not clear the barrier. Only native proof does.
        wire = status()
          ..['mode'] = 'stopped'
          ..['stopRequested'] = true;
        await tester.pump(const Duration(seconds: 1));
        await tester.pumpAndSettle();
        expect(recover, findsNothing);
        expect(calls, isNot(contains('startProfile')));
        expect(tester.widget<InkWell>(game).onLongPress, isNotNull);
        await tester.ensureVisible(game);
        await tester.tap(game);
        await tester.pumpAndSettle();
        expect(calls.where((call) => call == 'startProfile'), hasLength(1));
        expect(calls, isNot(contains('openEditor')));
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox.shrink());
      },
    );
  }

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
        await tester.pumpWidget(const UwUMouseApp());
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
      await tester.pumpWidget(const UwUMouseApp());
      await tester.pumpAndSettle();
      final game = find.byKey(const ValueKey('game-com.dts.freefireth'));
      await tester.scrollUntilVisible(
        game,
        150,
        scrollable: find.descendant(
          of: find.byType(RefreshIndicator),
          matching: find.byType(Scrollable),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(game);
      await tester.pumpAndSettle();
      expect(
        find.descendant(
          of: find.byKey(const ValueKey('overlay-permission')),
          matching: find.text('Mostrar sobre otras apps'),
        ),
        findsOneWidget,
      );
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
    await tester.pumpWidget(const UwUMouseApp());
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
    expect(
      find.descendant(
        of: find.byKey(const ValueKey('overlay-permission')),
        matching: find.text('Mostrar sobre otras apps'),
      ),
      findsOneWidget,
    );
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
      await tester.pumpWidget(const UwUMouseApp());
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
      await openConnectionSettings(tester);
      expect(
        find.descendant(
          of: find.byKey(const ValueKey('overlay-permission')),
          matching: find.text('Mostrar sobre otras apps'),
        ),
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
    await tester.pumpWidget(const UwUMouseApp());
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('game-com.dts.freefireth')));
    await tester.pumpAndSettle();
    expect(
      find.descendant(
        of: find.byKey(const ValueKey('overlay-permission')),
        matching: find.text('Mostrar sobre otras apps'),
      ),
      findsOneWidget,
    );
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
      await tester.pumpWidget(const UwUMouseApp());
      await tester.pump();
      expect(find.text('Depuración inalámbrica'), findsOneWidget);
      expect(find.text('Configurar en Android'), findsOneWidget);
      expect(find.text('Comprobar permiso en Android'), findsOneWidget);
      expect(find.text('Captura nativa'), findsNothing);
      expect(find.text('Motor de toques'), findsNothing);
      expect(find.textContaining('Desactivad'), findsNothing);
      expect(find.byKey(const ValueKey('connection-settings')), findsNothing);
      await openConnectionSettings(tester);
      expect(find.text('Activar accesibilidad'), findsNothing);
      expect(find.text('Activar motor'), findsNothing);
      pending.complete(status());
      await tester.pumpAndSettle();
      expect(find.text('Captura nativa'), findsNothing);
      expect(find.text('Motor de toques'), findsNothing);
      expect(find.text('Activar accesibilidad'), findsNothing);
      expect(find.text('Activar motor'), findsNothing);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets('home connection cards separate in landscape and remain usable', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(900, 500);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.textScaleFactorTestValue = 1.4;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    final calls = <String>[];
    mockActivation(calls, () => status(drawOverlays: false));
    await tester.pumpWidget(const UwUMouseApp());
    await tester.pumpAndSettle();

    final first = tester.getRect(
      find.byKey(const ValueKey('wireless-debugging-card')),
    );
    final second = tester.getRect(find.byKey(const ValueKey('overlay-card')));
    final bounds = tester.getRect(
      find.byKey(const ValueKey('connection-summary')),
    );
    expect(first.top, second.top);
    expect(second.left - first.right, greaterThanOrEqualTo(10));
    expect(first.left, greaterThanOrEqualTo(bounds.left));
    expect(second.right, lessThanOrEqualTo(bounds.right));
    await tester.tap(find.byKey(const ValueKey('wireless-debugging-settings')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('overlay-settings')));
    await tester.pumpAndSettle();
    expect(calls, contains('wirelessDebuggingSettings'));
    expect(calls, contains('openOverlaySettings'));
    expect(tester.takeException(), isNull);

    tester.view.physicalSize = const Size(320, 740);
    tester.platformDispatcher.textScaleFactorTestValue = 1.8;
    await tester.pumpAndSettle();
    final wireless = tester.getRect(
      find.byKey(const ValueKey('wireless-debugging-settings')),
    );
    final overlay = tester.getRect(
      find.byKey(const ValueKey('overlay-settings')),
    );
    expect(overlay.top, greaterThanOrEqualTo(wireless.bottom));
    expect(wireless.left, overlay.left);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets(
    'connection settings expose only missing services and update while open',
    (tester) async {
      final calls = <String>[];
      var captureReady = false, backend = false;
      mockActivation(
        calls,
        () => status(captureReady: captureReady, backend: backend),
      );
      await tester.pumpWidget(const UwUMouseApp());
      await tester.pumpAndSettle();
      expect(find.text('Depuración inalámbrica'), findsOneWidget);
      expect(find.text('Mostrar sobre otras apps'), findsOneWidget);
      expect(find.text('Captura nativa'), findsNothing);
      expect(find.text('Motor de toques'), findsNothing);
      expect(find.text('Preparar conexión'), findsNothing);
      await openConnectionSettings(tester);
      expect(find.text('Ajustes de conexión'), findsWidgets);
      expect(find.text('Activar accesibilidad'), findsNothing);
      expect(find.text('Activar motor'), findsOneWidget);
      expect(find.text('Iniciar en laboratorio'), findsNothing);
      expect(calls, isNot(contains('accessibilitySettings')));
      expect(calls, isNot(contains('activationStartDiscovery')));
      captureReady = true;
      await tester.pump(const Duration(seconds: 1));
      await tester.pumpAndSettle();
      expect(find.text('Captura nativa'), findsNothing);
      expect(find.text('Activar accesibilidad'), findsNothing);
      expect(find.text('Activar motor'), findsOneWidget);
      backend = true;
      await tester.pump(const Duration(seconds: 1));
      await tester.pumpAndSettle();
      expect(find.text('Motor de toques'), findsNothing);
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
      mockActivation(
        calls,
        () => status(captureReady: false, backend: false, drawOverlays: false),
      );
      await tester.pumpWidget(const UwUMouseApp());
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      await openConnectionSettings(tester);
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
      await tester.pumpWidget(const UwUMouseApp());
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('game-com.dts.freefireth')));
      // The launch action allows a bounded time for an existing Binder to attach.
      await tester.pump(const Duration(milliseconds: 300));
      await tester.pump(const Duration(milliseconds: 300));
      await tester.pumpAndSettle();
      expect(find.text('Captura nativa'), findsNothing);
      expect(find.text('Motor de toques'), findsNothing);
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
      expect(find.text('Motor de toques'), findsNothing);
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
      await tester.pumpWidget(const UwUMouseApp());
      await tester.pumpAndSettle();
      await openConnectionSettings(tester);
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
      expect(find.text('Motor de toques'), findsNothing);
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
      await tester.pumpWidget(const UwUMouseApp());
      await tester.pumpAndSettle();
      await openConnectionSettings(tester);
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
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      final backgroundReads = calls
          .where((c) => c == 'activationStatus')
          .length;
      await tester.pump(const Duration(seconds: 3));
      expect(
        calls.where((c) => c == 'activationStatus').length,
        backgroundReads,
      );
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();
      final resumedReads = calls.where((c) => c == 'activationStatus').length;
      expect(resumedReads, greaterThan(backgroundReads));
      await tester.pump(const Duration(seconds: 1));
      expect(
        calls.where((c) => c == 'activationStatus').length,
        resumedReads + 1,
      );
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
    await tester.pumpWidget(const UwUMouseApp());
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
    bool sessionOpen = false,
  }) {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call.method);
      onMethod?.call(call.method);
      return switch (call.method) {
        'status' => status(backend: backend())..['sessionOpen'] = sessionOpen,
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
    await tester.pumpWidget(const UwUMouseApp());
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

  testWidgets('main close invalidates cold setup before its completion', (
    tester,
  ) async {
    final calls = <String>[];
    var connecting = false, ready = false;
    mockColdLaunch(
      calls,
      sessionOpen: true,
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
    final close = find.byKey(const ValueKey('close-session'));
    expect(tester.widget<OutlinedButton>(close).onPressed, isNotNull);
    await tester.tap(close);
    await tester.pump();
    expect(calls.where((method) => method == 'stop'), hasLength(1));
    ready = true;
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pumpAndSettle();
    expect(calls, isNot(contains('startProfile')));
    expect(calls, isNot(contains('openEditor')));
    expect(calls.where((method) => method == 'activationCancel'), hasLength(1));
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

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
    await tester.pumpWidget(const UwUMouseApp());
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('game-com.dts.freefireth')));
    await tester.pumpAndSettle();
    expect(
      find.text('La captura de teclado y ratón no está disponible.'),
      findsOneWidget,
    );
    expect(find.text('Motor de toques'), findsNothing);
    expect(find.text('Activar motor'), findsNothing);
    expect(find.textContaining('Activar-UwU-Mouse.cmd'), findsNothing);
    expect(calls, isNot(contains('accessibilitySettings')));
    expect(calls, isNot(contains('startProfile')));
    expect(
      find.text('La captura de teclado y ratón no está disponible.'),
      findsOneWidget,
    );
    expect(find.text('Activar accesibilidad'), findsNothing);
    expect(calls, isNot(contains('accessibilitySettings')));
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
