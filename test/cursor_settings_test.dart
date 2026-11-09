import 'dart:ui' show Tristate;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:uwu_mouse/application/ports/mapper_gateway.dart';
import 'package:uwu_mouse/features/settings/presentation/cursor_settings.dart';

class _SettingsGateway implements MapperGateway {
  String style = 'classic';
  bool failSave = false;

  @override
  Future<String> cursorStyle() async => style;

  @override
  Future<void> setCursorStyle(String value) async {
    if (failSave) throw StateError('save failed');
    style = value;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  testWidgets('saves a selection and restores it when settings reopen', (
    tester,
  ) async {
    final gateway = _SettingsGateway();
    Widget page() => MaterialApp(
      home: Scaffold(
        body: CursorSettings(gateway: gateway, onConnection: () {}),
      ),
    );
    await tester.pumpWidget(page());
    await tester.pumpAndSettle();
    expect(find.text('Clásico'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('cursor-settings')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('cursor-style-pink')));
    await tester.pumpAndSettle();
    expect(gateway.style, 'pink');
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pumpWidget(page());
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('cursor-settings')));
    await tester.pumpAndSettle();
    final semantics = tester.getSemantics(
      find.byKey(const ValueKey('cursor-style-pink')),
    );
    expect(semantics.flagsCollection.isSelected, Tristate.isTrue);
  });

  testWidgets('keeps the previous selection if saving fails', (tester) async {
    final gateway = _SettingsGateway()..failSave = true;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: CursorSettings(gateway: gateway, onConnection: () {}),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('cursor-settings')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('cursor-style-dark')));
    await tester.pumpAndSettle();
    expect(gateway.style, 'classic');
    expect(
      find.text('No se pudo guardar el estilo del cursor.'),
      findsOneWidget,
    );
  });
}
