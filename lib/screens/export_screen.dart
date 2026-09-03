import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models/animation_clip.dart';
import '../services/export_service.dart';
import '../state/app_state.dart';
import '../widgets/color_wheel_picker.dart';
import '../widgets/common.dart';
import 'paywall_screen.dart';

/// Step 9 — render the rig frame-by-frame offscreen and encode it.
class ExportScreen extends ConsumerStatefulWidget {
  const ExportScreen({super.key, required this.clip, required this.seconds});

  final AnimationClip clip;
  final double seconds;

  @override
  ConsumerState<ExportScreen> createState() => _ExportScreenState();
}

class _ExportScreenState extends ConsumerState<ExportScreen> {
  late ExportSettings _settings;
  double _progress = 0;
  String _stage = '';
  bool _running = false;
  ExportResult? _result;

  @override
  void initState() {
    super.initState();
    final s = ref.read(settingsProvider);
    _settings = ExportSettings(
      seconds: widget.seconds,
      background: s.transparentPreview ? null : s.previewBackground,
      watermark: !s.premium,
      width: s.premium ? 768 : Limits.freeMaxExportWidth,
    );
  }

  Future<void> _run() async {
    final editor = ref.read(editorProvider);
    final skeleton = editor.skeleton;
    if (skeleton == null) return;
    setState(() {
      _running = true;
      _progress = 0;
      _result = null;
    });
    final result = await ExportService.export(
      skeleton: skeleton,
      images: editor.partImages,
      clip: widget.clip,
      settings: _settings,
      characterName: editor.character?.name ?? 'character',
      onProgress: (p, stage) {
        if (!mounted) return;
        setState(() {
          _progress = p;
          _stage = stage;
        });
      },
    );
    if (!mounted) return;
    setState(() {
      _running = false;
      _result = result;
    });
  }

