import 'package:flutter/material.dart';

import '../../../../application/models/game_models.dart';
import '../../../../shared/widgets/mobi_icon.dart';

class AppPicker extends StatefulWidget {
  const AppPicker({super.key, required this.apps});
  final List<LaunchableApp> apps;

  @override
  State<AppPicker> createState() => AppPickerState();
}

class AppPickerState extends State<AppPicker> {
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
