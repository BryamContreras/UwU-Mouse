import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobimapper/app/mapper_app.dart';
import 'package:mobimapper/application/ports/mapper_gateway.dart';

import 'support/profile_fixtures.dart';

void main() {
  testWidgets('library launches through an injected gateway without a platform channel', (
    tester,
  ) async {
    final gateway = _MemoryGateway();
    await tester.pumpWidget(MobiMapperApp(gateway: gateway));
    await tester.pumpAndSettle();
    expect(find.text('Free Fire'), findsOneWidget);
    expect(find.text('Captura nativa: Activada'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('game-com.dts.freefireth')));
    await tester.pumpAndSettle();
    expect(gateway.startedProfiles, ['profile-1']);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('legacy accessibility cannot enable a missing native capture module', (
    tester,
  ) async {
    final gateway = _MemoryGateway(captureReady: false, legacyAccessibility: true);
    await tester.pumpWidget(MobiMapperApp(gateway: gateway));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('game-com.dts.freefireth')));
    await tester.pumpAndSettle();
    expect(gateway.startedProfiles, isEmpty);
    expect(find.text('Captura nativa: Desactivada'), findsOneWidget);
    expect(find.text('Activar accesibilidad'), findsNothing);
    expect(find.text('Listo para abrir tu juego'), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('phone mapping requires its application overlay permission too', (
    tester,
  ) async {
    final gateway = _MemoryGateway(overlayPermission: false);
    await tester.pumpWidget(MobiMapperApp(gateway: gateway));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('game-com.dts.freefireth')));
    await tester.pumpAndSettle();
    expect(gateway.startedProfiles, isEmpty);
    expect(find.text('Mostrar sobre otras apps'), findsOneWidget);
    final allow = find.text('Permitir superposición');
    await tester.ensureVisible(allow);
    await tester.tap(allow);
    await tester.pumpAndSettle();
    expect(gateway.commands, [NativeCommand.openOverlaySettings]);
    await tester.pumpWidget(const SizedBox.shrink());
  });
}

class _MemoryGateway implements MapperGateway {
  _MemoryGateway({
    this.captureReady = true,
    this.overlayPermission = true,
    this.legacyAccessibility = false,
  });

  final bool captureReady, overlayPermission, legacyAccessibility;
  final startedProfiles = <String>[];
  final commands = <NativeCommand>[];

  @override
  Future<NativeStatus> status() async => NativeStatus.fromWire({
    'protocol': 1,
    'state': 'Pausada',
    'mode': 'paused',
    'error': '',
    'accessibility': legacyAccessibility,
    'captureReady': captureReady,
    'captureBackend': 'native-evdev',
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
    'devices': <Object?>[],
    'uiDisplayId': 0,
    'overlayPermission': overlayPermission,
  });

  @override
  Future<List<LaunchableApp>> listApps() async => const [
    LaunchableApp(
      packageName: 'com.dts.freefireth',
      label: 'Free Fire',
      gameHint: true,
    ),
  ];

  @override
  Future<List<LocalProfile>> listProfiles() async => [
    LocalProfile.fromWire(profileWire()),
  ];

  @override
  Future<LocalProfile?> selectedProfile() async =>
      LocalProfile.fromWire(profileWire());

  @override
  Future<LocalProfile> selectProfile(String id) async =>
      LocalProfile.fromWire(profileWire());

  @override
  Future<void> startProfile(String id) async => startedProfiles.add(id);

  @override
  Future<void> execute(NativeCommand command) async => commands.add(command);

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
