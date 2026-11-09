import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:uwu_mouse/app/mapper_app.dart';

void main() {
  const channel = MethodChannel('dev.uwumouse/control.v1');

  for (final initial in <bool?>[true, false, null]) {
    testWidgets(
      'connection settings open Android for wireless state $initial',
      (tester) async {
        var enabled = initial;
        final calls = <String>[];
        tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
          channel,
          (call) async {
            calls.add(call.method);
            return switch (call.method) {
              'status' => {
                'protocol': 1,
                'state': 'Detenida',
                'mode': 'stopped',
                'error': '',
                'accessibility': false,
                'captureReady': true,
                'backend': true,
                'contacts': 0,
                'keys': 0,
                'mouse': 0,
                'relativeSamples': 0,
                'injected': 0,
                'releaseUnverified': false,
                'probe': '',
                'maxQueueDepth': 0,
                'mergedMoves': 0,
                'queueMs': <Object?>[],
                'ipcMs': <Object?>[],
                'overlayPermission': true,
                'wirelessDebuggingEnabled': enabled,
                'devices': <Object?>[],
              },
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
        await tester.tap(find.text('Ajustes'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Conexión'));
        await tester.pumpAndSettle();

        final configure = find.byKey(
          const ValueKey('connection-wireless-debugging'),
        );
        expect(find.text('Depuración inalámbrica'), findsOneWidget);
        expect(
          find.text(switch (initial) {
            true => 'Activada',
            false => 'Desactivada',
            null => 'Comprobando',
          }),
          findsOneWidget,
        );
        await tester.ensureVisible(configure);
        await tester.tap(configure);
        await tester.pumpAndSettle();
        expect(calls.where((call) => call == 'wirelessDebuggingSettings'), [
          'wirelessDebuggingSettings',
        ]);

        // On returning from Android, display its current value rather than
        // assuming the user enabled or disabled the permission there.
        enabled = initial != true;
        tester.binding.handleAppLifecycleStateChanged(
          AppLifecycleState.inactive,
        );
        tester.binding.handleAppLifecycleStateChanged(
          AppLifecycleState.resumed,
        );
        await tester.pumpAndSettle();
        expect(find.text(enabled ? 'Activada' : 'Desactivada'), findsOneWidget);
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox.shrink());
      },
    );
  }
}
