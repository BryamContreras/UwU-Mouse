import 'package:flutter/material.dart';

String connectionState(bool? enabled, {bool feminine = false}) =>
    enabled == null
    ? 'Comprobando'
    : enabled
    ? (feminine ? 'Activada' : 'Activado')
    : (feminine ? 'Desactivada' : 'Desactivado');

Color connectionColor(bool? enabled) => enabled == null
    ? const Color(0xff62758a)
    : enabled
    ? const Color(0xff1f7a4f)
    : const Color(0xffb83537);

Widget connectionIcon(bool? enabled, {double size = 20}) => ExcludeSemantics(
  child: Icon(
    enabled == null
        ? Icons.more_horiz_rounded
        : enabled
        ? Icons.check_circle_rounded
        : Icons.cancel_rounded,
    color: connectionColor(enabled),
    size: size,
  ),
);
