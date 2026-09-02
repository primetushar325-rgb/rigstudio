import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';

import '../services/demo_character.dart';
import '../state/app_state.dart';
import '../widgets/common.dart';
import 'chroma_key_screen.dart';
import 'rig_entry_screen.dart';

/// Step 2 of the build order: get a PNG into the app and onto a canvas.
class ImportScreen extends ConsumerStatefulWidget {
  const ImportScreen({super.key});

  @override
  ConsumerState<ImportScreen> createState() => _ImportScreenState();
}

class _ImportScreenState extends ConsumerState<ImportScreen> {
  bool _busy = false;
  bool _removeBackground = true;
  String? _error;

  Future<void> _pickFromGallery() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final picked = await ImagePicker().pickImage(
        source: ImageSource.gallery,
        maxWidth: 2048,
        maxHeight: 2048,
      );
      if (picked == null) {
        setState(() => _busy = false);
        return;
      }
      await _ingest(await picked.readAsBytes(), name: _nameFrom(picked.name));
    } catch (e) {
      setState(() {
        _busy = false;
        _error = '$e';
      });
    }
  }

  Future<void> _useDemo() async {
    setState(() => _busy = true);
    await _ingest(await DemoCharacter.generatePng(), name: 'Demo character');
  }

  String _nameFrom(String fileName) {
    final base = fileName.split('/').last.split('.').first;
    return base.isEmpty ? 'Character' : base;
  }

  Future<void> _ingest(Uint8List bytes, {required String name}) async {
    final character =
        await ref.read(libraryProvider.notifier).createFromBytes(bytes, name: name);
    await ref.read(editorProvider.notifier).open(character);
    if (!mounted) return;
    setState(() => _busy = false);
    Navigator.pushReplacement(
      context,
      MaterialPageRoute(
        builder: (_) => _removeBackground ? const ChromaKeyScreen() : const RigEntryScreen(),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final editor = ref.watch(editorProvider);
    final preview = editor.character;

    return Scaffold(
      appBar: AppBar(title: const Text('Import character')),
      body: BusyOverlay(
        busy: _busy,
        status: 'Importing…',
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              AspectRatio(
                aspectRatio: 3 / 4,
                child: Container(
                  decoration: BoxDecoration(
                    color: const Color(0xFF13161D),
                    borderRadius: BorderRadius.circular(18),
                    border: Border.all(color: Colors.white10),
                  ),
                  clipBehavior: Clip.antiAlias,
                  child: preview == null
                      ? const Center(
                          child: Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Icon(Icons.image_outlined, size: 56, color: Colors.white24),
                              SizedBox(height: 12),
                              Text('Pick a full-body character image',
                                  style: TextStyle(color: Colors.white54)),
                              SizedBox(height: 4),
                              Text('Any background — solid colours key out best',
                                  style: TextStyle(color: Colors.white38, fontSize: 12)),
                            ],
                          ),
                        )
                      : Image.file(File(preview.cutSource), fit: BoxFit.contain),
                ),
              ),
              const SizedBox(height: 20),
              SwitchListTile(
                value: _removeBackground,
                onChanged: (v) => setState(() => _removeBackground = v),
                title: const Text('Remove background (chroma key)'),
                subtitle: const Text(
                    'Turn on for solid-colour backdrops like green screen. '
                    'Off keeps the image exactly as imported.'),
                contentPadding: EdgeInsets.zero,
              ),
              const SizedBox(height: 8),
              FilledButton.icon(
                onPressed: _busy ? null : _pickFromGallery,
                icon: const Icon(Icons.photo_library_outlined),
                label: const Text('Choose from gallery'),
              ),
              const SizedBox(height: 10),
              OutlinedButton.icon(
                onPressed: _busy ? null : _useDemo,
                icon: const Icon(Icons.auto_awesome_outlined),
                label: const Text('Use built-in demo character'),
              ),
              if (_error != null) ...[
                const SizedBox(height: 16),
                Text(_error!, style: const TextStyle(color: Colors.redAccent)),
              ],
              const SizedBox(height: 24),
              const Text(
                'Tip: a front-facing, arms-slightly-out pose gives the cleanest '
                'automatic cuts.',
                style: TextStyle(color: Colors.white38, fontSize: 12),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
