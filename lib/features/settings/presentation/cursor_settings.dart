import 'package:flutter/material.dart';

import '../../../application/ports/mapper_gateway.dart';
import '../../../shared/widgets/uwu_icon.dart';

class CursorSettings extends StatefulWidget {
  const CursorSettings({
    super.key,
    required this.gateway,
    required this.onConnection,
  });

  final MapperGateway gateway;
  final VoidCallback onConnection;

  @override
  State<CursorSettings> createState() => _CursorSettingsState();
}

class _CursorSettingsState extends State<CursorSettings> {
  String? _selected;
  String? _error;
  bool _saving = false;

  static const _styles = [
    ('classic', 'Clásico', Color(0xfff6f9ff), Color(0xff193466)),
    ('dark', 'Oscuro', Color(0xff1e2639), Colors.white),
    ('cyan', 'Cian', Color(0xff39e1f1), Color(0xff193466)),
    ('pink', 'Rosa', Color(0xffff7eb8), Color(0xff193466)),
  ];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final style = await widget.gateway.cursorStyle();
      if (mounted) {
        setState(() {
          _selected = style;
          _error = null;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() => _error = 'No se pudo cargar el estilo del cursor.');
      }
    }
  }

  Future<void> _select(String style) async {
    if (_saving || style == _selected) return;
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await widget.gateway.setCursorStyle(style);
      if (mounted) setState(() => _selected = style);
    } catch (_) {
      if (mounted) {
        setState(() => _error = 'No se pudo guardar el estilo del cursor.');
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  String get _selectedLabel =>
      _styles
          .where((style) => style.$1 == _selected)
          .map((style) => style.$2)
          .firstOrNull ??
      (_error == null ? 'Cargando…' : 'No disponible');

  void _showCursorStyles() {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => StatefulBuilder(
        builder: (sheetContext, updateSheet) => SizedBox(
          height: MediaQuery.sizeOf(context).height * .8,
          child: ListView(
            padding: const EdgeInsets.fromLTRB(20, 4, 20, 28),
            children: [
              Row(
                children: [
                  const CircleAvatar(
                    backgroundColor: Color(0xffedf5fc),
                    child: Icon(
                      Icons.near_me_outlined,
                      color: Color(0xff1966ab),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Estilo del cursor',
                          style: Theme.of(context).textTheme.headlineSmall
                              ?.copyWith(fontWeight: FontWeight.w800),
                        ),
                        const SizedBox(height: 3),
                        const Text('Elige cómo se verá en tus juegos.'),
                      ],
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 20),
              if (_error != null) ...[
                Text(
                  _error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
                if (_selected == null)
                  TextButton.icon(
                    onPressed: () async {
                      await _load();
                      if (sheetContext.mounted) updateSheet(() {});
                    },
                    icon: const UwuIcon(Icons.refresh_rounded, size: 22),
                    label: const Text('Reintentar'),
                  ),
                const SizedBox(height: 8),
              ],
              if (_selected == null && _error == null)
                const Center(child: CircularProgressIndicator()),
              if (_selected != null)
                for (final (id, label, fill, edge) in _styles)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 10),
                    child: Semantics(
                      selected: _selected == id,
                      button: true,
                      child: Material(
                        color: _selected == id
                            ? const Color(0xffe5f2ff)
                            : const Color(0xfff0f8ff),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(16),
                          side: BorderSide(
                            color: _selected == id
                                ? const Color(0xff2467b5)
                                : const Color(0xffc4e0f5),
                          ),
                        ),
                        child: ListTile(
                          key: ValueKey('cursor-style-$id'),
                          selected: _selected == id,
                          onTap: _saving
                              ? null
                              : () async {
                                  final pending = _select(id);
                                  updateSheet(() {});
                                  await pending;
                                  if (sheetContext.mounted) updateSheet(() {});
                                },
                          leading: CustomPaint(
                            size: const Size(27, 32),
                            painter: _CursorPainter(fill, edge),
                          ),
                          title: Text(
                            label,
                            style: const TextStyle(fontWeight: FontWeight.w700),
                          ),
                          trailing: _selected == id
                              ? const Icon(
                                  Icons.check_circle_rounded,
                                  color: Color(0xff2467b5),
                                )
                              : null,
                        ),
                      ),
                    ),
                  ),
            ],
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) => ListView(
    padding: const EdgeInsets.fromLTRB(20, 22, 20, 28),
    children: [
      Text(
        'Ajustes',
        style: Theme.of(context).textTheme.headlineSmall
            ?.copyWith(fontWeight: FontWeight.w800),
      ),
      const SizedBox(height: 18),
      Card(
        child: ListTile(
          leading: const Icon(Icons.link_rounded),
          title: const Text(
            'Conexión',
            style: TextStyle(fontWeight: FontWeight.w700),
          ),
          subtitle: const Text('Permisos y servicio'),
          trailing: const Icon(Icons.chevron_right_rounded),
          onTap: widget.onConnection,
        ),
      ),
      Card(
        child: ListTile(
          key: const ValueKey('cursor-settings'),
          leading: const Icon(Icons.near_me_outlined),
          title: const Text(
            'Estilo del cursor',
            style: TextStyle(fontWeight: FontWeight.w700),
          ),
          subtitle: Text(_selectedLabel),
          trailing: const Icon(Icons.chevron_right_rounded),
          onTap: _selected != null || _error != null ? _showCursorStyles : null,
        ),
      ),
    ],
  );
}

class _CursorPainter extends CustomPainter {
  const _CursorPainter(this.fill, this.edge);
  final Color fill, edge;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.save();
    canvas.translate(2, 2);
    canvas.scale((size.height - 5) / 20);
    final path = Path()
      ..moveTo(0, 0)
      ..lineTo(1.4, 20)
      ..lineTo(7.8, 13.2)
      ..lineTo(17.5, 13.6)
      ..close();
    canvas.drawPath(path, Paint()..color = fill);
    canvas.drawPath(
      path,
      Paint()
        ..color = edge
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.1
        ..strokeJoin = StrokeJoin.round,
    );
    canvas.restore();
  }

  @override
  bool shouldRepaint(_CursorPainter oldDelegate) =>
      fill != oldDelegate.fill || edge != oldDelegate.edge;
}
