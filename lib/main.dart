import 'package:flutter/material.dart';

import 'app/mapper_app.dart';
import 'app/theme.dart';
import 'features/connection/presentation/local_activation_page.dart';
import 'platform/android/method_channel_mapper_gateway.dart';

void main() => runApp(const UwUMouseApp());

@pragma('vm:entry-point')
void activationMain() => runApp(
  MaterialApp(
    title: 'Activar motor',
    debugShowCheckedModeBanner: false,
    theme: buildAppTheme(),
    home: const LocalActivationPage(gateway: MethodChannelMapperGateway()),
  ),
);
