import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'native_api.dart';
import 'app_icons.dart';

class GameSessionPage extends StatefulWidget {
  const GameSessionPage({super.key});

  @override
  State<GameSessionPage> createState() => _GameSessionPageState();
}

class _GameSessionPageState extends State<GameSessionPage>
    with WidgetsBindingObserver {
  final _api = const NativeApi();
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

  List<LocalProfile> get _gameProfiles => _profiles
      .where((profile) => profile.packageName == _selectedPackage)
      .toList(growable: false);

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
      final message = _errorText(error);
      setState(() => _failure = message);
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(message)));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _chooseGame() async {
    final picked = await showModalBottomSheet<LaunchableApp>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (context) => _AppPicker(apps: _apps),
    );
    if (!mounted || picked == null) return;
    final available = _profiles
        .where((profile) => profile.packageName == picked.packageName)
        .toList(growable: false);
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
        builder: (context) =>
            _ProfileNameDialog(title: title, initial: initial),
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
      status != null &&
      !status.blocked &&
      !status.recovering &&
      !status.editing &&
      status.contacts == 0;

  bool get _safe => _safeStatus(_status);

  String _labelFor(String packageName) =>
      _apps
          .where((app) => app.packageName == packageName)
          .map((app) => app.label)
          .firstOrNull ??
      packageName;

  Future<void> _launch(LocalProfile profile) async {
    if (!_safe || _busy) return;
    final request = ++_launchEpoch;
    var needsSetup = false;
    await _run(() async {
      var current = await _readStatus();
      // requestAttach may still be recovering the Binder on a cold app launch.
      for (
        var attempt = 0;
        attempt < 2 &&
            current.accessibility &&
            !current.backend &&
            _safeStatus(current);
        attempt++
      ) {
        await Future<void>.delayed(const Duration(milliseconds: 250));
        if (!_launchCurrent(request)) return;
        current = await _readStatus();
      }
      if (!_launchCurrent(request) || !_safeStatus(current)) return;
      if (current.accessibility && !current.backend) {
        final reactivated = await _reactivateForLaunch(request);
        if (!_launchCurrent(request)) return;
        if (reactivated != null) current = reactivated;
      }
      if (!current.accessibility || !current.backend) {
        needsSetup = true;
        return;
      }
      if (profile.calibrated) {
        await _api.startProfile(profile.id);
      } else {
        await _api.openEditor(profile.id);
      }
    });
    if (_launchCurrent(request) && needsSetup) _showSettings();
  }

  bool _launchCurrent(int request) =>
      mounted && _foreground && request == _launchEpoch;

  /// An explicit game tap may reuse an existing local ADB authorization.
  /// Backgrounding invalidates the tap, even if the user returns before ready.
  Future<NativeStatus?> _reactivateForLaunch(int request) async {
    var ownsDiscovery = false;
    var preserveOtherOperation = false;
    var expired = false;
    Timer? timeout;
    try {
      final previous = await _api.activationStatus();
      if (!_launchCurrent(request) || !previous.paired || previous.busy) {
        return null;
      }
      timeout = Timer(const Duration(seconds: 20), () => expired = true);
      await _api.activationStartDiscovery();
      ownsDiscovery = true;
      if (!_launchCurrent(request)) return null;
      final discovered = await _api.activationStatus();
      if (discovered.busy) {
        preserveOtherOperation = true;
        return null;
      }
      await _api.activationConnect();
      while (_launchCurrent(request) && !expired) {
        final activation = await _api.activationStatus();
        if (!_launchCurrent(request) || expired) return null;
        if (activation.phase == 'pairing') {
          preserveOtherOperation = true;
          return null;
        }
        if (activation.phase == 'error' || activation.phase == 'idle') {
          if (activation.message.isNotEmpty) {
            setState(() => _failure = activation.message);
          }
          return null;
        }
        if (activation.ready) {
          final verified = await _readStatus();
          if (_launchCurrent(request) &&
              verified.backend &&
              verified.accessibility &&
              _safeStatus(verified)) {
            return verified;
          }
          return null;
        }
        await Future<void>.delayed(const Duration(milliseconds: 250));
      }
      if (expired && _launchCurrent(request)) {
        setState(
          () => _failure =
              'No se pudo reactivar a tiempo. Revisa Depuración inalámbrica.',
        );
      }
    } catch (error) {
      if (_launchCurrent(request)) {
        setState(() => _failure = _errorText(error));
      }
    } finally {
      timeout?.cancel();
      if (ownsDiscovery && !preserveOtherOperation) {
        // A notification may have begun pairing after the initial status read.
        // Its authorization belongs to that action and must remain untouched.
        try {
          final latest = await _api.activationStatus();
          if (latest.phase != 'pairing') await _api.activationCancel();
        } catch (_) {
          // Keep the original failure; never start a game after a failed check.
        }
      }
    }
    return null;
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
                      Icons.tune_rounded,
                      'Ajustes',
                      'Conexión y controles',
                    ),
                    const SizedBox(height: 16),
                    _connectionDetails(
                      status,
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
                    const SizedBox(height: 10),
                    _laboratory(status),
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
      builder: (_) => const _LocalActivationSheet(),
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
      builder: (_) => SizedBox(
        height: MediaQuery.sizeOf(context).height * .8,
        child: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            _sheetHeading(
              Icons.sports_esports_rounded,
              _labelFor(profile.packageName),
              profile.name,
            ),
            const SizedBox(height: 16),
            _controls(
              profile,
              _safe && !_busy && _status?.accessibility == true,
            ),
            const Divider(height: 32),
            _profilePicker(profile),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final status = _status;
    final library = <String, LocalProfile>{};
    for (final p in _profiles) {
      if (!library.containsKey(p.packageName) || p.id == _selectedId) {
        library[p.packageName] = p;
      }
    }
    final needsSetup = status?.backend != true || status?.accessibility != true;
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
                  Color(0xff0875d6),
                  Color(0xff1685d8),
                  Color(0xff2587d5),
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
            const Text(
              'UwU Mouse',
              style: TextStyle(
                fontWeight: FontWeight.w800,
                letterSpacing: -.7,
                fontSize: 21,
                color: Colors.white,
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
            if (needsSetup && status != null)
              Card(
                child: ListTile(
                  leading: _roundIcon(Icons.cable_rounded),
                  title: const Text('Preparar conexión'),
                  subtitle: Text(
                    status.accessibility
                        ? 'Activa el motor en este teléfono.'
                        : 'Activa UwU Mouse · entrada local.',
                  ),
                  trailing: const MobiIcon(Icons.chevron_right),
                  onTap: _showSettings,
                ),
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
      const SizedBox(height: 3),
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

  Widget _profilePicker(LocalProfile? profile) {
    final profiles = _gameProfiles;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        OutlinedButton.icon(
          onPressed: _busy || _loadingCatalog ? null : _chooseGame,
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
              key: ValueKey('profiles-$_selectedPackage-$_selectedId'),
              initialValue: profile?.id,
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
                      if (id != null) unawaited(_selectProfile(id));
                    },
            )
          else
            const Text('Crea un perfil para empezar.'),
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            runSpacing: 4,
            children: [
              TextButton.icon(
                onPressed: _busy ? null : _createProfile,
                icon: const MobiIcon(Icons.add, size: 26),
                label: const Text('Nuevo perfil'),
              ),
              if (profile != null)
                TextButton(
                  onPressed: _busy ? null : () => _renameProfile(profile),
                  child: const Text('Renombrar'),
                ),
              if (profile != null)
                TextButton(
                  onPressed: _busy ? null : () => _deleteProfile(profile),
                  child: const Text('Eliminar'),
                ),
            ],
          ),
        ],
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
          '${profile.cameraModeLabel} · ${profile.cameraRateLabel}\n'
              'X ${profile.sensitivityX.toStringAsFixed(2)} · Y ${profile.sensitivityY.toStringAsFixed(2)}',
        ),
        _bindingLine(
          Icons.touch_app_outlined,
          'Acciones',
          '${profile.actionCount} guardadas',
        ),
        const SizedBox(height: 12),
        FilledButton.icon(
          onPressed: canEdit
              ? () => _run(() => _api.openEditor(profile.id))
              : null,
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

  Widget _connectionDetails(
    NativeStatus? status, {
    required VoidCallback onCheck,
    bool checking = false,
    String? error,
  }) => ExpansionTile(
    leading: const MobiIcon(Icons.link_rounded, size: 27),
    trailing: const MobiIcon(Icons.expand_more_rounded, size: 26),
    title: const Text('Conexión'),
    initiallyExpanded: true,
    childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
    expandedCrossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(
        'Accesibilidad: ${status == null
            ? 'comprobando…'
            : status.accessibility
            ? 'activa'
            : 'inactiva'}',
      ),
      const SizedBox(height: 8),
      if (status != null && !status.accessibility)
        _command(
          'Abrir ajustes de accesibilidad',
          Icons.accessibility_new,
          NativeCommand.accessibilitySettings,
        ),
      const SizedBox(height: 8),
      Text(
        'Motor de toques: ${status == null
            ? 'comprobando…'
            : status.backend
            ? 'activo'
            : 'apagado'}',
      ),
      if (status != null && !status.backend) ...[
        const SizedBox(height: 8),
        const Text(
          'Activa el motor con Depuración inalámbrica. '
          'El emparejamiento se realiza en este teléfono.',
        ),
        const SizedBox(height: 8),
        FilledButton.icon(
          onPressed: !_safeStatus(status) || status.active
              ? null
              : _activateOnPhone,
          icon: const MobiIcon(Icons.cable_rounded, size: 25),
          label: const Text('Activar en este teléfono'),
        ),
      ],
      const SizedBox(height: 12),
      OutlinedButton.icon(
        onPressed: checking ? null : onCheck,
        icon: const MobiIcon(Icons.refresh_rounded, size: 25),
        label: Text(checking ? 'Comprobando…' : 'Comprobar servicio'),
      ),
      if (status?.accessibility == true &&
          status?.backend == true &&
          _safeStatus(status) &&
          !status!.active)
        const Text('Listo para abrir tu juego'),
      if (error != null) Text(error),
      if (status?.backendError.isNotEmpty == true) Text(status!.backendError),
      const SizedBox(height: 12),
      Text(
        status == null || status.devices.isEmpty
            ? 'Conecta teclado y ratón por OTG o Bluetooth.'
            : 'Dispositivos\n${status.devices.join('\n')}',
      ),
    ],
  );

  Widget _laboratory(NativeStatus? status) {
    String timing(List<double>? data) => data == null || data.isEmpty
        ? 'Sin muestras'
        : data.map((value) => '${value.toStringAsFixed(3)} ms').join(' / ');
    return ExpansionTile(
      leading: const MobiIcon(Icons.science_outlined, size: 27),
      trailing: const MobiIcon(Icons.expand_more_rounded, size: 26),
      title: const Text('Laboratorio y diagnóstico'),
      childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      expandedCrossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text('InputLab · pruebas del mapeador.'),
        const SizedBox(height: 10),
        _command(
          'Iniciar en laboratorio',
          Icons.play_arrow,
          NativeCommand.startLab,
        ),
        _command(
          'Probar inyección sintética',
          Icons.science_outlined,
          NativeCommand.probe,
        ),
        _command(
          'Abrir receptor sin mapear',
          Icons.open_in_new,
          NativeCommand.openLab,
        ),
        _command(
          'Detener y liberar entradas',
          Icons.stop_circle_outlined,
          NativeCommand.stop,
        ),
        Text(status?.probe ?? 'Sin ejecutar'),
        const SizedBox(height: 14),
        Text(
          'Teclas: ${status?.keys ?? 0} · muestras ratón: ${status?.mouse ?? 0}\n'
          'Deltas relativos: ${status?.relativeSamples ?? 0}\n'
          'Inyecciones admitidas: ${status?.injected ?? 0}\n'
          'Profundidad máxima: ${status?.maxQueueDepth ?? 0} · '
          'combinados: ${status?.mergedMoves ?? 0}',
        ),
        const SizedBox(height: 10),
        Text(
          'p50 / p95 / p99\nCola: ${timing(status?.queueMs)}\n'
          'IPC + admisión: ${timing(status?.ipcMs)}',
        ),
        const SizedBox(height: 8),
        const Text('Tiempos internos; no incluyen la imagen.'),
        const SizedBox(height: 10),
        _command(
          'Cerrar backend ADB',
          Icons.power_settings_new,
          NativeCommand.shutdownBackend,
        ),
      ],
    );
  }
}

