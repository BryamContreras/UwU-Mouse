import 'package:flutter/material.dart';

/// Original UwUMouse vector glyphs. Material IconData values are identifiers only.
abstract final class UwuIcons {
  // A registered-font code point is an identifier only; artwork is always painted below.
  static const brand = IconData(0xe000, fontFamily: 'MaterialIcons');
}

/// Theme-aware, cached path artwork; no font, asset decoding or path parsing at paint time.
class UwuIcon extends StatelessWidget {
  const UwuIcon(
    this.icon, {
    super.key,
    this.size,
    this.color,
    this.semanticLabel,
    this.textDirection,
  });
  final IconData? icon;
  final double? size;
  final Color? color;
  final String? semanticLabel;
  final TextDirection? textDirection;
  @override
  Widget build(BuildContext context) {
    final theme = IconTheme.of(context);
    final base = color ?? theme.color ?? Theme.of(context).colorScheme.primary;
    final tint = base.withValues(alpha: base.a * (theme.opacity ?? 1));
    final side = size ?? theme.size ?? 26;
    final direction =
        textDirection ?? Directionality.maybeOf(context) ?? TextDirection.ltr;
    final painted = SizedBox.square(
      dimension: side,
      child: CustomPaint(
        painter: _GlyphPainter(
          _resolve(icon),
          tint,
          icon?.matchTextDirection == true && direction == TextDirection.rtl,
        ),
      ),
    );
    return semanticLabel == null
        ? painted
        : Semantics(label: semanticLabel, image: true, child: painted);
  }
}

enum _Glyph {
  brand,
  controller,
  joystick,
  camera,
  action,
  area,
  fire,
  aim,
  map,
  keyboard,
  settings,
  close,
  plus,
  plusCircle,
  play,
  stop,
  power,
  mouse,
  move,
  link,
  lab,
  shield,
  search,
  apps,
  arrow,
  arrowDown,
  external,
  accessibility,
  warning,
  more,
  importFile,
  exportFile,
}

_Glyph _resolve(IconData? icon) => icon == UwuIcons.brand
    ? _Glyph.brand
    : (_identifiers[icon] ?? _Glyph.controller);
final _identifiers = <IconData, _Glyph>{
  Icons.file_open_outlined: _Glyph.importFile,
  Icons.save_alt_rounded: _Glyph.exportFile,
  Icons.accessibility_new: _Glyph.accessibility,
  Icons.add: _Glyph.plus,
  Icons.add_circle_outline_rounded: _Glyph.plusCircle,
  Icons.add_rounded: _Glyph.plus,
  Icons.apps: _Glyph.apps,
  Icons.cable_rounded: _Glyph.link,
  Icons.chevron_right: _Glyph.arrow,
  Icons.expand_more_rounded: _Glyph.arrowDown,
  Icons.keyboard_arrow_down_rounded: _Glyph.arrowDown,
  Icons.arrow_drop_down: _Glyph.arrowDown,
  Icons.close: _Glyph.close,
  Icons.error_outline_rounded: _Glyph.warning,
  Icons.gamepad_rounded: _Glyph.controller,
  Icons.link_rounded: _Glyph.link,
  Icons.more_horiz: _Glyph.more,
  Icons.mouse_outlined: _Glyph.mouse,
  Icons.open_in_new: _Glyph.external,
  Icons.open_with: _Glyph.move,
  Icons.play_arrow: _Glyph.play,
  Icons.play_arrow_rounded: _Glyph.play,
  Icons.power_settings_new: _Glyph.power,
  Icons.science_outlined: _Glyph.lab,
  Icons.search_rounded: _Glyph.search,
  Icons.shield_outlined: _Glyph.shield,
  Icons.sports_esports_outlined: _Glyph.controller,
  Icons.sports_esports_rounded: _Glyph.controller,
  Icons.stop_circle_outlined: _Glyph.stop,
  Icons.touch_app_outlined: _Glyph.action,
  Icons.tune: _Glyph.settings,
  Icons.tune_rounded: _Glyph.settings,
};

class _Shape {
  const _Shape(
    this.path, {
    this.accent = false,
    this.filled = false,
    this.opacity = 1,
    this.width = 1.8,
  });
  final Path path;
  final bool accent, filled;
  final double opacity, width;
}

