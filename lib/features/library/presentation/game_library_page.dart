import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../application/ports/mapper_gateway.dart';
import '../../../application/controllers/profile_launcher.dart';
import '../../../application/policies/session_readiness.dart';
import '../../../shared/widgets/mobi_icon.dart';
import '../../connection/presentation/connection_indicators.dart';
import '../../connection/presentation/connection_settings_panel.dart';
import '../../connection/presentation/local_activation_sheet.dart';
import 'widgets/app_picker.dart';
import 'widgets/profile_name_dialog.dart';

class GameLibraryPage extends StatefulWidget {
  const GameLibraryPage({super.key, required this.gateway});
  final MapperGateway gateway;

  @override
  State<GameLibraryPage> createState() => _GameLibraryPageState();
}

class _GameLibraryPageState extends State<GameLibraryPage>
    with WidgetsBindingObserver {
  MapperGateway get _api => widget.gateway;
  final _connectionStatus = ValueNotifier<NativeStatus?>(null);
  Future<NativeStatus>? _statusRead;
  NativeStatus? _status;
  List<LaunchableApp> _apps = const [];
  List<LocalProfile> _profiles = const [];
  String? _selectedPackage, _selectedId, _failure;
  Timer? _timer;
  int _launchEpoch = 0;
  bool _foreground = true;
  bool _busy = false, _reading = false, _loadingCatalog = false;

  String get _uiDisplayScope => _status?.uiDisplayScope ?? 'phone';
  List<LocalProfile> _profilesForGame(String? packageName) {
    final all = _profiles
        .where((profile) => profile.packageName == packageName)
        .toList(growable: false);
    final scoped = all
        .where((profile) => profile.displayScope == _uiDisplayScope)
        .toList(growable: false);
    return scoped.isNotEmpty ? scoped : all;
  }

  List<LocalProfile> get _gameProfiles => _profilesForGame(_selectedPackage);

  String get _gameLabel {
    for (final app in _apps) {
      if (app.packageName == _selectedPackage) return app.label;
    }
    return _selectedPackage ?? 'Elegir un juego';
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    unawaited(_refreshAll());
    _startPolling();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground = state == AppLifecycleState.resumed;
    if (!_foreground) _launchEpoch++;
    _timer?.cancel();
    if (_foreground) {
      // The native editor may have saved while Flutter was in the background.
      unawaited(_refreshAll());
      _startPolling();
    }
  }

  void _startPolling() {
    _timer?.cancel();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) => _poll());
  }

  @override
  void dispose() {
    _launchEpoch++;
    _timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    _connectionStatus.dispose();
    super.dispose();
  }

  /// Settings and launch share one pending read; an older poll cannot replace it.
  Future<NativeStatus> _readStatus() {
    final pending = _statusRead;
    if (pending != null) return pending;
    late final Future<NativeStatus> request;
    request = _api
        .status()
        .then((next) {
          if (mounted) {
            setState(() => _status = next);
            _connectionStatus.value = next;
          }
          return next;
        })
        .whenComplete(() {
          if (identical(_statusRead, request)) _statusRead = null;
        });
    _statusRead = request;
    return request;
  }

  Future<void> _poll() async {
    if (_reading || !_foreground) return;
    _reading = true;
    try {
      await _readStatus();
    } catch (error) {
      if (mounted) setState(() => _failure = _errorText(error));
    } finally {
      _reading = false;
    }
  }

  Future<void> _refreshAll({bool reloadApps = false}) async {
    await _poll();
    if (_loadingCatalog) return;
    if (mounted) setState(() => _loadingCatalog = true);
    try {
      final apps = reloadApps || _apps.isEmpty ? await _api.listApps() : _apps;
      final profiles = await _api.listProfiles();
      final selected = await _api.selectedProfile();
      if (!mounted) return;
      setState(() {
        _apps = apps;
        _profiles = profiles;
        if (selected != null) {
          if (_selectedPackage == null || selected.id != _selectedId) {
            _selectedPackage = selected.packageName;
          }
          _selectedId = selected.id;
        } else {
          _selectedId = null;
        }
        _failure = null;
      });
    } catch (error) {
      if (mounted) setState(() => _failure = _errorText(error));
    } finally {
      if (mounted) setState(() => _loadingCatalog = false);
    }
  }

  Future<void> _run(
    Future<void> Function() action, {
    bool profiles = false,
  }) async {
    if (_busy) return;
    var needsOverlaySettings = false;
    setState(() {
      _busy = true;
      _failure = null;
    });
    try {
      await action();
      if (profiles) await _refreshAll();
      await _poll();
    } catch (error) {
      if (!mounted) return;
      if (error is PlatformException &&
          error.code == 'EXTERNAL_OVERLAY_PERMISSION_REQUIRED') {
        needsOverlaySettings = true;
      } else {
        final message = _errorText(error);
        setState(() => _failure = message);
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(message)));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
    if (needsOverlaySettings && mounted) {
      try {
        await _readStatus();
      } catch (_) {
        // The connection panel keeps its normal status retry available.
      }
      if (mounted) _showSettings();
    }
  }

  Future<void> _chooseGame() async {
    final picked = await showModalBottomSheet<LaunchableApp>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (context) => AppPicker(apps: _apps),
    );
    if (!mounted || picked == null) return;
    final available = _profilesForGame(picked.packageName);
    setState(() {
      _selectedPackage = picked.packageName;
      _selectedId = null;
    });
    if (available.isNotEmpty) {
      await _selectProfile(available.first.id);
    } else {
      await _run(() async {
        final created = await _api.createProfile(
          picked.packageName,
          '${picked.label} · principal',
        );
        _selectedId = created.id;
      }, profiles: true);
    }
  }

  Future<void> _selectProfile(String id) => _run(() async {
    final selected = await _api.selectProfile(id);
    if (mounted) {
      setState(() {
        _selectedPackage = selected.packageName;
        _selectedId = selected.id;
      });
    }
  }, profiles: true);

  Future<void> _createProfile() async {
    final packageName = _selectedPackage;
    if (packageName == null) return;
    final name = await _nameDialog('Nuevo perfil', '$_gameLabel · principal');
    if (!mounted || name == null) return;
    await _run(() async {
      final created = await _api.createProfile(packageName, name);
      if (mounted) {
        setState(() {
          _selectedId = created.id;
          _selectedPackage = created.packageName;
        });
      }
    }, profiles: true);
  }

  Future<void> _importProfileFile() => _run(() async {
    await _api.importProfileFile();
  }, profiles: true);

  Future<void> _exportProfileFile(LocalProfile profile) => _run(() async {
    await _api.exportProfileFile(profile.id);
  });

  Future<void> _renameProfile(LocalProfile profile) async {
    final name = await _nameDialog('Nombre del perfil', profile.name);
    if (!mounted || name == null || name == profile.name) return;
    await _run(() async {
      await _api.renameProfile(profile.id, name);
    }, profiles: true);
  }

  Future<void> _deleteProfile(LocalProfile profile) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Eliminar perfil'),
        content: Text('Se eliminará «${profile.name}» de este teléfono.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancelar'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Eliminar'),
          ),
        ],
      ),
    );
    if (!mounted || confirmed != true) return;
    await _run(() => _api.deleteProfile(profile.id), profiles: true);
  }

  Future<String?> _nameDialog(String title, String initial) =>
      showDialog<String>(
        context: context,
        builder: (context) => ProfileNameDialog(title: title, initial: initial),
      );

  String _errorText(Object error) => switch (error) {
    PlatformException() =>
      error.message ?? 'La operación nativa no se completó.',
    FormatException() => error.message,
    _ => error.toString(),
  };

  Widget _command(String label, IconData icon, NativeCommand command) =>
      OutlinedButton.icon(
        onPressed: _busy ? null : () => _run(() => _api.execute(command)),
        icon: MobiIcon(icon, size: 25),
        label: Text(label),
      );

  bool _safeStatus(NativeStatus? status) =>
      SessionReadiness.mayConfigure(status);

  bool get _safe => _safeStatus(_status);

  bool _needsOverlayPermission(NativeStatus status) =>
      SessionReadiness.needsOverlayPermission(status);

  String _labelFor(String packageName) =>
      _apps
          .where((app) => app.packageName == packageName)
          .map((app) => app.label)
          .firstOrNull ??
      packageName;

  Future<void> _launch(LocalProfile profile) async {
    if (!_safe || _busy) return;
    final request = ++_launchEpoch;
    var outcome = ProfileLaunchOutcome.cancelled;
    await _run(() async {
      outcome = await ProfileLauncher(
        gateway: _api,
        readStatus: _readStatus,
        isCurrent: () => _launchCurrent(request),
        reportFailure: (message) {
          if (_launchCurrent(request)) setState(() => _failure = message);
        },
        describeError: _errorText,
      ).launch(profile);
    });
    if (_launchCurrent(request) &&
        outcome == ProfileLaunchOutcome.setupRequired) {
      _showSettings();
    }
  }

  bool _launchCurrent(int request) =>
      mounted && _foreground && request == _launchEpoch;

  Future<void> _editProfile(LocalProfile profile) async {
    var needsSetup = false;
    await _run(() async {
      final current = await _readStatus();
      if (!_safeStatus(current)) return;
      if (_needsOverlayPermission(current)) {
        needsSetup = true;
        return;
      }
      await _api.openEditor(profile.id);
    });
    if (mounted && needsSetup) _showSettings();
  }

  void _showSettings() {
    var checking = false;
    String? checkError = _failure;
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => StatefulBuilder(
        builder: (sheetContext, updateSheet) =>
            ValueListenableBuilder<NativeStatus?>(
              valueListenable: _connectionStatus,
              builder: (_, status, _) => SizedBox(
                height: MediaQuery.sizeOf(context).height * .8,
                child: ListView(
                  padding: const EdgeInsets.fromLTRB(20, 4, 20, 28),
                  children: [
                    _sheetHeading(
                      Icons.link_rounded,
                      'Ajustes de conexión',
                      'Permisos y servicio',
                    ),
                    const SizedBox(height: 16),
                    ConnectionSettingsPanel(
                      status: status,
                      busy: _busy,
                      onActivate: _activateOnPhone,
                      onCommand: (command) => _run(() => _api.execute(command)),
                      checking: checking,
                      error: checkError,
                      onCheck: () async {
                        if (checking) return;
                        updateSheet(() {
                          checking = true;
                          checkError = null;
                        });
                        try {
                          await _readStatus();
                        } catch (error) {
                          if (sheetContext.mounted) {
                            updateSheet(() => checkError = _errorText(error));
                          }
                        } finally {
                          if (sheetContext.mounted) {
                            updateSheet(() => checking = false);
                          }
                        }
                      },
                    ),
                  ],
                ),
              ),
            ),
      ),
    );
  }

  Future<void> _activateOnPhone() async {
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => LocalActivationSheet(gateway: _api),
    );
    if (mounted) await _poll();
  }

  Future<void> _gameOptions(LocalProfile profile) async {
    await _selectProfile(profile.id);
    if (!mounted) return;
    await showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (_) => StatefulBuilder(
        builder: (sheetContext, updateSheet) {
          final profiles = _gameProfiles;
          final selected =
              profiles.where((item) => item.id == _selectedId).firstOrNull ??
              profiles.firstOrNull;
          return SizedBox(
            height: MediaQuery.sizeOf(context).height * .8,
            child: ListView(
              padding: const EdgeInsets.all(20),
              children: [
                _sheetHeading(
                  Icons.sports_esports_rounded,
                  _labelFor(_selectedPackage ?? profile.packageName),
                  selected?.name ?? 'Perfiles',
                ),
                const SizedBox(height: 16),
                _controls(
                  selected,
                  _safe && !_busy && _status?.captureReady == true,
                ),
                const Divider(height: 32),
                _profilePicker(
                  selected,
                  onChanged: () {
                    if (sheetContext.mounted) updateSheet(() {});
                  },
                ),
              ],
            ),
          );
        },
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final status = _status;
    final library = <String, LocalProfile>{};
    int preference(LocalProfile profile) =>
        (profile.displayScope == _uiDisplayScope ? 2 : 0) +
        (profile.id == _selectedId ? 1 : 0);
    for (final p in _profiles) {
      final previous = library[p.packageName];
      if (previous == null || preference(p) > preference(previous)) {
        library[p.packageName] = p;
      }
    }
    return Scaffold(
      appBar: AppBar(
        toolbarHeight: 72,
        backgroundColor: const Color(0xff0875d6),
        surfaceTintColor: Colors.transparent,
        foregroundColor: Colors.white,
        systemOverlayStyle: const SystemUiOverlayStyle(
          statusBarColor: Colors.transparent,
          statusBarIconBrightness: Brightness.light,
          statusBarBrightness: Brightness.dark,
          systemNavigationBarColor: Colors.white,
          systemNavigationBarDividerColor: Colors.white,
          systemNavigationBarIconBrightness: Brightness.dark,
        ),
        flexibleSpace: const SizedBox.expand(
          child: DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [
                  Color(0xff142c59),
                  Color(0xff203e79),
                  Color(0xff2c5095),
                ],
                stops: [0, .62, 1],
              ),
            ),
          ),
        ),
        title: Row(
          children: [
            Container(
              width: 44,
              height: 44,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: Colors.white.withValues(alpha: .16),
                border: Border.all(color: Colors.white.withValues(alpha: .3)),
                shape: BoxShape.circle,
              ),
              child: const MobiIcon(
                MobiIcons.brand,
                size: 30,
                color: Colors.white,
                semanticLabel: 'UwU Mouse',
              ),
            ),
            const SizedBox(width: 10),
            const Expanded(
              child: Text(
                'UwU Mouse',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontWeight: FontWeight.w800,
                  letterSpacing: -.7,
                  fontSize: 21,
                  color: Colors.white,
                ),
              ),
            ),
          ],
        ),
        actions: [
          IconButton(
            onPressed: _showSettings,
            style: IconButton.styleFrom(
              backgroundColor: Colors.white.withValues(alpha: .16),
              foregroundColor: Colors.white,
            ),
            icon: const MobiIcon(
              Icons.tune_rounded,
              size: 27,
              color: Colors.white,
            ),
            tooltip: 'Ajustes',
          ),
          const SizedBox(width: 12),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: () => _refreshAll(reloadApps: true),
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 18, 20, 28),
          children: [
            _connectionSummary(status),
            const SizedBox(height: 18),
            Row(
              children: [
                Expanded(
                  child: Text(
                    'Mis juegos',
                    style: Theme.of(context).textTheme.titleLarge?.copyWith(
                      fontWeight: FontWeight.w800,
                      letterSpacing: -.8,
                    ),
                  ),
                ),
                _pill('${library.length}', Icons.sports_esports_rounded),
              ],
            ),
            const SizedBox(height: 18),
            if (_loadingCatalog || _busy)
              const Padding(
                padding: EdgeInsets.only(bottom: 14),
                child: LinearProgressIndicator(minHeight: 2),
              ),
            if (status?.blocked == true ||
                status?.recovering == true ||
                status?.active == true ||
                (status?.activeProfileId != null && status?.mode != 'stopped'))
              Card(
                child: ListTile(
                  leading: _roundIcon(
                    status!.blocked || status.recovering
                        ? Icons.shield_outlined
                        : Icons.gamepad_rounded,
                  ),
                  title: Text(
                    status.recovering
                        ? 'Recuperando'
                        : status.blocked
                        ? 'Cierre pendiente'
                        : status.active
                        ? (status.pointerMode == 'cursor'
                              ? 'Cursor'
                              : 'En control')
                        : 'En pausa',
                  ),
                  subtitle: status.error.isEmpty
                      ? null
                      : Text(
                          status.error,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                        ),
                  trailing: _command(
                    'Cerrar sesión',
                    Icons.close,
                    NativeCommand.stop,
                  ),
                ),
              ),
            if (_failure != null)
              Padding(
                padding: const EdgeInsets.all(12),
                child: Text(
                  _failure!,
                  style: const TextStyle(color: Color(0xffb94f09)),
                ),
              ),
            LayoutBuilder(
              builder: (context, constraints) {
                const gap = 16.0;
                final columns = ((constraints.maxWidth + gap) / 112)
                    .floor()
                    .clamp(1, 6);
                final width =
                    (constraints.maxWidth - gap * (columns - 1)) / columns;
                final diameter = width.clamp(0.0, 112.0);
                return Wrap(
                  spacing: gap,
                  runSpacing: 24,
                  children: [
                    for (final profile in library.values)
                      SizedBox(
                        width: width,
                        child: _gameTile(profile, diameter),
                      ),
                    SizedBox(width: width, child: _addGameTile(diameter)),
                  ],
                );
              },
            ),
          ],
        ),
      ),
    );
  }

  Widget _roundIcon(IconData icon) => Container(
    width: 44,
    height: 44,
    alignment: Alignment.center,
    decoration: const BoxDecoration(
      color: Color(0xffedf5fc),
      shape: BoxShape.circle,
    ),
    child: MobiIcon(icon, color: const Color(0xff1966ab), size: 27),
  );

  Widget _connectionSummary(NativeStatus? status) {
    Widget stateLine(String label, bool? enabled, {bool feminine = false}) =>
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            connectionIcon(enabled, size: 18),
            const SizedBox(width: 6),
            Expanded(
              child: Text(
                '$label: ${connectionState(enabled, feminine: feminine)}',
                style: TextStyle(
                  color: connectionColor(enabled),
                  fontSize: 13,
                  height: 1.3,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
          ],
        );
    return Container(
      key: const ValueKey('connection-summary'),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          colors: [Color(0xfff0f8ff), Color(0xffe8f4ff)],
        ),
        border: Border.all(color: const Color(0xffbeddf5)),
        borderRadius: BorderRadius.circular(18),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                stateLine(
                  'Captura nativa',
                  status?.captureReady,
                  feminine: true,
                ),
                const SizedBox(height: 8),
                stateLine('Motor de toques', status?.backend),
              ],
            ),
          ),
          const SizedBox(width: 8),
          SizedBox(
            width: 96,
            child: TextButton(
              key: const ValueKey('connection-settings'),
              style: TextButton.styleFrom(
                minimumSize: const Size(44, 48),
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 8),
                textStyle: const TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w700,
                ),
              ),
              onPressed: _showSettings,
              child: const Text(
                'Ajustes de conexión',
                textAlign: TextAlign.center,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _pill(String text, IconData icon) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
    decoration: BoxDecoration(
      color: const Color(0xfff3f7fb),
      borderRadius: BorderRadius.circular(20),
      border: Border.all(color: const Color(0xffaed3f1)),
    ),
    child: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        MobiIcon(icon, size: 24, color: const Color(0xff2677b8)),
        const SizedBox(width: 7),
        Text(
          text,
          style: const TextStyle(
            color: Color(0xff174a7c),
            fontSize: 12,
            fontWeight: FontWeight.w700,
          ),
        ),
      ],
    ),
  );

  Widget _sheetHeading(IconData icon, String title, String subtitle) => Row(
    children: [
      _roundIcon(icon),
      const SizedBox(width: 12),
      Expanded(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              title,
              style: const TextStyle(
                fontSize: 22,
                fontWeight: FontWeight.w800,
                letterSpacing: -.5,
              ),
            ),
            const SizedBox(height: 3),
            Text(
              subtitle,
              style: const TextStyle(color: Color(0xff527394), fontSize: 12),
            ),
          ],
        ),
      ),
    ],
  );

  Widget _libraryCircle(
    double diameter, {
    required Widget child,
    VoidCallback? onTap,
    VoidCallback? onLongPress,
    Key? tapKey,
    bool selected = false,
  }) => SizedBox.square(
    dimension: diameter,
    child: DecoratedBox(
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        boxShadow: [
          BoxShadow(
            color: const Color(0xff274d79).withValues(alpha: .07),
            blurRadius: 16,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: Material(
        color: const Color(0xfff7faff),
        shape: CircleBorder(
          side: BorderSide(
            color: selected ? const Color(0xff69afe5) : const Color(0xffdce6ef),
            width: selected ? 1.5 : 1,
          ),
        ),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          key: tapKey,
          customBorder: const CircleBorder(),
          onTap: onTap,
          onLongPress: onLongPress,
          child: child,
        ),
      ),
    ),
  );

  Widget _tileCaption(
    String title,
    String action,
    IconData icon, {
    bool available = true,
  }) => Column(
    children: [
      const SizedBox(height: 10),
      SizedBox(
        height: MediaQuery.textScalerOf(context).scale(14) * 2.4,
        child: Text(
          title,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          textAlign: TextAlign.center,
          style: const TextStyle(
            fontSize: 14,
            height: 1.2,
            fontWeight: FontWeight.w700,
            color: Color(0xff183650),
          ),
        ),
      ),
      if (action != 'Jugar' && action != 'Mapear')
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            MobiIcon(
              icon,
              size: 18,
              color: available
                  ? const Color(0xff147bc3)
                  : const Color(0xff7b8794),
            ),
            const SizedBox(width: 3),
            Flexible(
              child: Text(
                action,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                  color: available
                      ? const Color(0xff147bc3)
                      : const Color(0xff7b8794),
                ),
              ),
            ),
          ],
        ),
    ],
  );

  Widget _addGameTile(double diameter) => Column(
    mainAxisSize: MainAxisSize.min,
    children: [
      Semantics(
        label: 'Agregar juego',
        button: true,
        child: _libraryCircle(
          diameter,
          onTap: _busy || _loadingCatalog || !_safe ? null : _chooseGame,
          child: const Center(
            child: MobiIcon(
              Icons.add_rounded,
              size: 52,
              color: Color(0xff2589cf),
            ),
          ),
        ),
      ),
      _tileCaption('Agregar juego', 'Seleccionar', Icons.search_rounded),
    ],
  );

  Widget _gameTile(LocalProfile profile, double diameter) {
    final app = _apps
        .where((a) => a.packageName == profile.packageName)
        .firstOrNull;
    final available = app != null;
    final label = _labelFor(profile.packageName);
    final fallback = Center(
      child: MobiIcon(
        Icons.sports_esports_rounded,
        size: diameter * .5,
        color: const Color(0xff3087c7),
      ),
    );
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Semantics(
          label: available
              ? '${profile.calibrated ? 'Jugar' : 'Mapear'} $label'
              : '$label no disponible',
          button: true,
          child: _libraryCircle(
            diameter,
            selected: profile.id == _selectedId,
            tapKey: ValueKey('game-${profile.packageName}'),
            onTap: _safe && !_busy && available ? () => _launch(profile) : null,
            onLongPress: _safe && !_busy ? () => _gameOptions(profile) : null,
            child: ClipOval(
              child: app?.icon != null
                  ? Image.memory(
                      app!.icon!,
                      width: diameter,
                      height: diameter,
                      fit: BoxFit.cover,
                      gaplessPlayback: true,
                      excludeFromSemantics: true,
                      errorBuilder: (_, error, stack) => fallback,
                    )
                  : fallback,
            ),
          ),
        ),
        _tileCaption(
          label,
          !available
              ? 'No disponible'
              : profile.calibrated
              ? 'Jugar'
              : 'Mapear',
          available ? Icons.play_arrow_rounded : Icons.error_outline_rounded,
          available: available,
        ),
      ],
    );
  }

  Widget _profilePicker(LocalProfile? profile, {VoidCallback? onChanged}) {
    Future<void> updateAfter(Future<void> action) async {
      await action;
      onChanged?.call();
    }

    final profiles = _gameProfiles;
    final matching = profiles.where((item) => item.id == profile?.id);
    final chosen = matching.isNotEmpty
        ? matching.first
        : profiles.isNotEmpty
        ? profiles.first
        : null;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        OutlinedButton.icon(
          onPressed: _busy || _loadingCatalog
              ? null
              : () => updateAfter(_chooseGame()),
          icon: const MobiIcon(Icons.sports_esports_outlined, size: 27),
          label: Padding(
            padding: const EdgeInsets.symmetric(vertical: 10),
            child: Text(_gameLabel),
          ),
        ),
        if (_selectedPackage == null)
          const Padding(
            padding: EdgeInsets.only(top: 10),
            child: Text('Elige una app disponible.'),
          )
        else ...[
          const SizedBox(height: 14),
          if (profiles.isNotEmpty)
            DropdownButtonFormField<String>(
              key: ValueKey(
                'profiles-$_selectedPackage-$_uiDisplayScope-$_selectedId',
              ),
              initialValue: chosen?.id,
              isExpanded: true,
              icon: const MobiIcon(Icons.expand_more_rounded, size: 26),
              decoration: const InputDecoration(labelText: 'Perfil'),
              items: profiles
                  .map(
                    (item) => DropdownMenuItem(
                      value: item.id,
                      child: Text(item.name, overflow: TextOverflow.ellipsis),
                    ),
                  )
                  .toList(growable: false),
              onChanged: _busy
                  ? null
                  : (id) {
                      if (id != null) {
                        unawaited(updateAfter(_selectProfile(id)));
                      }
                    },
            )
          else
            const Text('Crea un perfil para empezar.'),
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            runSpacing: 4,
            children: [
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  TextButton.icon(
                    onPressed: _busy
                        ? null
                        : () => updateAfter(_createProfile()),
                    icon: const MobiIcon(Icons.add, size: 26),
                    label: const Text('Nuevo perfil'),
                  ),
                  TextButton.icon(
                    onPressed: _busy
                        ? null
                        : () => updateAfter(_importProfileFile()),
                    icon: const MobiIcon(Icons.file_open_outlined, size: 26),
                    label: const Text('Importar perfil'),
                  ),
                ],
              ),
              if (chosen != null)
                TextButton.icon(
                  onPressed: _busy
                      ? null
                      : () => updateAfter(_exportProfileFile(chosen)),
                  icon: const MobiIcon(Icons.save_alt_rounded, size: 24),
                  label: const Text('Exportar'),
                ),
              if (chosen != null)
                TextButton(
                  onPressed: _busy
                      ? null
                      : () => updateAfter(_renameProfile(chosen)),
                  child: const Text('Renombrar'),
                ),
              if (chosen != null)
                TextButton(
                  onPressed: _busy
                      ? null
                      : () => updateAfter(_deleteProfile(chosen)),
                  child: const Text('Eliminar'),
                ),
            ],
          ),
        ],
        if (_selectedPackage == null)
          TextButton.icon(
            onPressed: _busy ? null : () => updateAfter(_importProfileFile()),
            icon: const MobiIcon(Icons.file_open_outlined, size: 26),
            label: const Text('Importar perfil'),
          ),
      ],
    );
  }

  Widget _controls(LocalProfile? profile, bool canEdit) {
    if (profile == null) {
      return const Text('Elige un perfil para mapear.');
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          profile.calibrated
              ? '${profile.orientationLabel} · '
                    '${profile.screenWidth} × ${profile.screenHeight}'
              : 'Coloca tus controles para empezar.',
          style: TextStyle(
            color: profile.calibrated
                ? const Color(0xff186bb0)
                : const Color(0xff985811),
          ),
        ),
        const SizedBox(height: 12),
        _bindingLine(Icons.open_with, 'Joystick', profile.joystickLabel),
        _bindingLine(
          Icons.mouse_outlined,
          'Cámara',
          '${profile.cameraRateLabel}\n'
              'X ${profile.sensitivityX.toStringAsFixed(2)} · Y ${profile.sensitivityY.toStringAsFixed(2)}',
        ),
        _bindingLine(
          Icons.touch_app_outlined,
          'Acciones',
          '${profile.actionCount} guardadas',
        ),
        const SizedBox(height: 12),
        FilledButton.icon(
          onPressed: canEdit ? () => _editProfile(profile) : null,
          icon: const MobiIcon(Icons.tune, size: 27),
          label: Text(
            profile.calibrated ? 'Editar sobre el juego' : 'Colocar controles',
          ),
        ),
        const SizedBox(height: 10),
        Text('Cámara / cursor · ${keyLabel(profile.cameraToggle)}'),
        const SizedBox(height: 8),
        Text(
          '${keyLabel(profile.cameraToggle)} libera el cursor. La burbuja M abre el mapeo.',
          style: const TextStyle(color: Color(0xff527394), fontSize: 12),
        ),
      ],
    );
  }

  Widget _bindingLine(IconData icon, String title, String value) => Padding(
    padding: const EdgeInsets.only(bottom: 12),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _roundIcon(icon),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title,
                style: const TextStyle(fontSize: 11, color: Color(0xff5d7d9d)),
              ),
              const SizedBox(height: 3),
              Text(
                value,
                style: const TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
        ),
      ],
    ),
  );
}
