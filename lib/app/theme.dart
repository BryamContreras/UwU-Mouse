import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

ThemeData buildAppTheme() => ThemeData(
  brightness: Brightness.light,
  colorScheme: const ColorScheme.light(
    primary: Color(0xff244d96),
    onPrimary: Color(0xffffffff),
    secondary: Color(0xff345fb0),
    surface: Colors.white,
    onSurface: Color(0xff10315e),
    onSurfaceVariant: Color(0xff527394),
    outline: Color(0xffc4d3e3),
    error: Color(0xffad3f35),
  ),
  scaffoldBackgroundColor: Colors.white,
  switchTheme: SwitchThemeData(
    thumbColor: WidgetStateProperty.resolveWith(
      (states) => states.contains(WidgetState.selected)
          ? Colors.white
          : const Color(0xff6b7b91),
    ),
    trackColor: WidgetStateProperty.resolveWith(
      (states) => states.contains(WidgetState.selected)
          ? const Color(0xff244d96)
          : const Color(0xffd6dde7),
    ),
    trackOutlineColor: WidgetStateProperty.resolveWith(
      (states) => states.contains(WidgetState.selected)
          ? const Color(0xff244d96)
          : const Color(0xff8090a8),
    ),
    thumbIcon: WidgetStateProperty.resolveWith(
      (states) => Icon(
        states.contains(WidgetState.selected)
            ? Icons.check_rounded
            : Icons.close_rounded,
        size: 16,
        color: states.contains(WidgetState.selected)
            ? const Color(0xff244d96)
            : Colors.white,
      ),
    ),
  ),
  iconTheme: const IconThemeData(color: Color(0xff1966ab), size: 26),
  appBarTheme: const AppBarTheme(
    systemOverlayStyle: SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: Brightness.dark,
      statusBarBrightness: Brightness.light,
      systemNavigationBarColor: Colors.white,
      systemNavigationBarDividerColor: Colors.white,
      systemNavigationBarIconBrightness: Brightness.dark,
    ),
    backgroundColor: Colors.white,
    surfaceTintColor: Colors.transparent,
    elevation: 0,
    centerTitle: false,
  ),
  bottomSheetTheme: const BottomSheetThemeData(
    backgroundColor: Colors.white,
    surfaceTintColor: Colors.transparent,
    dragHandleColor: Color(0xffb4c4d6),
    shape: RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
    ),
  ),
  dialogTheme: DialogThemeData(
    backgroundColor: Colors.white,
    surfaceTintColor: Colors.transparent,
    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
  ),
  filledButtonTheme: FilledButtonThemeData(
    style: FilledButton.styleFrom(
      minimumSize: const Size(44, 46),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
      textStyle: const TextStyle(fontWeight: FontWeight.w700),
    ),
  ),
  outlinedButtonTheme: OutlinedButtonThemeData(
    style: OutlinedButton.styleFrom(
      minimumSize: const Size(44, 44),
      side: const BorderSide(color: Color(0xff91bfe5)),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
    ),
  ),
  inputDecorationTheme: InputDecorationTheme(
    filled: true,
    fillColor: const Color(0xfff4f7fb),
    contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
    border: OutlineInputBorder(
      borderRadius: BorderRadius.circular(16),
      borderSide: const BorderSide(color: Color(0xffb5d6f0)),
    ),
    enabledBorder: OutlineInputBorder(
      borderRadius: BorderRadius.circular(16),
      borderSide: const BorderSide(color: Color(0xffb5d6f0)),
    ),
    focusedBorder: OutlineInputBorder(
      borderRadius: BorderRadius.circular(16),
      borderSide: const BorderSide(color: Color(0xff0d6ed1), width: 1.5),
    ),
  ),
  dividerTheme: const DividerThemeData(color: Color(0xffc0dcf2)),
  cardTheme: CardThemeData(
    color: Colors.white,
    surfaceTintColor: Colors.transparent,
    elevation: 0,
    margin: const EdgeInsets.only(bottom: 14),
    shape: RoundedRectangleBorder(
      borderRadius: BorderRadius.circular(20),
      side: const BorderSide(color: Color(0xffb8d7ef)),
    ),
  ),
  expansionTileTheme: ExpansionTileThemeData(
    backgroundColor: Colors.white,
    collapsedBackgroundColor: Colors.white,
    iconColor: const Color(0xff186bb0),
    collapsedIconColor: const Color(0xff527ba4),
    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
    collapsedShape: RoundedRectangleBorder(
      borderRadius: BorderRadius.circular(18),
    ),
  ),
  useMaterial3: true,
);