final Map<_Glyph, List<_Shape>> _art = {
  _Glyph.importFile: [
    _Shape(
      Path()
        ..moveTo(4, 4)
        ..lineTo(15, 4)
        ..lineTo(20, 9)
        ..lineTo(20, 20)
        ..lineTo(4, 20)
        ..close(),
    ),
    _Shape(
      Path()
        ..moveTo(12, 8)
        ..lineTo(12, 16)
        ..moveTo(8.5, 12.5)
        ..lineTo(12, 16)
        ..lineTo(15.5, 12.5),
    ),
  ],
  _Glyph.exportFile: [
    _Shape(
      Path()
        ..moveTo(4, 15)
        ..lineTo(4, 20)
        ..lineTo(20, 20)
        ..lineTo(20, 15),
    ),
    _Shape(
      Path()
        ..moveTo(12, 16)
        ..lineTo(12, 4)
        ..moveTo(8, 8)
        ..lineTo(12, 4)
        ..lineTo(16, 8),
    ),
  ],
  _Glyph.arrowDown: [
    _Shape(
      Path()
        ..moveTo(5.5, 8.5)
        ..lineTo(12, 15)
        ..lineTo(18.5, 8.5),
      width: 2.1,
    ),
  ],
  _Glyph.brand: [
    _Shape(
      Path()
        ..moveTo(12, 2)
        ..cubicTo(6.8, 2, 3.8, 5.5, 3.8, 10)
        ..lineTo(3.8, 14.5)
        ..cubicTo(3.8, 19.6, 7, 22, 12, 22)
        ..cubicTo(17, 22, 20.2, 19.6, 20.2, 14.5)
        ..lineTo(20.2, 10)
        ..cubicTo(20.2, 5.5, 17.2, 2, 12, 2)
        ..close(),
      width: 1.7,
    ),
    _Shape(
      Path()
        ..moveTo(6.8, 11.2)
        ..lineTo(6.8, 12.2)
        ..cubicTo(6.8, 14.3, 10, 14.3, 10, 12.2)
        ..lineTo(10, 11.2)
        ..moveTo(14, 11.2)
        ..lineTo(14, 12.2)
        ..cubicTo(14, 14.3, 17.2, 14.3, 17.2, 12.2)
        ..lineTo(17.2, 11.2),
      width: 1.5,
    ),
    _Shape(
      Path()
        ..moveTo(10.2, 16.5)
        ..cubicTo(10.4, 18.7, 11.8, 18.8, 12, 17.2)
        ..cubicTo(12.2, 18.8, 13.6, 18.7, 13.8, 16.5),
      width: 1.4,
    ),
    _Shape(
      Path()
        ..moveTo(12, 5)
        ..lineTo(12, 7.5),
      width: 1.8,
    ),
  ],
  _Glyph.controller: [
    _Shape(
      Path()
        ..moveTo(7, 5.5)
        ..lineTo(17, 5.5)
        ..cubicTo(18.8, 5.5, 20, 6.8, 20.4, 8.6)
        ..lineTo(21.5, 16)
        ..cubicTo(21.9, 18.3, 19.6, 19.5, 18, 18)
        ..lineTo(15.5, 15.8)
        ..lineTo(8.5, 15.8)
        ..lineTo(6, 18)
        ..cubicTo(4.4, 19.5, 2.1, 18.3, 2.5, 16)
        ..lineTo(3.6, 8.6)
        ..cubicTo(4, 6.8, 5.2, 5.5, 7, 5.5)
        ..close(),
      accent: true,
      filled: true,
      opacity: 0.14,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(7, 5.5)
        ..lineTo(17, 5.5)
        ..cubicTo(18.8, 5.5, 20, 6.8, 20.4, 8.6)
        ..lineTo(21.5, 16)
        ..cubicTo(21.9, 18.3, 19.6, 19.5, 18, 18)
        ..lineTo(15.5, 15.8)
        ..lineTo(8.5, 15.8)
        ..lineTo(6, 18)
        ..cubicTo(4.4, 19.5, 2.1, 18.3, 2.5, 16)
        ..lineTo(3.6, 8.6)
        ..cubicTo(4, 6.8, 5.2, 5.5, 7, 5.5)
        ..close(),
      accent: false,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(6.2, 10.7)
        ..lineTo(10.2, 10.7)
        ..moveTo(8.2, 8.7)
        ..lineTo(8.2, 12.7),
      accent: true,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(17.8, 9.4)
        ..cubicTo(17.8, 9.9523, 17.3523, 10.4, 16.8, 10.4)
        ..cubicTo(16.2477, 10.4, 15.8, 9.9523, 15.8, 9.4)
        ..cubicTo(15.8, 8.8477, 16.2477, 8.4, 16.8, 8.4)
        ..cubicTo(17.3523, 8.4, 17.8, 8.8477, 17.8, 9.4)
        ..close(),
      accent: true,
      filled: true,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(15.8, 12)
        ..cubicTo(15.8, 12.5523, 15.3523, 13, 14.8, 13)
        ..cubicTo(14.2477, 13, 13.8, 12.5523, 13.8, 12)
        ..cubicTo(13.8, 11.4477, 14.2477, 11, 14.8, 11)
        ..cubicTo(15.3523, 11, 15.8, 11.4477, 15.8, 12)
        ..close(),
      accent: false,
      filled: true,
      opacity: 1,
      width: 1.8,
    ),
  ],
  _Glyph.joystick: [
    _Shape(
      Path()
        ..moveTo(3.5, 16)
        ..cubicTo(3.5, 12.8, 20.5, 12.8, 20.5, 16)
        ..lineTo(20.5, 18)
        ..cubicTo(20.5, 21.3, 3.5, 21.3, 3.5, 18)
        ..close(),
      accent: true,
      filled: true,
      opacity: 0.16,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(3.5, 16)
        ..cubicTo(3.5, 12.8, 20.5, 12.8, 20.5, 16)
        ..lineTo(20.5, 18)
        ..cubicTo(20.5, 21.3, 3.5, 21.3, 3.5, 18)
        ..close(),
      accent: false,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(11, 15)
        ..lineTo(13.5, 8.5),
      accent: true,
      filled: false,
      opacity: 1,
      width: 2.1,
    ),
    _Shape(
      Path()
        ..moveTo(17.8, 5.7)
        ..cubicTo(17.8, 7.4673, 16.3673, 8.9, 14.6, 8.9)
        ..cubicTo(12.8327, 8.9, 11.4, 7.4673, 11.4, 5.7)
        ..cubicTo(11.4, 3.9327, 12.8327, 2.5, 14.6, 2.5)
        ..cubicTo(16.3673, 2.5, 17.8, 3.9327, 17.8, 5.7)
        ..close(),
      accent: true,
      filled: true,
      opacity: 0.2,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(17.8, 5.7)
        ..cubicTo(17.8, 7.4673, 16.3673, 8.9, 14.6, 8.9)
        ..cubicTo(12.8327, 8.9, 11.4, 7.4673, 11.4, 5.7)
        ..cubicTo(11.4, 3.9327, 12.8327, 2.5, 14.6, 2.5)
        ..cubicTo(16.3673, 2.5, 17.8, 3.9327, 17.8, 5.7)
        ..close(),
      accent: true,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
  ],
  _Glyph.camera: [
    _Shape(
      Path()
        ..moveTo(5.8, 6.2)
        ..lineTo(18.2, 6.2)
        ..quadraticBezierTo(21.2, 6.2, 21.2, 9.2)
        ..lineTo(21.2, 17.2)
        ..quadraticBezierTo(21.2, 20.2, 18.2, 20.2)
        ..lineTo(5.8, 20.2)
        ..quadraticBezierTo(2.8, 20.2, 2.8, 17.2)
        ..lineTo(2.8, 9.2)
        ..quadraticBezierTo(2.8, 6.2, 5.8, 6.2)
        ..close(),
      accent: true,
      filled: true,
      opacity: 0.14,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(5.8, 6.2)
        ..lineTo(18.2, 6.2)
        ..quadraticBezierTo(21.2, 6.2, 21.2, 9.2)
        ..lineTo(21.2, 17.2)
        ..quadraticBezierTo(21.2, 20.2, 18.2, 20.2)
        ..lineTo(5.8, 20.2)
        ..quadraticBezierTo(2.8, 20.2, 2.8, 17.2)
        ..lineTo(2.8, 9.2)
        ..quadraticBezierTo(2.8, 6.2, 5.8, 6.2)
        ..close(),
      accent: false,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(7.6, 6.2)
        ..lineTo(9.3, 3.6)
        ..lineTo(14.7, 3.6)
        ..lineTo(16.4, 6.2),
      accent: false,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(16, 13)
        ..cubicTo(16, 15.2091, 14.2091, 17, 12, 17)
        ..cubicTo(9.7909, 17, 8, 15.2091, 8, 13)
        ..cubicTo(8, 10.7909, 9.7909, 9, 12, 9)
        ..cubicTo(14.2091, 9, 16, 10.7909, 16, 13)
        ..close(),
      accent: true,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(18.85, 9.1)
        ..cubicTo(18.85, 9.5142, 18.5142, 9.85, 18.1, 9.85)
        ..cubicTo(17.6858, 9.85, 17.35, 9.5142, 17.35, 9.1)
        ..cubicTo(17.35, 8.6858, 17.6858, 8.35, 18.1, 8.35)
        ..cubicTo(18.5142, 8.35, 18.85, 8.6858, 18.85, 9.1)
        ..close(),
      accent: false,
      filled: true,
      opacity: 1,
      width: 1.8,
    ),
  ],
  _Glyph.action: [
    _Shape(
      Path()
        ..moveTo(9, 12)
        ..lineTo(9, 5.8)
        ..cubicTo(9, 3.3, 12.4, 3.3, 12.4, 5.8)
        ..lineTo(12.4, 10.5)
        ..lineTo(14.3, 10.5)
        ..cubicTo(15.4, 10.5, 16.2, 11.4, 17.2, 11.7)
        ..lineTo(18.9, 12.3)
        ..cubicTo(20, 12.7, 20.5, 14, 20, 15.3)
        ..lineTo(18.2, 20.5)
        ..lineTo(10.7, 20.5)
        ..lineTo(5.5, 14.7)
        ..cubicTo(4, 12.8, 6.5, 10.7, 8, 12.3)
        ..lineTo(9.5, 14),
      accent: false,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(5.6, 6.8)
        ..lineTo(3.5, 6.2)
        ..moveTo(6.8, 3.4)
        ..lineTo(5.8, 1.8)
        ..moveTo(15.6, 4.3)
        ..lineTo(17.5, 3),
      accent: true,
      filled: false,
      opacity: 1,
      width: 1.7,
    ),
  ],
  _Glyph.area: [
    _Shape(
      Path()
        ..moveTo(6.5, 5)
        ..lineTo(17.5, 5)
        ..quadraticBezierTo(19, 5, 19, 6.5)
        ..lineTo(19, 17.5)
        ..quadraticBezierTo(19, 19, 17.5, 19)
        ..lineTo(6.5, 19)
        ..quadraticBezierTo(5, 19, 5, 17.5)
        ..lineTo(5, 6.5)
        ..quadraticBezierTo(5, 5, 6.5, 5)
        ..close(),
      accent: true,
      filled: true,
      opacity: 0.12,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(3, 8)
        ..lineTo(3, 3)
        ..lineTo(8, 3)
        ..moveTo(16, 3)
        ..lineTo(21, 3)
        ..lineTo(21, 8)
        ..moveTo(21, 16)
        ..lineTo(21, 21)
        ..lineTo(16, 21)
        ..moveTo(8, 21)
        ..lineTo(3, 21)
        ..lineTo(3, 16),
      accent: false,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(7.5, 7.5)
        ..lineTo(16.5, 7.5)
        ..lineTo(16.5, 16.5)
        ..lineTo(7.5, 16.5)
        ..close(),
      accent: true,
      filled: false,
      opacity: 1,
      width: 1.5,
    ),
  ],
  _Glyph.fire: [
    _Shape(
      Path()
        ..moveTo(19, 12)
        ..cubicTo(19, 15.866, 15.866, 19, 12, 19)
        ..cubicTo(8.134, 19, 5, 15.866, 5, 12)
        ..cubicTo(5, 8.134, 8.134, 5, 12, 5)
        ..cubicTo(15.866, 5, 19, 8.134, 19, 12)
        ..close(),
      accent: false,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(12, 2)
        ..lineTo(12, 6)
        ..moveTo(22, 12)
        ..lineTo(18, 12)
        ..moveTo(12, 22)
        ..lineTo(12, 18)
        ..moveTo(2, 12)
        ..lineTo(6, 12),
      accent: true,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(12, 8.2)
        ..lineTo(14.7, 12)
        ..lineTo(12, 15.8)
        ..lineTo(9.3, 12)
        ..close(),
      accent: true,
      filled: true,
      opacity: 1,
      width: 1.8,
    ),
  ],
  _Glyph.aim: [
    _Shape(
      Path()
        ..moveTo(20, 12)
        ..cubicTo(20, 16.4183, 16.4183, 20, 12, 20)
        ..cubicTo(7.5817, 20, 4, 16.4183, 4, 12)
        ..cubicTo(4, 7.5817, 7.5817, 4, 12, 4)
        ..cubicTo(16.4183, 4, 20, 7.5817, 20, 12)
        ..close(),
      accent: false,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(12, 1.7)
        ..lineTo(12, 7)
        ..moveTo(22.3, 12)
        ..lineTo(17, 12)
        ..moveTo(12, 22.3)
        ..lineTo(12, 17)
        ..moveTo(1.7, 12)
        ..lineTo(7, 12),
      accent: true,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(14.8, 12)
        ..cubicTo(14.8, 13.5464, 13.5464, 14.8, 12, 14.8)
        ..cubicTo(10.4536, 14.8, 9.2, 13.5464, 9.2, 12)
        ..cubicTo(9.2, 10.4536, 10.4536, 9.2, 12, 9.2)
        ..cubicTo(13.5464, 9.2, 14.8, 10.4536, 14.8, 12)
        ..close(),
      accent: true,
      filled: false,
      opacity: 1,
      width: 1.6,
    ),
  ],
  _Glyph.map: [
    _Shape(
      Path()
        ..moveTo(3, 6)
        ..lineTo(9, 3.5)
        ..lineTo(15, 6)
        ..lineTo(21, 3.5)
        ..lineTo(21, 18)
        ..lineTo(15, 20.5)
        ..lineTo(9, 18)
        ..lineTo(3, 20.5)
        ..close(),
      accent: true,
      filled: true,
      opacity: 0.13,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(3, 6)
        ..lineTo(9, 3.5)
        ..lineTo(15, 6)
        ..lineTo(21, 3.5)
        ..lineTo(21, 18)
        ..lineTo(15, 20.5)
        ..lineTo(9, 18)
        ..lineTo(3, 20.5)
        ..close(),
      accent: false,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(9, 3.5)
        ..lineTo(9, 18)
        ..moveTo(15, 6)
        ..lineTo(15, 20.5),
      accent: true,
      filled: false,
      opacity: 1,
      width: 1.6,
    ),
  ],
  _Glyph.keyboard: [
    _Shape(
      Path()
        ..moveTo(5.1, 5)
        ..lineTo(18.9, 5)
        ..quadraticBezierTo(21.5, 5, 21.5, 7.6)
        ..lineTo(21.5, 16.4)
        ..quadraticBezierTo(21.5, 19, 18.9, 19)
        ..lineTo(5.1, 19)
        ..quadraticBezierTo(2.5, 19, 2.5, 16.4)
        ..lineTo(2.5, 7.6)
        ..quadraticBezierTo(2.5, 5, 5.1, 5)
        ..close(),
      accent: true,
      filled: true,
      opacity: 0.14,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(5.1, 5)
        ..lineTo(18.9, 5)
        ..quadraticBezierTo(21.5, 5, 21.5, 7.6)
        ..lineTo(21.5, 16.4)
        ..quadraticBezierTo(21.5, 19, 18.9, 19)
        ..lineTo(5.1, 19)
        ..quadraticBezierTo(2.5, 19, 2.5, 16.4)
        ..lineTo(2.5, 7.6)
        ..quadraticBezierTo(2.5, 5, 5.1, 5)
        ..close(),
      accent: false,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(5.8, 9)
        ..lineTo(7.1, 9)
        ..moveTo(10.2, 9)
        ..lineTo(11.5, 9)
        ..moveTo(14.6, 9)
        ..lineTo(15.9, 9)
        ..moveTo(18.1, 9)
        ..lineTo(18.2, 9)
        ..moveTo(5.8, 12)
        ..lineTo(7.1, 12)
        ..moveTo(10.2, 12)
        ..lineTo(11.5, 12)
        ..moveTo(14.6, 12)
        ..lineTo(15.9, 12)
        ..moveTo(18.1, 12)
        ..lineTo(18.2, 12)
        ..moveTo(7, 15.5)
        ..lineTo(17, 15.5),
      accent: true,
      filled: false,
      opacity: 1,
      width: 1.6,
    ),
  ],
  _Glyph.settings: [
    _Shape(
      Path()
        ..moveTo(5, 3)
        ..lineTo(5, 8)
        ..moveTo(5, 12)
        ..lineTo(5, 21)
        ..moveTo(12, 3)
        ..lineTo(12, 13)
        ..moveTo(12, 17)
        ..lineTo(12, 21)
        ..moveTo(19, 3)
        ..lineTo(19, 6)
        ..moveTo(19, 10)
        ..lineTo(19, 21),
      accent: false,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(7.1, 10)
        ..cubicTo(7.1, 11.1598, 6.1598, 12.1, 5, 12.1)
        ..cubicTo(3.8402, 12.1, 2.9, 11.1598, 2.9, 10)
        ..cubicTo(2.9, 8.8402, 3.8402, 7.9, 5, 7.9)
        ..cubicTo(6.1598, 7.9, 7.1, 8.8402, 7.1, 10)
        ..close(),
      accent: true,
      filled: true,
      opacity: 0.18,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(7.1, 10)
        ..cubicTo(7.1, 11.1598, 6.1598, 12.1, 5, 12.1)
        ..cubicTo(3.8402, 12.1, 2.9, 11.1598, 2.9, 10)
        ..cubicTo(2.9, 8.8402, 3.8402, 7.9, 5, 7.9)
        ..cubicTo(6.1598, 7.9, 7.1, 8.8402, 7.1, 10)
        ..close(),
      accent: true,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(14.1, 15)
        ..cubicTo(14.1, 16.1598, 13.1598, 17.1, 12, 17.1)
        ..cubicTo(10.8402, 17.1, 9.9, 16.1598, 9.9, 15)
        ..cubicTo(9.9, 13.8402, 10.8402, 12.9, 12, 12.9)
        ..cubicTo(13.1598, 12.9, 14.1, 13.8402, 14.1, 15)
        ..close(),
      accent: true,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(21.1, 8)
        ..cubicTo(21.1, 9.1598, 20.1598, 10.1, 19, 10.1)
        ..cubicTo(17.8402, 10.1, 16.9, 9.1598, 16.9, 8)
        ..cubicTo(16.9, 6.8402, 17.8402, 5.9, 19, 5.9)
        ..cubicTo(20.1598, 5.9, 21.1, 6.8402, 21.1, 8)
        ..close(),
      accent: true,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
  ],
  _Glyph.close: [
    _Shape(
      Path()
        ..moveTo(6.5, 6.5)
        ..lineTo(17.5, 17.5)
        ..moveTo(17.5, 6.5)
        ..lineTo(6.5, 17.5),
      accent: false,
      filled: false,
      opacity: 1,
      width: 2.1,
    ),
  ],
  _Glyph.plus: [
    _Shape(
      Path()
        ..moveTo(12, 4.5)
        ..lineTo(12, 19.5)
        ..moveTo(4.5, 12)
        ..lineTo(19.5, 12),
      accent: false,
      filled: false,
      opacity: 1,
      width: 2.1,
    ),
  ],
  _Glyph.plusCircle: [
    _Shape(
      Path()
        ..moveTo(21, 12)
        ..cubicTo(21, 16.9706, 16.9706, 21, 12, 21)
        ..cubicTo(7.0294, 21, 3, 16.9706, 3, 12)
        ..cubicTo(3, 7.0294, 7.0294, 3, 12, 3)
        ..cubicTo(16.9706, 3, 21, 7.0294, 21, 12)
        ..close(),
      accent: false,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(12, 7.5)
        ..lineTo(12, 16.5)
        ..moveTo(7.5, 12)
        ..lineTo(16.5, 12),
      accent: true,
      filled: false,
      opacity: 1,
      width: 2,
    ),
  ],
  _Glyph.play: [
    _Shape(
      Path()
        ..moveTo(8, 4.5)
        ..lineTo(20, 12)
        ..lineTo(8, 19.5)
        ..close(),
      accent: true,
      filled: true,
      opacity: 0.18,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(8, 4.5)
        ..lineTo(20, 12)
        ..lineTo(8, 19.5)
        ..close(),
      accent: false,
      filled: false,
      opacity: 1,
      width: 1.9,
    ),
  ],
  _Glyph.stop: [
    _Shape(
      Path()
        ..moveTo(21, 12)
        ..cubicTo(21, 16.9706, 16.9706, 21, 12, 21)
        ..cubicTo(7.0294, 21, 3, 16.9706, 3, 12)
        ..cubicTo(3, 7.0294, 7.0294, 3, 12, 3)
        ..cubicTo(16.9706, 3, 21, 7.0294, 21, 12)
        ..close(),
      accent: false,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(9.3, 8)
        ..lineTo(14.7, 8)
        ..quadraticBezierTo(16, 8, 16, 9.3)
        ..lineTo(16, 14.7)
        ..quadraticBezierTo(16, 16, 14.7, 16)
        ..lineTo(9.3, 16)
        ..quadraticBezierTo(8, 16, 8, 14.7)
        ..lineTo(8, 9.3)
        ..quadraticBezierTo(8, 8, 9.3, 8)
        ..close(),
      accent: true,
      filled: true,
      opacity: 1,
      width: 1.8,
    ),
  ],
  _Glyph.power: [
    _Shape(
      Path()
        ..moveTo(12, 2.5)
        ..lineTo(12, 11),
      accent: true,
      filled: false,
      opacity: 1,
      width: 2.1,
    ),
    _Shape(
      Path()
        ..moveTo(6.4, 5.4)
        ..cubicTo(1.3, 8.4, 1, 15.3, 5.5, 19)
        ..cubicTo(9.4, 22.1, 15, 22, 18.6, 18.2)
        ..cubicTo(22.4, 14.2, 21.9, 8.1, 17.6, 5.4),
      accent: false,
      filled: false,
      opacity: 1,
      width: 1.9,
    ),
  ],
  _Glyph.mouse: [
    _Shape(
      Path()
        ..moveTo(12, 2)
        ..lineTo(12, 2)
        ..quadraticBezierTo(18, 2, 18, 8)
        ..lineTo(18, 16)
        ..quadraticBezierTo(18, 22, 12, 22)
        ..lineTo(12, 22)
        ..quadraticBezierTo(6, 22, 6, 16)
        ..lineTo(6, 8)
        ..quadraticBezierTo(6, 2, 12, 2)
        ..close(),
      accent: true,
      filled: true,
      opacity: 0.14,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(12, 2)
        ..lineTo(12, 2)
        ..quadraticBezierTo(18, 2, 18, 8)
        ..lineTo(18, 16)
        ..quadraticBezierTo(18, 22, 12, 22)
        ..lineTo(12, 22)
        ..quadraticBezierTo(6, 22, 6, 16)
        ..lineTo(6, 8)
        ..quadraticBezierTo(6, 2, 12, 2)
        ..close(),
      accent: false,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(6.3, 10)
        ..lineTo(17.7, 10)
        ..moveTo(12, 5.2)
        ..lineTo(12, 7.6),
      accent: true,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
  ],
  _Glyph.move: [
    _Shape(
      Path()
        ..moveTo(12, 2.5)
        ..lineTo(12, 21.5)
        ..moveTo(2.5, 12)
        ..lineTo(21.5, 12),
      accent: false,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(8.5, 6)
        ..lineTo(12, 2.5)
        ..lineTo(15.5, 6)
        ..moveTo(18, 8.5)
        ..lineTo(21.5, 12)
        ..lineTo(18, 15.5)
        ..moveTo(8.5, 18)
        ..lineTo(12, 21.5)
        ..lineTo(15.5, 18)
        ..moveTo(6, 8.5)
        ..lineTo(2.5, 12)
        ..lineTo(6, 15.5),
      accent: true,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
  ],
  _Glyph.link: [
    _Shape(
      Path()
        ..moveTo(10, 7.5)
        ..lineTo(12.5, 5)
        ..cubicTo(15.9, 1.6, 21.1, 6.8, 17.7, 10.2)
        ..lineTo(15.2, 12.7)
        ..moveTo(14, 16.5)
        ..lineTo(11.5, 19)
        ..cubicTo(8.1, 22.4, 2.9, 17.2, 6.3, 13.8)
        ..lineTo(8.8, 11.3),
      accent: false,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(8.8, 15.2)
        ..lineTo(15.2, 8.8),
      accent: true,
      filled: false,
      opacity: 1,
      width: 2.1,
    ),
  ],
  _Glyph.lab: [
    _Shape(
      Path()
        ..moveTo(8, 3)
        ..lineTo(16, 3)
        ..lineTo(16, 5)
        ..lineTo(14.5, 5)
        ..lineTo(14.5, 10)
        ..lineTo(20, 18.7)
        ..cubicTo(20.9, 20.1, 20, 21, 18.7, 21)
        ..lineTo(5.3, 21)
        ..cubicTo(4, 21, 3.1, 20.1, 4, 18.7)
        ..lineTo(9.5, 10)
        ..lineTo(9.5, 5)
        ..lineTo(8, 5)
        ..close(),
      accent: true,
      filled: true,
      opacity: 0.12,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(8, 3)
        ..lineTo(16, 3)
        ..moveTo(9.5, 3)
        ..lineTo(9.5, 10)
        ..lineTo(4, 18.7)
        ..cubicTo(3.1, 20.1, 4, 21, 5.3, 21)
        ..lineTo(18.7, 21)
        ..cubicTo(20, 21, 20.9, 20.1, 20, 18.7)
        ..lineTo(14.5, 10)
        ..lineTo(14.5, 3),
      accent: false,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(7.6, 15)
        ..lineTo(16.4, 15),
      accent: true,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(13.75, 17.7)
        ..cubicTo(13.75, 18.1694, 13.3694, 18.55, 12.9, 18.55)
        ..cubicTo(12.4306, 18.55, 12.05, 18.1694, 12.05, 17.7)
        ..cubicTo(12.05, 17.2306, 12.4306, 16.85, 12.9, 16.85)
        ..cubicTo(13.3694, 16.85, 13.75, 17.2306, 13.75, 17.7)
        ..close(),
      accent: true,
      filled: true,
      opacity: 1,
      width: 1.8,
    ),
  ],
  _Glyph.shield: [
    _Shape(
      Path()
        ..moveTo(12, 2.5)
        ..lineTo(20, 5.8)
        ..lineTo(20, 11.8)
        ..cubicTo(20, 17.4, 15.8, 20.8, 12, 22)
        ..cubicTo(8.2, 20.8, 4, 17.4, 4, 11.8)
        ..lineTo(4, 5.8)
        ..close(),
      accent: true,
      filled: true,
      opacity: 0.13,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(12, 2.5)
        ..lineTo(20, 5.8)
        ..lineTo(20, 11.8)
        ..cubicTo(20, 17.4, 15.8, 20.8, 12, 22)
        ..cubicTo(8.2, 20.8, 4, 17.4, 4, 11.8)
        ..lineTo(4, 5.8)
        ..close(),
      accent: false,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(8.2, 12)
        ..lineTo(10.8, 14.6)
        ..lineTo(16, 9.4),
      accent: true,
      filled: false,
      opacity: 1,
      width: 2,
    ),
  ],
  _Glyph.search: [
    _Shape(
      Path()
        ..moveTo(17.3, 10.5)
        ..cubicTo(17.3, 14.2555, 14.2555, 17.3, 10.5, 17.3)
        ..cubicTo(6.7445, 17.3, 3.7, 14.2555, 3.7, 10.5)
        ..cubicTo(3.7, 6.7445, 6.7445, 3.7, 10.5, 3.7)
        ..cubicTo(14.2555, 3.7, 17.3, 6.7445, 17.3, 10.5)
        ..close(),
      accent: false,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(15.5, 15.5)
        ..lineTo(21, 21),
      accent: true,
      filled: false,
      opacity: 1,
      width: 2.2,
    ),
  ],
  _Glyph.apps: [
    _Shape(
      Path()
        ..moveTo(4.7, 3)
        ..lineTo(8.3, 3)
        ..quadraticBezierTo(10, 3, 10, 4.7)
        ..lineTo(10, 8.3)
        ..quadraticBezierTo(10, 10, 8.3, 10)
        ..lineTo(4.7, 10)
        ..quadraticBezierTo(3, 10, 3, 8.3)
        ..lineTo(3, 4.7)
        ..quadraticBezierTo(3, 3, 4.7, 3)
        ..close(),
      accent: true,
      filled: true,
      opacity: 0.2,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(4.7, 3)
        ..lineTo(8.3, 3)
        ..quadraticBezierTo(10, 3, 10, 4.7)
        ..lineTo(10, 8.3)
        ..quadraticBezierTo(10, 10, 8.3, 10)
        ..lineTo(4.7, 10)
        ..quadraticBezierTo(3, 10, 3, 8.3)
        ..lineTo(3, 4.7)
        ..quadraticBezierTo(3, 3, 4.7, 3)
        ..close(),
      accent: false,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(15.7, 3)
        ..lineTo(19.3, 3)
        ..quadraticBezierTo(21, 3, 21, 4.7)
        ..lineTo(21, 8.3)
        ..quadraticBezierTo(21, 10, 19.3, 10)
        ..lineTo(15.7, 10)
        ..quadraticBezierTo(14, 10, 14, 8.3)
        ..lineTo(14, 4.7)
        ..quadraticBezierTo(14, 3, 15.7, 3)
        ..close(),
      accent: true,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(4.7, 14)
        ..lineTo(8.3, 14)
        ..quadraticBezierTo(10, 14, 10, 15.7)
        ..lineTo(10, 19.3)
        ..quadraticBezierTo(10, 21, 8.3, 21)
        ..lineTo(4.7, 21)
        ..quadraticBezierTo(3, 21, 3, 19.3)
        ..lineTo(3, 15.7)
        ..quadraticBezierTo(3, 14, 4.7, 14)
        ..close(),
      accent: true,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(15.7, 14)
        ..lineTo(19.3, 14)
        ..quadraticBezierTo(21, 14, 21, 15.7)
        ..lineTo(21, 19.3)
        ..quadraticBezierTo(21, 21, 19.3, 21)
        ..lineTo(15.7, 21)
        ..quadraticBezierTo(14, 21, 14, 19.3)
        ..lineTo(14, 15.7)
        ..quadraticBezierTo(14, 14, 15.7, 14)
        ..close(),
      accent: true,
      filled: true,
      opacity: 0.2,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(15.7, 14)
        ..lineTo(19.3, 14)
        ..quadraticBezierTo(21, 14, 21, 15.7)
        ..lineTo(21, 19.3)
        ..quadraticBezierTo(21, 21, 19.3, 21)
        ..lineTo(15.7, 21)
        ..quadraticBezierTo(14, 21, 14, 19.3)
        ..lineTo(14, 15.7)
        ..quadraticBezierTo(14, 14, 15.7, 14)
        ..close(),
      accent: false,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
  ],
  _Glyph.arrow: [
    _Shape(
      Path()
        ..moveTo(8.5, 5.5)
        ..lineTo(15, 12)
        ..lineTo(8.5, 18.5),
      accent: false,
      filled: false,
      opacity: 1,
      width: 2.1,
    ),
  ],
  _Glyph.external: [
    _Shape(
      Path()
        ..moveTo(10, 4)
        ..lineTo(5, 4)
        ..quadraticBezierTo(3, 4, 3, 6)
        ..lineTo(3, 19)
        ..quadraticBezierTo(3, 21, 5, 21)
        ..lineTo(18, 21)
        ..quadraticBezierTo(20, 21, 20, 19)
        ..lineTo(20, 14),
      accent: false,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(13, 3)
        ..lineTo(21, 3)
        ..lineTo(21, 11)
        ..moveTo(21, 3)
        ..lineTo(10, 14),
      accent: true,
      filled: false,
      opacity: 1,
      width: 2,
    ),
  ],
  _Glyph.accessibility: [
    _Shape(
      Path()
        ..moveTo(14, 4)
        ..cubicTo(14, 5.1046, 13.1046, 6, 12, 6)
        ..cubicTo(10.8954, 6, 10, 5.1046, 10, 4)
        ..cubicTo(10, 2.8954, 10.8954, 2, 12, 2)
        ..cubicTo(13.1046, 2, 14, 2.8954, 14, 4)
        ..close(),
      accent: false,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(3.5, 8.2)
        ..lineTo(12, 10)
        ..lineTo(20.5, 8.2)
        ..moveTo(12, 10)
        ..lineTo(12, 14)
        ..moveTo(12, 14)
        ..lineTo(7.7, 21)
        ..moveTo(12, 14)
        ..lineTo(16.3, 21),
      accent: true,
      filled: false,
      opacity: 1,
      width: 2,
    ),
  ],
  _Glyph.warning: [
    _Shape(
      Path()
        ..moveTo(10.4, 3.8)
        ..cubicTo(11.1, 2.6, 12.9, 2.6, 13.6, 3.8)
        ..lineTo(22, 18.2)
        ..cubicTo(22.7, 19.4, 21.8, 21, 20.4, 21)
        ..lineTo(3.6, 21)
        ..cubicTo(2.2, 21, 1.3, 19.4, 2, 18.2)
        ..close(),
      accent: true,
      filled: true,
      opacity: 0.15,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(10.4, 3.8)
        ..cubicTo(11.1, 2.6, 12.9, 2.6, 13.6, 3.8)
        ..lineTo(22, 18.2)
        ..cubicTo(22.7, 19.4, 21.8, 21, 20.4, 21)
        ..lineTo(3.6, 21)
        ..cubicTo(2.2, 21, 1.3, 19.4, 2, 18.2)
        ..close(),
      accent: false,
      filled: false,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(12, 8)
        ..lineTo(12, 13.4),
      accent: true,
      filled: false,
      opacity: 1,
      width: 2,
    ),
    _Shape(
      Path()
        ..moveTo(13, 17)
        ..cubicTo(13, 17.5523, 12.5523, 18, 12, 18)
        ..cubicTo(11.4477, 18, 11, 17.5523, 11, 17)
        ..cubicTo(11, 16.4477, 11.4477, 16, 12, 16)
        ..cubicTo(12.5523, 16, 13, 16.4477, 13, 17)
        ..close(),
      accent: true,
      filled: true,
      opacity: 1,
      width: 1.8,
    ),
  ],
  _Glyph.more: [
    _Shape(
      Path()
        ..moveTo(6.6, 12)
        ..cubicTo(6.6, 12.8837, 5.8837, 13.6, 5, 13.6)
        ..cubicTo(4.1163, 13.6, 3.4, 12.8837, 3.4, 12)
        ..cubicTo(3.4, 11.1163, 4.1163, 10.4, 5, 10.4)
        ..cubicTo(5.8837, 10.4, 6.6, 11.1163, 6.6, 12)
        ..close(),
      accent: false,
      filled: true,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(13.6, 12)
        ..cubicTo(13.6, 12.8837, 12.8837, 13.6, 12, 13.6)
        ..cubicTo(11.1163, 13.6, 10.4, 12.8837, 10.4, 12)
        ..cubicTo(10.4, 11.1163, 11.1163, 10.4, 12, 10.4)
        ..cubicTo(12.8837, 10.4, 13.6, 11.1163, 13.6, 12)
        ..close(),
      accent: true,
      filled: true,
      opacity: 1,
      width: 1.8,
    ),
    _Shape(
      Path()
        ..moveTo(20.6, 12)
        ..cubicTo(20.6, 12.8837, 19.8837, 13.6, 19, 13.6)
        ..cubicTo(18.1163, 13.6, 17.4, 12.8837, 17.4, 12)
        ..cubicTo(17.4, 11.1163, 18.1163, 10.4, 19, 10.4)
        ..cubicTo(19.8837, 10.4, 20.6, 11.1163, 20.6, 12)
        ..close(),
      accent: false,
      filled: true,
      opacity: 1,
      width: 1.8,
    ),
  ],
};

class _GlyphPainter extends CustomPainter {
  _GlyphPainter(this.glyph, this.color, this.flip);
  final _Glyph glyph;
  final Color color;
  final bool flip;
  @override
  void paint(Canvas canvas, Size size) {
    canvas.save();
    canvas.scale(size.width / 24, size.height / 24);
    if (flip) {
      canvas.translate(24, 0);
      canvas.scale(-1, 1);
    }
    final accent = Color.lerp(color, const Color(0xff45baff), .42)!;
    final paint = Paint()
      ..isAntiAlias = true
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round;
    for (final shape in _art[glyph]!) {
      final tint = shape.accent ? accent : color;
      paint
        ..style = shape.filled ? PaintingStyle.fill : PaintingStyle.stroke
        ..strokeWidth = shape.width
        ..color = tint.withValues(alpha: tint.a * shape.opacity);
      canvas.drawPath(shape.path, paint);
    }
    canvas.restore();
  }

  @override
  bool shouldRepaint(_GlyphPainter old) =>
      old.glyph != glyph || old.color != color || old.flip != flip;
}