class _LocalActivationSheet extends StatefulWidget {
  const _LocalActivationSheet();

  @override
  State<_LocalActivationSheet> createState() => _LocalActivationSheetState();
}

class _LocalActivationSheetState extends State<_LocalActivationSheet>
    with WidgetsBindingObserver {
  final _api = const NativeApi();
  final _code = TextEditingController();
  final _pairingPort = TextEditingController();
  final _connectPort = TextEditingController();
  LocalActivationStatus? _status;
  Timer? _timer;
  String? _error;
  bool _reading = false, _sending = false, _foreground = true;
  bool _pairingPortEdited = false, _connectPortEdited = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    unawaited(_perform(_api.activationStartDiscovery));
    _timer = Timer.periodic(const Duration(seconds: 1), (_) => _poll());
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground = state == AppLifecycleState.resumed;
    if (_foreground) unawaited(_poll());
  }

  @override
  void dispose() {
    _timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    _code.dispose();
    _pairingPort.dispose();
    _connectPort.dispose();
    // Stops discovery and pending activation work; an activated backend stays up.
    unawaited(_api.activationCancel().catchError((Object _) {}));
    super.dispose();
  }

  String _message(Object error) => error is PlatformException
      ? error.message ?? 'No se pudo activar el motor.'
      : error is FormatException
      ? error.message
      : 'No se pudo comprobar la activación.';

  Future<void> _poll() async {
    if (_reading || !_foreground || !mounted) return;
    _reading = true;
    try {
      final next = await _api.activationStatus();
      if (!mounted) return;
      if (!_pairingPortEdited && next.pairingPort != null) {
        _pairingPort.text = '${next.pairingPort}';
      }
      if (!_connectPortEdited && next.connectPort != null) {
        _connectPort.text = '${next.connectPort}';
      }
      setState(() => _status = next);
    } catch (error) {
      if (mounted) setState(() => _error = _message(error));
    } finally {
      _reading = false;
    }
  }

  Future<void> _perform(Future<void> Function() operation) async {
    if (_sending) return;
    setState(() {
      _sending = true;
      _error = null;
    });
    try {
      await operation();
      if (mounted) await _poll();
    } catch (error) {
      if (mounted) setState(() => _error = _message(error));
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  int? _port(TextEditingController controller) {
    final value = int.tryParse(controller.text.trim());
    return value != null && value > 0 && value <= 65535 ? value : null;
  }

  Future<void> _pair() async {
    final code = _code.text.trim();
    final port = _port(_pairingPort);
    if (!RegExp(r'^\d{6}$').hasMatch(code)) {
      setState(() => _error = 'Escribe el código de 6 dígitos de Android.');
      return;
    }
    if (port == null) {
      setState(() => _error = 'Escribe el puerto de emparejamiento.');
      return;
    }
    await _perform(() async {
      await _api.activationPair(
        host: _status?.host ?? '127.0.0.1',
        port: port,
        code: code,
      );
      if (mounted) _code.clear();
    });
  }

  Future<void> _connect() async {
    final rawPort = _connectPort.text.trim();
    final port = _port(_connectPort);
    if (rawPort.isNotEmpty && port == null) {
      setState(
        () => _error = 'El puerto de conexión debe estar entre 1 y 65535.',
      );
      return;
    }
    await _perform(
      () => _api.activationConnect(host: _status?.host, port: port),
    );
  }

  @override
  Widget build(BuildContext context) {
    final status = _status;
    final busy = _sending || status?.busy == true;
    return SafeArea(
      top: false,
      child: SizedBox(
        height: MediaQuery.sizeOf(context).height * .85,
        child: ListView(
          key: const ValueKey('activation-list'),
          padding: EdgeInsets.fromLTRB(
            20,
            4,
            20,
            24 + MediaQuery.viewInsetsOf(context).bottom,
          ),
          children: [
            Row(
              children: [
                const Expanded(
                  child: Text(
                    'Activar en este teléfono',
                    style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800),
                  ),
                ),
                IconButton(
                  tooltip: 'Cerrar activación',
                  onPressed: () => Navigator.pop(context),
                  icon: const MobiIcon(Icons.close_rounded, size: 26),
                ),
              ],
            ),
            const SizedBox(height: 12),
            const Text(
              '1. Activa Depuración inalámbrica.\n'
              '2. Abre «Emparejar dispositivo con código».\n'
              '3. Escribe sus 6 dígitos en la notificación UwU Mouse.',
            ),
            const SizedBox(height: 8),
            const Text(
              'También puedes usar pantalla dividida y este formulario. '
              'Mantén abierta la ventana del código de Android.',
              style: TextStyle(fontSize: 12, color: Color(0xff527394)),
            ),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: busy
                  ? null
                  : () => _perform(_api.wirelessDebuggingSettings),
              icon: const MobiIcon(Icons.settings_outlined, size: 25),
              label: const Text('Abrir Depuración inalámbrica'),
            ),
            const SizedBox(height: 16),
            if (busy) ...[
              const LinearProgressIndicator(minHeight: 2),
              const SizedBox(height: 10),
            ],
            Text(
              status?.message.isNotEmpty == true
                  ? status!.message
                  : 'Buscando el servicio local…',
              key: const ValueKey('activation-message'),
              style: TextStyle(
                fontWeight: FontWeight.w600,
                color: status?.ready == true
                    ? const Color(0xff23714e)
                    : const Color(0xff1966ab),
              ),
            ),
            if (_error != null) ...[
              const SizedBox(height: 8),
              Text(_error!, style: const TextStyle(color: Color(0xffb94f09))),
            ],
            if (status?.ready != true) ...[
              const SizedBox(height: 16),
              TextField(
                key: const ValueKey('activation-code'),
                controller: _code,
                enabled: !busy,
                keyboardType: TextInputType.number,
                inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                maxLength: 6,
                autofillHints: const [],
                enableSuggestions: false,
                autocorrect: false,
                decoration: const InputDecoration(
                  labelText: 'Código de 6 dígitos',
                ),
              ),
              const SizedBox(height: 8),
              TextField(
                key: const ValueKey('activation-pairing-port'),
                controller: _pairingPort,
                enabled: !busy,
                keyboardType: TextInputType.number,
                inputFormatters: [
                  FilteringTextInputFormatter.digitsOnly,
                  LengthLimitingTextInputFormatter(5),
                ],
                onChanged: (_) => _pairingPortEdited = true,
                decoration: const InputDecoration(
                  labelText: 'Puerto de emparejamiento',
                  helperText: 'Se detecta solo; si falta, copia el de la ventana del código.',
                  helperMaxLines: 2,
                ),
              ),
              const SizedBox(height: 12),
              FilledButton(
                key: const ValueKey('activation-pair'),
                onPressed: busy ? null : _pair,
                child: const Text('Emparejar y activar'),
              ),
              if (status?.paired == true) ...[
                const SizedBox(height: 8),
                OutlinedButton(
                  key: const ValueKey('activation-connect'),
                  onPressed: busy ? null : _connect,
                  child: const Text('Reactivar sin código'),
                ),
              ],
              const SizedBox(height: 8),
              ExpansionTile(
                title: const Text('Si no detecta el servicio'),
                tilePadding: EdgeInsets.zero,
                children: [
                  TextField(
                    key: const ValueKey('activation-connect-port'),
                    controller: _connectPort,
                    enabled: !busy,
                    keyboardType: TextInputType.number,
                    inputFormatters: [
                      FilteringTextInputFormatter.digitsOnly,
                      LengthLimitingTextInputFormatter(5),
                    ],
                    onChanged: (_) => _connectPortEdited = true,
                    decoration: const InputDecoration(
                      labelText: 'Puerto de conexión',
                      helperText:
                          'En la pantalla principal de Depuración inalámbrica.',
                      helperMaxLines: 2,
                    ),
                  ),
                  const SizedBox(height: 10),
                  TextButton.icon(
                    onPressed: busy
                        ? null
                        : () => _perform(_api.activationStartDiscovery),
                    icon: const MobiIcon(Icons.refresh_rounded, size: 23),
                    label: const Text('Buscar de nuevo'),
                  ),
                  const Text(
                    'También puedes usar Activar-UwU-Mouse.cmd desde una PC.',
                    style: TextStyle(fontSize: 12, color: Color(0xff527394)),
                  ),
                ],
              ),
            ] else ...[
              const SizedBox(height: 16),
              const Text(
                'Motor activo. Vuelve a tu biblioteca y toca el juego.',
              ),
              const SizedBox(height: 12),
              FilledButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('Listo'),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _ProfileNameDialog extends StatefulWidget {
  const _ProfileNameDialog({required this.title, required this.initial});
  final String title, initial;

  @override
  State<_ProfileNameDialog> createState() => _ProfileNameDialogState();
}

class _ProfileNameDialogState extends State<_ProfileNameDialog> {
  late final TextEditingController _controller;

  @override
  void initState() {
    super.initState();
    final initial = widget.initial;
    _controller = TextEditingController(
      text: initial.length > 60 ? initial.substring(0, 60) : initial,
    );
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _save() {
    final value = _controller.text.trim();
    if (value.isNotEmpty && value.length <= 60) Navigator.pop(context, value);
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text(widget.title),
    content: TextField(
      controller: _controller,
      autofocus: true,
      maxLength: 60,
      textCapitalization: TextCapitalization.sentences,
      decoration: const InputDecoration(labelText: 'Nombre'),
      onChanged: (_) => setState(() {}),
      onSubmitted: (_) => _save(),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('Cancelar'),
      ),
      FilledButton(
        onPressed: _controller.text.trim().isEmpty ? null : _save,
        child: const Text('Guardar'),
      ),
    ],
  );
}

class _AppPicker extends StatefulWidget {
  const _AppPicker({required this.apps});
  final List<LaunchableApp> apps;

  @override
  State<_AppPicker> createState() => _AppPickerState();
}

class _AppPickerState extends State<_AppPicker> {
  String _query = '';

  @override
  Widget build(BuildContext context) {
    final query = _query.trim().toLowerCase();
    final apps = widget.apps
        .where(
          (app) =>
              app.label.toLowerCase().contains(query) ||
              app.packageName.toLowerCase().contains(query),
        )
        .toList(growable: false);
    return SafeArea(
      child: Padding(
        padding: EdgeInsets.only(
          left: 18,
          right: 18,
          bottom: MediaQuery.viewInsetsOf(context).bottom,
        ),
        child: SizedBox(
          height: MediaQuery.sizeOf(context).height * .78,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Seleccionar juego',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: 8),
              const Text(
                'Elige una app para tu biblioteca.',
                style: TextStyle(color: Color(0xff527394), fontSize: 12),
              ),
              const SizedBox(height: 14),
              TextField(
                decoration: const InputDecoration(
                  hintText: 'Buscar por nombre o paquete',
                  prefixIcon: MobiIcon(Icons.search_rounded, size: 27),
                ),
                onChanged: (value) => setState(() => _query = value),
              ),
              const SizedBox(height: 10),
              Expanded(
                child: apps.isEmpty
                    ? const Center(
                        child: Text(
                          'No hay aplicaciones visibles que coincidan.',
                        ),
                      )
                    : ListView.separated(
                        itemCount: apps.length,
                        separatorBuilder: (_, index) =>
                            const SizedBox(height: 4),
                        itemBuilder: (context, index) {
                          final app = apps[index];
                          return ListTile(
                            contentPadding: const EdgeInsets.symmetric(
                              horizontal: 10,
                              vertical: 4,
                            ),
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(16),
                            ),
                            tileColor: const Color(0xfff4f7fb),
                            leading: app.icon == null
                                ? MobiIcon(
                                    app.gameHint
                                        ? Icons.sports_esports_outlined
                                        : Icons.apps,
                                    size: 27,
                                  )
                                : ClipRRect(
                                    borderRadius: BorderRadius.circular(10),
                                    child: Image.memory(
                                      app.icon!,
                                      width: 44,
                                      height: 44,
                                      fit: BoxFit.cover,
                                      errorBuilder: (_, error, stack) =>
                                          const MobiIcon(Icons.apps, size: 27),
                                    ),
                                  ),
                            title: Text(app.label),
                            subtitle: Text(
                              app.packageName,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                fontSize: 11,
                                color: Color(0xff5d7a99),
                              ),
                            ),
                            trailing: const MobiIcon(
                              Icons.add_circle_outline_rounded,
                              size: 27,
                              color: Color(0xff237abf),
                            ),
                            onTap: () => Navigator.pop(context, app),
                          );
                        },
                      ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