  @override
  Widget build(BuildContext context) {
    final premium = ref.watch(settingsProvider).premium;
    final maxSeconds = premium ? 30.0 : Limits.freeMaxExportSeconds;
    final maxWidth = premium ? 1440 : Limits.freeMaxExportWidth;
    // A corrupt export is never offered for Save/Share (magic-byte validated).
    final resultReady = _result != null && ExportService.isResultReady(_result!);

    return Scaffold(
      appBar: AppBar(title: Text('Export · ${widget.clip.label}')),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          SegmentedButton<ExportFormat>(
            segments: const [
              ButtonSegment(value: ExportFormat.gif, label: Text('GIF')),
              ButtonSegment(value: ExportFormat.pngSequence, label: Text('PNG seq')),
              ButtonSegment(value: ExportFormat.mp4, label: Text('MP4 (Beta)')),
            ],
            selected: {_settings.format},
            onSelectionChanged: (s) {
              final f = s.first;
              if (f == ExportFormat.mp4 && !premium) {
                Navigator.push(
                  context,
                  MaterialPageRoute(
                    builder: (_) =>
                        const PaywallScreen(reason: 'HD mp4 export is Premium.'),
                  ),
                );
                return;
              }
              setState(() => _settings = _settings.copyWith(format: f));
            },
          ),
          if (_settings.format == ExportFormat.mp4)
            const Padding(
              padding: EdgeInsets.only(top: 8),
              child: Text(
                'MP4 is Beta — it needs the on-device ffmpeg encoder linked. '
                'GIF is the fully-verified default and always works.',
                style: TextStyle(fontSize: 11, color: Color(0xFFFFC46B)),
              ),
            ),
          const SizedBox(height: 16),
          LabeledSlider(
            label: 'Duration',
            value: _settings.seconds.clamp(0.5, maxSeconds),
            min: 0.5,
            max: maxSeconds,
            digits: 1,
            suffix: 's',
            onChanged: (v) => setState(() => _settings = _settings.copyWith(seconds: v)),
          ),
          LabeledSlider(
            label: 'FPS',
            value: _settings.fps.toDouble(),
            min: 8,
            max: 30,
            divisions: 22,
            digits: 0,
            onChanged: (v) =>
                setState(() => _settings = _settings.copyWith(fps: v.round())),
          ),
          LabeledSlider(
            label: 'Width',
            value: _settings.width.toDouble().clamp(128, maxWidth.toDouble()),
            min: 128,
            max: maxWidth.toDouble(),
            divisions: 16,
            digits: 0,
            suffix: 'px',
            onChanged: (v) =>
                setState(() => _settings = _settings.copyWith(width: v.round())),
          ),
          const SizedBox(height: 8),
          const Text('Background', style: TextStyle(fontSize: 13)),
          const SizedBox(height: 8),
          BackgroundPickerBar(
            color: _settings.background ?? const Color(0xFF101216),
            transparent: _settings.background == null,
            onPicked: (c) => setState(() => _settings = c == null
                ? _settings.copyWith(clearBackground: true)
                : _settings.copyWith(background: c)),
          ),
          const SizedBox(height: 8),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            value: _settings.watermark,
            onChanged: premium
                ? (v) => setState(() => _settings = _settings.copyWith(watermark: v))
                : null,
            title: const Text('Watermark'),
            subtitle: Text(premium
                ? 'Premium: watermark is optional.'
                : 'Free exports include the RigStudio watermark.'),
          ),
          const SizedBox(height: 8),
          if (_running) ...[
            LinearProgressIndicator(value: _progress),
            const SizedBox(height: 8),
            Text(_stage, style: const TextStyle(fontSize: 12, color: Colors.white54)),
          ] else
            FilledButton.icon(
              onPressed: _run,
              icon: const Icon(Icons.movie_creation_outlined),
              label: Text('Render ${_settings.frameCount} frames'),
            ),
          if (_result != null) ...[
            const SizedBox(height: 20),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(resultReady ? Icons.check_circle : Icons.error_outline,
                            color: resultReady ? Colors.greenAccent : Colors.orangeAccent),
                        const SizedBox(width: 8),
                        Text('${_result!.frames} frames exported',
                            style: const TextStyle(fontWeight: FontWeight.w700)),
                      ],
                    ),
                    const SizedBox(height: 6),
                    Text(_result!.path,
                        style: const TextStyle(fontSize: 11, color: Colors.white38)),
                    if (_result!.note != null) ...[
                      const SizedBox(height: 8),
                      Text('Note: ${_result!.note}',
                          style: const TextStyle(fontSize: 11, color: Colors.white54)),
                    ],
                    if (resultReady &&
                        _result!.format == ExportFormat.gif &&
                        File(_result!.path).existsSync()) ...[
                      const SizedBox(height: 12),
                      ClipRRect(
                        borderRadius: BorderRadius.circular(12),
                        child: Image.file(File(_result!.path), height: 220),
                      ),
                    ],
                    if (!resultReady)
                      const Padding(
                        padding: EdgeInsets.symmetric(vertical: 8),
                        child: Text(
                          'This export did not pass validation (it may be corrupt). '
                          'It has NOT been sent to the gallery — pick GIF and render again.',
                          style: TextStyle(fontSize: 12, color: Colors.orangeAccent),
                        ),
                      ),
                    if (resultReady) ...[
                      const SizedBox(height: 12),
                      Row(
                        children: [
                          Expanded(
                            child: OutlinedButton.icon(
                              onPressed: () async {
                                final ok = await ExportService.saveToGallery(_result!);
                                if (!context.mounted) return;
                                ScaffoldMessenger.of(context).showSnackBar(SnackBar(
                                  content: Text(ok
                                      ? 'Saved to gallery'
                                      : 'Gallery rejected this format — try sharing'),
                                ));
                              },
                              icon: const Icon(Icons.download),
                              label: const Text('Save'),
                            ),
                          ),
                          const SizedBox(width: 12),
                          Expanded(
                            child: FilledButton.icon(
                              onPressed: () => ExportService.shareResult(_result!),
                              icon: const Icon(Icons.ios_share),
                              label: const Text('Share'),
                            ),
                          ),
                        ],
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ],
          const SizedBox(height: 24),
          const Text(
            'Every export is validated (file size + magic bytes) before it can '
            'be saved or shared, so a corrupt file never reaches the gallery. '
            'GIF encodes fully on-device via the image package and is the '
            'recommended default.',
            style: TextStyle(fontSize: 11, color: Colors.white38),
          ),
        ],
      ),
    );
  }
}
