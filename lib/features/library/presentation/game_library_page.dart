import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../application/ports/mapper_gateway.dart';
import '../../../application/controllers/profile_launcher.dart';
import '../../../application/policies/session_readiness.dart';
import '../../../shared/widgets/uwu_icon.dart';
import '../../connection/presentation/connection_indicators.dart';
import '../../connection/presentation/connection_settings_panel.dart';
import '../../connection/presentation/local_activation_sheet.dart';
import '../../settings/presentation/cursor_settings.dart';
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
  int _statusEpoch = 0;
  bool _foreground = true;
  bool _busy = false, _reading = false, _loadingCatalog = false;
  bool _closeInFlight = false, _closeRequested = false;
  bool _closeReturned = false, _closureConfirmed = false;
  int _tab = 0;
  bool get _working => _busy || _closeInFlight || _closeRequested;

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
    final epoch = _statusEpoch;
    final mayConfirmClosure = _closeReturned;
    late final Future<NativeStatus> request;
    request = _api
        .status()
        .then((next) {
          if (mounted && epoch == _statusEpoch) {
            setState(() {
              _status = next;
              if (mayConfirmClosure && !next.hasSession) {
                _closureConfirmed = true;
                if (!_busy) _closeRequested = false;
              }
            });
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
    final epoch = _statusEpoch;
    _reading = true;
    try {
      await _readStatus();
    } catch (error) {
      if (mounted && epoch == _statusEpoch && !_transientSnapshot(error)) {
        setState(() => _failure = _errorText(error));
      }
    } finally {
      _reading = false;
    }
  }

  bool _transientSnapshot(Object error) =>
      error is PlatformException &&
      (error.code == 'STATUS_STALE' || error.code == 'STATUS_IN_PROGRESS');

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
    if (_busy || _closeRequested || _closeInFlight) return;
    final runEpoch = _launchEpoch;
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
      if (!mounted || runEpoch != _launchEpoch || _transientSnapshot(error)) {
        return;
      }
      if (error is PlatformException &&
          error.code == 'EXTERNAL_OVERLAY_PERMISSION_REQUIRED') {
        needsOverlaySettings = true;
      } else {
        final message = _errorText(error);
        setState(() => _failure = message);
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(message)));
      }
    } finally {
      if (mounted) {
        setState(() {
          _busy = false;
          if (_closureConfirmed) _closeRequested = false;
        });
      }
    }
    if (needsOverlaySettings &&
        mounted &&
        runEpoch == _launchEpoch &&
        !_closeRequested &&
        !_closeInFlight) {
      try {
        await _readStatus();
      } catch (_) {
        // The connection panel keeps its normal status retry available.
      }
      if (mounted) _showSettings();
    }
  }

  /// Closing bypasses ordinary UI work and invalidates launches awaiting a reply.
  Future<void> _requestClose({bool recover = false}) async {
    if (_closeInFlight) return;
    _launchEpoch++;
    final epoch = ++_statusEpoch;
    setState(() {
      _closeInFlight = true;
      _closeRequested = true;
      _closeReturned = false;
      _closureConfirmed = false;
      _failure = null;
    });
    try {
      try {
        if (recover) {
          await _api.recoverPendingSession();
        } else {
          await _api.execute(NativeCommand.stop);
        }
      } finally {
        _closeReturned = true;
      }
    } catch (error) {
      if (mounted) setState(() => _failure = _errorText(error));
    } finally {
      if (mounted) setState(() => _closeInFlight = false);
      unawaited(_refreshClosure(epoch));
    }
  }

  Future<void> _refreshClosure(int epoch) async {
    // A read begun before stop returned cannot prove that this request closed.
    final pending = _statusRead;
    if (pending != null) {
      try {
        await pending;
      } catch (_) {
        // Read a new snapshot even if the superseded poll failed.
      }
    }
    if (!mounted || epoch != _statusEpoch) return;
    try {
      await _readStatus();
    } catch (error) {
      if (mounted && epoch == _statusEpoch && !_transientSnapshot(error)) {
        setState(() => _failure = _errorText(error));
      }
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
          TextButton.icon(
            onPressed: () => Navigator.pop(context, false),
            icon: const UwuIcon(Icons.close_rounded, size: 20),
            label: const Text('Cancelar'),
          ),
          TextButton.icon(
            onPressed: () => Navigator.pop(context, true),
            icon: const UwuIcon(Icons.delete_outline, size: 20),
            label: const Text('Eliminar'),
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

  bool _safeStatus(NativeStatus? status) =>
      !_closeRequested &&
      !_closeInFlight &&
      SessionReadiness.mayConfigure(status);

  bool get _safe => _safeStatus(_status);

  Widget _sessionCard(NativeStatus status) {
    final needsClosure = SessionReadiness.needsClosureRecovery(status);
    return Card(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ListTile(
            leading: _roundIcon(
              needsClosure ? Icons.shield_outlined : Icons.gamepad_rounded,
            ),
            title: Text(
              _closeRequested || status.closing
                  ? 'Cerrando sesión'
                  : status.recovering
                  ? 'Recuperando'
                  : needsClosure
                  ? 'Cierre pendiente'
                  : status.transitioning
                  ? 'Preparando sesión'
                  : status.active
                  ? (status.pointerMode == 'cursor' ? 'Cursor' : 'En control')
                  : 'En pausa',
            ),
            subtitle: status.error.isEmpty
                ? null
                : Text(
                    status.error,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
            child: OutlinedButton.icon(
              key: const ValueKey('close-session'),
              onPressed: _closeInFlight ? null : () => _requestClose(),
              icon: const UwuIcon(Icons.stop_circle_outlined, size: 24),
              label: Text(
                _closeInFlight ? 'Solicitando cierre…' : 'Cerrar sesión',
              ),
            ),
          ),
          if (needsClosure)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Text(
                    'Si Android mantiene toques pendientes, vuelve al juego anterior para verificar su liberación.',
                  ),
                  const SizedBox(height: 12),
                  FilledButton.icon(
                    key: const ValueKey('recover-pending-session'),
                    onPressed: _closeInFlight
                        ? null
                        : () => _requestClose(recover: true),
                    icon: const UwuIcon(Icons.close_rounded, size: 25),
                    label: const Text('Cerrar sesión anterior'),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }

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
      mounted &&
      _foreground &&
      !_closeRequested &&
      !_closeInFlight &&
      request == _launchEpoch;

  Future<void> _editProfile(LocalProfile profile) async {
    final request = ++_launchEpoch;
    var needsSetup = false;
    await _run(() async {
      final current = await _readStatus();
      if (!_launchCurrent(request) || !_safeStatus(current)) return;
      if (_needsOverlayPermission(current)) {
        needsSetup = true;
        return;
      }
      await _api.openEditor(profile.id);
    });
    if (_launchCurrent(request) && needsSetup) _showSettings();
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
                      busy: _working,
                      onActivate: _activateOnPhone,
                      onWirelessDebugging: () =>
                          _run(_api.wirelessDebuggingSettings),
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
          systemNavigationBarColor: Color(0xff203e79),
          systemNavigationBarDividerColor: Color(0xff203e79),
          systemNavigationBarIconBrightness: Brightness.light,
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
              child: const UwuIcon(
                UwuIcons.brand,
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
      ),
      bottomNavigationBar: DecoratedBox(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [Color(0xff142c59), Color(0xff203e79), Color(0xff2c5095)],
            stops: [0, .62, 1],
          ),
        ),
        child: NavigationBarTheme(
          data: NavigationBarThemeData(
            iconTheme: WidgetStateProperty.resolveWith(
              (states) => IconThemeData(
                color: states.contains(WidgetState.selected)
                    ? Colors.white
                    : Colors.white70,
              ),
            ),
            labelTextStyle: WidgetStateProperty.all(
              const TextStyle(
                color: Colors.white,
                fontWeight: FontWeight.w600,
                fontSize: 12,
              ),
            ),
          ),
          child: NavigationBar(
            backgroundColor: Colors.transparent,
            surfaceTintColor: Colors.transparent,
            indicatorColor: Colors.white.withValues(alpha: .18),
            selectedIndex: _tab,
            onDestinationSelected: (index) => setState(() => _tab = index),
            destinations: const [
              NavigationDestination(
                icon: Icon(Icons.home_outlined),
                selectedIcon: Icon(Icons.home_rounded),
                label: 'Inicio',
              ),
              NavigationDestination(
                icon: Icon(Icons.settings_outlined),
                selectedIcon: Icon(Icons.settings_rounded),
                label: 'Ajustes',
              ),
            ],
          ),
        ),
      ),
      body: _tab == 1
          ? CursorSettings(gateway: _api, onConnection: _showSettings)
          : RefreshIndicator(
              onRefresh: () => _refreshAll(reloadApps: true),
              child: ListView(
                padding: const EdgeInsets.fromLTRB(20, 18, 20, 28),
                children: [
                  if (status != null &&
                      (status.hasSession ||
                          _closeRequested ||
                          _closeInFlight)) ...[
                    _sessionCard(status),
                    const SizedBox(height: 14),
                  ],
                  _connectionSummary(status),
                  const SizedBox(height: 18),
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          'Mis juegos',
                          style: Theme.of(context).textTheme.titleLarge
                              ?.copyWith(
                                fontWeight: FontWeight.w800,
                                letterSpacing: -.8,
                              ),
                        ),
                      ),
                      _pill('${library.length}', Icons.sports_esports_rounded),
                    ],
                  ),
                  const SizedBox(height: 18),
                  if (_loadingCatalog || _busy || _closeInFlight)
                    const Padding(
                      padding: EdgeInsets.only(bottom: 14),
                      child: LinearProgressIndicator(minHeight: 2),
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
                          (constraints.maxWidth - gap * (columns - 1)) /
                          columns;
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
    child: UwuIcon(icon, color: const Color(0xff1966ab), size: 27),
  );

  Widget _connectionSummary(NativeStatus? status) {
    final decoration = BoxDecoration(
      gradient: const LinearGradient(
        colors: [Color(0xfff0f8ff), Color(0xffe8f4ff)],
      ),
      border: Border.all(color: const Color(0xffbeddf5)),
      borderRadius: BorderRadius.circular(18),
    );
    const padding = EdgeInsets.symmetric(horizontal: 8, vertical: 2);
    return LayoutBuilder(
      builder: (context, constraints) {
        // Keep the same breakpoint as the former shared card's inner width.
        final wide = constraints.maxWidth >= 616;
        Widget option(Widget child, Key key) => wide
            ? Expanded(
                child: Container(
                  key: key,
                  padding: padding,
                  decoration: decoration,
                  child: child,
                ),
              )
            : child;
        return Container(
          key: const ValueKey('connection-summary'),
          padding: wide ? EdgeInsets.zero : padding,
          decoration: wide ? null : decoration,
          child: Flex(
            direction: wide ? Axis.horizontal : Axis.vertical,
            crossAxisAlignment: wide
                ? CrossAxisAlignment.start
                : CrossAxisAlignment.stretch,
            children: [
              option(
                ListTile(
                  key: const ValueKey('wireless-debugging-settings'),
                  contentPadding: const EdgeInsets.symmetric(horizontal: 8),
                  minTileHeight: 56,
                  minVerticalPadding: 4,
                  visualDensity: VisualDensity.compact,
                  minLeadingWidth: 28,
                  horizontalTitleGap: 8,
                  titleTextStyle: const TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w700,
                    height: 1.15,
                    color: Color(0xff10315e),
                  ),
                  subtitleTextStyle: const TextStyle(
                    fontSize: 12,
                    height: 1.2,
                    color: Color(0xff527394),
                  ),
                  leading: const Icon(
                    Icons.wifi_rounded,
                    color: Color(0xff1966ab),
                    size: 28,
                  ),
                  title: const Text('Depuración inalámbrica'),
                  subtitle: Text(switch (status?.wirelessDebuggingEnabled) {
                    true => 'Activada',
                    false => 'Desactivada',
                    null => 'Configurar en Android',
                  }),
                  trailing: connectionIcon(
                    status?.wirelessDebuggingEnabled,
                    size: 24,
                  ),
                  onTap: _working
                      ? null
                      : () => _run(_api.wirelessDebuggingSettings),
                ),
                const ValueKey('wireless-debugging-card'),
              ),
              if (wide)
                const SizedBox(width: 12)
              else
                const Divider(height: 1, indent: 8, endIndent: 8),
              option(
                ListTile(
                  key: const ValueKey('overlay-settings'),
                  contentPadding: const EdgeInsets.symmetric(horizontal: 8),
                  minTileHeight: 56,
                  minVerticalPadding: 4,
                  visualDensity: VisualDensity.compact,
                  minLeadingWidth: 28,
                  horizontalTitleGap: 8,
                  titleTextStyle: const TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w700,
                    height: 1.15,
                    color: Color(0xff10315e),
                  ),
                  subtitleTextStyle: const TextStyle(
                    fontSize: 12,
                    height: 1.2,
                    color: Color(0xff527394),
                  ),
                  leading: const Icon(
                    Icons.layers_outlined,
                    color: Color(0xff1966ab),
                    size: 28,
                  ),
                  title: const Text('Mostrar sobre otras apps'),
                  subtitle: Text(switch (status?.overlayPermission) {
                    true => 'Permitida',
                    false => 'Necesita permiso',
                    null => 'Comprobar permiso en Android',
                  }),
                  trailing: connectionIcon(status?.overlayPermission, size: 24),
                  onTap: _working
                      ? null
                      : () => _run(
                          () => _api.execute(NativeCommand.openOverlaySettings),
                        ),
                ),
                const ValueKey('overlay-card'),
              ),
            ],
          ),
        );
      },
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
        UwuIcon(icon, size: 24, color: const Color(0xff2677b8)),
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
            UwuIcon(
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
          onTap: _working || _loadingCatalog || !_safe ? null : _chooseGame,
          child: const Center(
            child: UwuIcon(
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
      child: UwuIcon(
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
          onPressed: _working || _loadingCatalog
              ? null
              : () => updateAfter(_chooseGame()),
          icon: const UwuIcon(Icons.sports_esports_outlined, size: 27),
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
              icon: const UwuIcon(Icons.expand_more_rounded, size: 26),
              decoration: const InputDecoration(
                labelText: 'Perfil',
                prefixIcon: UwuIcon(Icons.person_outline, size: 23),
              ),
              items: profiles
                  .map(
                    (item) => DropdownMenuItem(
                      value: item.id,
                      child: Text(item.name, overflow: TextOverflow.ellipsis),
                    ),
                  )
                  .toList(growable: false),
              onChanged: _working
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
          Row(
            children: [
              Expanded(
                child: TextButton.icon(
                  onPressed: _working
                      ? null
                      : () => updateAfter(_createProfile()),
                  icon: const UwuIcon(Icons.add, size: 26),
                  label: const FittedBox(child: Text('Nuevo perfil')),
                ),
              ),
              Expanded(
                child: TextButton.icon(
                  onPressed: _working
                      ? null
                      : () => updateAfter(_importProfileFile()),
                  icon: const UwuIcon(Icons.file_open_outlined, size: 26),
                  label: const FittedBox(child: Text('Importar perfil')),
                ),
              ),
            ],
          ),
          Wrap(
            spacing: 8,
            runSpacing: 4,
            children: [
              if (chosen != null)
                TextButton.icon(
                  onPressed: _working
                      ? null
                      : () => updateAfter(_exportProfileFile(chosen)),
                  icon: const UwuIcon(Icons.save_alt_rounded, size: 24),
                  label: const Text('Exportar'),
                ),
              if (chosen != null)
                TextButton.icon(
                  onPressed: _working
                      ? null
                      : () => updateAfter(_renameProfile(chosen)),
                  icon: const UwuIcon(Icons.edit_outlined, size: 22),
                  label: const Text('Renombrar'),
                ),
              if (chosen != null)
                TextButton.icon(
                  onPressed: _working
                      ? null
                      : () => updateAfter(_deleteProfile(chosen)),
                  icon: const UwuIcon(Icons.delete_outline, size: 22),
                  label: const Text('Eliminar'),
                ),
            ],
          ),
        ],
        if (_selectedPackage == null)
          TextButton.icon(
            onPressed: _working
                ? null
                : () => updateAfter(_importProfileFile()),
            icon: const UwuIcon(Icons.file_open_outlined, size: 26),
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
          icon: const UwuIcon(Icons.tune, size: 27),
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
