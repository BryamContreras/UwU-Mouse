import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../application/ports/mapper_gateway.dart';
import '../features/library/presentation/game_library_page.dart';
import '../platform/android/method_channel_mapper_gateway.dart';
import 'theme.dart';

/// Composition root: the only UI layer that selects a platform adapter.
class UwUMouseApp extends StatefulWidget {
  const UwUMouseApp({
    super.key,
    this.gateway = const MethodChannelMapperGateway(),
  });

  final MapperGateway gateway;

  @override
  State<UwUMouseApp> createState() => _UwUMouseAppState();
}

class _UwUMouseAppState extends State<UwUMouseApp> {
  static const _desktopScale = 1.15;
  static const _minimumContentWidth = 320.0;
  bool _externalDisplay = false;

  void _onDisplayChanged(int displayId) {
    final external = displayId != 0;
    if (_externalDisplay != external) {
      setState(() => _externalDisplay = external);
    }
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'UwU Mouse',
    debugShowCheckedModeBanner: false,
    theme: buildAppTheme(),
    builder: (context, child) =>
        _externalDisplay ? _desktopViewport(context, child!) : child!,
    home: GameLibraryPage(
      gateway: widget.gateway,
      onDisplayChanged: _onDisplayChanged,
    ),
  );

  Widget _desktopViewport(BuildContext context, Widget child) {
    final media = MediaQuery.of(context);
    return LayoutBuilder(
      builder: (context, constraints) {
        final viewport = constraints.biggest;
        if (!viewport.isFinite || viewport.isEmpty) return child;
        // Leave enough logical width for the library in a narrow desktop window.
        final scale = math.max(
          1.0,
          math.min(_desktopScale, viewport.width / _minimumContentWidth),
        );
        final size = viewport / scale;
        return FittedBox(
          fit: BoxFit.fill,
          child: SizedBox.fromSize(
            size: size,
            child: MediaQuery(
              data: media.copyWith(
                size: size,
                devicePixelRatio: media.devicePixelRatio * scale,
                padding: media.padding / scale,
                viewPadding: media.viewPadding / scale,
                viewInsets: media.viewInsets / scale,
                systemGestureInsets: media.systemGestureInsets / scale,
              ),
              child: child,
            ),
          ),
        );
      },
    );
  }
}
