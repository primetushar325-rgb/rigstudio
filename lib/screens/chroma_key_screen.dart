import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../services/chroma_key_service.dart';
import '../state/app_state.dart';
import '../widgets/color_wheel_picker.dart';
import '../widgets/common.dart';
import '../rendering/rig_painter.dart';
import 'rig_entry_screen.dart';

/// Step 3: solid-colour background removal, previewed live on a downscaled
/// copy and baked at full resolution on confirm. All pixel work happens on a
/// background isolate via `compute()`.
class ChromaKeyScreen extends ConsumerStatefulWidget {
  const ChromaKeyScreen({super.key});

  @override
  ConsumerState<ChromaKeyScreen> createState() => _ChromaKeyScreenState();
}

class _ChromaKeyScreenState extends ConsumerState<ChromaKeyScreen> {
  Uint8List? _preview;
  Timer? _debounce;
  bool _rendering = false;
  bool _pickingColor = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _initialGuess());
  }

  Future<void> _initialGuess() async {
    final character = ref.read(editorProvider).character;
    if (character == null) return;
    final bytes = await File(character.sourceImagePath).readAsBytes();
    final guess = await ChromaKeyService.guessKeyColor(bytes);
    ref.read(editorProvider.notifier).setChroma(
          ref.read(editorProvider).chroma.copyWith(keyColor: guess),
        );
    _schedulePreview();
  }

  void _schedulePreview() {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 120), _renderPreview);
  }

  Future<void> _renderPreview() async {
    if (_rendering) return;
    setState(() => _rendering = true);
    final bytes = await ref.read(editorProvider.notifier).previewChroma();
    if (!mounted) return;
    setState(() {
      _preview = bytes;
      _rendering = false;
    });
  }

  @override
  void dispose() {
    _debounce?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final editor = ref.watch(editorProvider);
    final params = editor.chroma;
    final notifier = ref.read(editorProvider.notifier);

    void update(ChromaKeyParams p) {
      notifier.setChroma(p);
      _schedulePreview();
    }

    return Scaffold(
      appBar: AppBar(
        title: const Text('Remove background'),
        actions: [
          TextButton(
            onPressed: () async {
              await notifier.skipChroma();
              if (!context.mounted) return;
              Navigator.pushReplacement(
                  context, MaterialPageRoute(builder: (_) => const RigEntryScreen()));
            },
            child: const Text('Skip'),
          ),
        ],
      ),
      body: BusyOverlay(
        busy: editor.busy,
        status: editor.status,
        child: Column(
          children: [
            Expanded(
              child: Stack(
                fit: StackFit.expand,
                children: [
                  CustomPaint(painter: CheckerboardPainter()),
                  if (_preview != null)
                    Image.memory(_preview!, fit: BoxFit.contain, gaplessPlayback: true)
                  else if (editor.character != null)
                    Image.file(File(editor.character!.sourceImagePath), fit: BoxFit.contain),
                  if (_rendering)
                    const Align(
                      alignment: Alignment.topRight,
                      child: Padding(
                        padding: EdgeInsets.all(12),
                        child: SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2)),
                      ),
                    ),
                ],
              ),
            ),
            Container(
              decoration: const BoxDecoration(
                color: Color(0xFF13161D),
                borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
              ),
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
              child: SafeArea(
                top: false,
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Row(
                      children: [
                        const Text('Key colour', style: TextStyle(fontSize: 13)),
                        const SizedBox(width: 12),
                        GestureDetector(
                          onTap: () => setState(() => _pickingColor = !_pickingColor),
                          child: Container(
                            width: 40,
                            height: 28,
                            decoration: BoxDecoration(
                              color: params.keyColor,
                              borderRadius: BorderRadius.circular(8),
                              border: Border.all(color: Colors.white30),
                            ),
                          ),
                        ),
                        const Spacer(),
                        TextButton.icon(
                          onPressed: () =>
                              update(params.copyWith(keyColor: const Color(0xFF00B140))),
                          icon: const Icon(Icons.restart_alt, size: 16),
                          label: const Text('Green'),
                        ),
                        Switch(
                          value: params.despill,
                          onChanged: (v) => update(params.copyWith(despill: v)),
                        ),
                        const Text('Despill', style: TextStyle(fontSize: 12)),
                      ],
                    ),
                    if (_pickingColor)
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 10),
                        child: ColorWheelPicker(
                          size: 180,
                          color: params.keyColor,
                          onChanged: (c) => update(params.copyWith(keyColor: c)),
                        ),
                      ),
                    LabeledSlider(
                      label: 'Tolerance',
                      value: params.tolerance,
                      onChanged: (v) => update(params.copyWith(tolerance: v)),
                      max: 0.9,
                    ),
                    LabeledSlider(
                      label: 'Edge feather',
                      value: params.feather,
                      onChanged: (v) => update(params.copyWith(feather: v)),
                      max: 0.6,
                    ),
                    const SizedBox(height: 8),
                    SizedBox(
                      width: double.infinity,
                      child: FilledButton.icon(
                        onPressed: () async {
                          await notifier.applyChroma();
                          if (!context.mounted) return;
                          Navigator.pushReplacement(context,
                              MaterialPageRoute(builder: (_) => const RigEntryScreen()));
                        },
                        icon: const Icon(Icons.check),
                        label: const Text('Bake transparent PNG'),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
