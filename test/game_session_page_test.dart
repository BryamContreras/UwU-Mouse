import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobimapper/main.dart';

import 'native_api_test.dart' show profileWire;

void main() {
  const channel = MethodChannel('dev.mobimapper/control.v1');

  Map<String, Object?> status({bool blocked = false}) => {
    'protocol': 1,
    'mode': blocked ? 'blocked' : 'paused',
    'state': blocked ? 'Bloqueada' : 'Pausada',
    'error': blocked ? 'Cancelación anterior no verificada' : '',
    'accessibility': true,
    'backend': true,
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
}
