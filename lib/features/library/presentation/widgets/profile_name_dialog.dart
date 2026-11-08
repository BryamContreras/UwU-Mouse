import 'package:flutter/material.dart';

class ProfileNameDialog extends StatefulWidget {
  const ProfileNameDialog({
    super.key,
    required this.title,
    required this.initial,
  });
  final String title, initial;

  @override
  State<ProfileNameDialog> createState() => ProfileNameDialogState();
}

class ProfileNameDialogState extends State<ProfileNameDialog> {
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
