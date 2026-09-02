import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/animation_library.dart';
import '../models/animation_clip.dart';
import '../state/app_state.dart';
import '../widgets/color_wheel_picker.dart';
import '../widgets/common.dart';
import '../widgets/rig_preview.dart';
import 'export_screen.dart';
import 'layers_screen.dart';
import 'paywall_screen.dart';

/// Steps 7-8 — clip playback on the rigged character plus the background
/// picker and duration trimmer.
class AnimateScreen extends ConsumerStatefulWidget {
  const AnimateScreen({super.key});

  @override
  ConsumerState<AnimateScreen> createState() => _AnimateScreenState();
}

class _AnimateScreenState extends ConsumerState<AnimateScreen> {
  late AnimationClip _clip = kIdle;
  bool _playing = true;
  double _speed = 1.0;
  double _trimSeconds = 3.0;
  bool _showBones = false;

  @override
  void initState() {
    super.initState();
    final last = ref.read(editorProvider).character?.lastClip;
    if (last != null) _clip = clipByName(last);
    _trimSeconds = _clip.durationSeconds.clamp(1.0, 6.0);
  }

  @override
  Widget build(BuildContext context) {
    final editor = ref.watch(editorProvider);
    final settings = ref.watch(settingsProvider);
    final skeleton = editor.skeleton;

    if (skeleton == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('Animate')),
        body: const Center(child: Text('This character has no rig yet.')),
      );
    }

    final maxSeconds = settings.premium ? 30.0 : Limits.freeMaxExportSeconds;

    return Scaffold(
      appBar: AppBar(
        title: Text(editor.character?.name ?? 'Animate'),
        actions: [
          IconButton(
            tooltip: 'Bones overlay',
            icon: Icon(Icons.polyline,
                color: _showBones ? Colors.amber : null),
            onPressed: () => setState(() => _showBones = !_showBones),
          ),
          IconButton(
            tooltip: 'Layers & pivots',
            icon: const Icon(Icons.layers_outlined),
            onPressed: () => Navigator.push(
                context, MaterialPageRoute(builder: (_) => const LayersScreen())),
          ),
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: Container(
              margin: const EdgeInsets.fromLTRB(16, 8, 16, 8),
              clipBehavior: Clip.antiAlias,
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(18),
                border: Border.all(color: Colors.white10),
              ),
              child: RigPreview(
                skeleton: skeleton,
                images: editor.partImages,
                clip: _clip,
                playing: _playing,
                speed: _speed,
                background: settings.previewBackground,
                transparent: settings.transparentPreview,
                showBones: _showBones,
              ),
            ),
          ),
          _ClipStrip(
            selected: _clip,
            premium: settings.premium,
            onSelect: (clip) {
              if (Limits.clipLocked(settings.premium, clip.premium)) {
                Navigator.push(
                  context,
                  MaterialPageRoute(
                    builder: (_) => PaywallScreen(
                        reason: '“${clip.label}” is part of the Premium clip pack.'),
                  ),
                );
                return;
              }
              setState(() {
                _clip = clip;
                _trimSeconds = clip.durationSeconds.clamp(1.0, maxSeconds);
                _playing = true;
              });
              ref.read(editorProvider.notifier).setLastClip(clip.name);
            },
          ),
          Container(
            decoration: const BoxDecoration(
              color: Color(0xFF13161D),
              borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
            ),
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
            child: SafeArea(
              top: false,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Row(
                    children: [
                      IconButton.filledTonal(
                        onPressed: () => setState(() => _playing = !_playing),
                        icon: Icon(_playing ? Icons.pause : Icons.play_arrow),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: LabeledSlider(
                          label: 'Speed',
                          value: _speed,
                          min: 0.25,
                          max: 2.0,
                          digits: 2,
                          suffix: '×',
                          onChanged: (v) => setState(() => _speed = v),
                        ),
                      ),
                    ],
                  ),
                  LabeledSlider(
                    label: 'Export length',
                    value: _trimSeconds.clamp(0.5, maxSeconds),
                    min: 0.5,
                    max: maxSeconds,
                    digits: 1,
                    suffix: 's',
                    onChanged: (v) => setState(() => _trimSeconds = v),
                  ),
                  if (!settings.premium)
                    const Padding(
                      padding: EdgeInsets.only(bottom: 4),
                      child: Text(
                        'Free tier caps exports at '
                        '${Limits.freeMaxExportSeconds} s with a watermark.',
                        style: TextStyle(fontSize: 11, color: Colors.white38),
                      ),
                    ),
                  const SizedBox(height: 4),
                  Align(
                    alignment: Alignment.centerLeft,
                    child: BackgroundPickerBar(
                      color: settings.previewBackground,
                      transparent: settings.transparentPreview,
                      onPicked: (c) => ref.read(settingsProvider.notifier).setPreviewBackground(
                            c ?? settings.previewBackground,
                            transparent: c == null,
                          ),
                    ),
                  ),
                  const SizedBox(height: 8),
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton.icon(
                      onPressed: () => Navigator.push(
                        context,
                        MaterialPageRoute(
                          builder: (_) => ExportScreen(
                            clip: _clip,
                            seconds: _trimSeconds,
                          ),
                        ),
                      ),
                      icon: const Icon(Icons.ios_share),
                      label: const Text('Export'),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ClipStrip extends StatelessWidget {
  const _ClipStrip({
    required this.selected,
    required this.onSelect,
    required this.premium,
  });

  final AnimationClip selected;
  final ValueChanged<AnimationClip> onSelect;
  final bool premium;

  static const Map<String, IconData> _icons = {
    'idle': Icons.self_improvement,
    'stand': Icons.man,
    'walk': Icons.directions_walk,
    'run': Icons.directions_run,
    'wave': Icons.waving_hand,
    'talk': Icons.record_voice_over,
    'sit': Icons.chair,
    'sleep': Icons.bedtime,
    'jump': Icons.rocket_launch,
  };

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 92,
      child: ListView.builder(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 12),
        itemCount: kAnimationLibrary.length,
        itemBuilder: (context, i) {
          final clip = kAnimationLibrary[i];
          final isSelected = clip.name == selected.name;
          final locked = Limits.clipLocked(premium, clip.premium);
          return GestureDetector(
            onTap: () => onSelect(clip),
            child: Container(
              width: 84,
              margin: const EdgeInsets.symmetric(horizontal: 5, vertical: 10),
              decoration: BoxDecoration(
                color: isSelected ? const Color(0xFF2B2350) : const Color(0xFF171A21),
                borderRadius: BorderRadius.circular(16),
                border: Border.all(
                    color: isSelected ? Colors.amber : Colors.white10,
                    width: isSelected ? 2 : 1),
              ),
              child: Stack(
                children: [
                  Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(_icons[clip.name] ?? Icons.animation,
                            color: isSelected ? Colors.amber : Colors.white70),
                        const SizedBox(height: 6),
                        Text(clip.label,
                            style: TextStyle(
                                fontSize: 12,
                                fontWeight:
                                    isSelected ? FontWeight.w800 : FontWeight.w500)),
                      ],
                    ),
                  ),
                  if (locked)
                    const Positioned(
                      right: 6,
                      top: 6,
                      child: Icon(Icons.lock, size: 13, color: Color(0xFFFFA94D)),
                    ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }
}
