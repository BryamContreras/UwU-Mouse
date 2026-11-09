import 'package:flutter/material.dart';

import '../application/ports/mapper_gateway.dart';
import '../features/library/presentation/game_library_page.dart';
import '../platform/android/method_channel_mapper_gateway.dart';
import 'theme.dart';

/// Composition root: the only UI layer that selects a platform adapter.
class UwUMouseApp extends StatelessWidget {
  const UwUMouseApp({
    super.key,
    this.gateway = const MethodChannelMapperGateway(),
  });

  final MapperGateway gateway;

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'UwU Mouse',
    debugShowCheckedModeBanner: false,
    theme: buildAppTheme(),
    home: GameLibraryPage(gateway: gateway),
  );
}
