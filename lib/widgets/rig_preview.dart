import 'dart:ui' as ui;

import 'package:flutter/material.dart';

import '../models/animation_clip.dart';
import '../models/skeleton.dart';
import '../rendering/rig_painter.dart';

/// Plays an [AnimationClip] on a rigged [Skeleton]. Also renders a static rest
/// pose when [clip] is null, which is what the layers screen shows.
class RigPreview extends StatefulWidget {
  const RigPreview({
    super.key,
    required this.skeleton,
    required this.images,
    this.clip,
    this.playing = true,
    this.speed = 1.0,
    this.background,
    this.transparent = false,
    this.showBones = false,
    this.selectedBoneId,
    this.onTime,
  });

  final Skeleton skeleton;
  final Map<String, ui.Image> images;
  final AnimationClip? clip;
  final bool playing;
  final double speed;
  final Color? background;
  final bool transparent;
  final bool showBones;
  final String? selectedBoneId;
  final ValueChanged<double>? onTime;

  @override
  State<RigPreview> createState() => _RigPreviewState();
}

class _RigPreviewState extends State<RigPreview> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(vsync: this);

  @override
  void initState() {
    super.initState();
    _sync();
    _c.addListener(() => widget.onTime?.call(_c.value));
  }

  @override
  void didUpdateWidget(covariant RigPreview old) {
    super.didUpdateWidget(old);
    if (old.clip?.name != widget.clip?.name ||
        old.playing != widget.playing ||
        old.speed != widget.speed) {
      _sync();
    }
  }

  void _sync() {
    final clip = widget.clip;
    if (clip == null) {
      _c.stop();
      _c.value = 0;
      return;
    }
    _c.duration = Duration(
      milliseconds:
          (clip.durationSeconds * 1000 / widget.speed.clamp(0.1, 4.0)).round(),
    );
    if (widget.playing) {
      clip.loop ? _c.repeat() : _c.forward(from: 0);
    } else {
      _c.stop();
    }
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        return Stack(
          fit: StackFit.expand,
          children: [
            if (widget.transparent)
              CustomPaint(painter: CheckerboardPainter())
            else if (widget.background != null)
              ColoredBox(color: widget.background!),
            AnimatedBuilder(
              animation: _c,
              builder: (context, _) {
                final pose = widget.clip?.sample(_c.value) ?? const <String, BonePose>{};
                return CustomPaint(
                  painter: RigPainter(
                    skeleton: widget.skeleton,
                    images: widget.images,
                    pose: pose,
                    showBones: widget.showBones,
                    selectedBoneId: widget.selectedBoneId,
                  ),
                  size: Size(constraints.maxWidth, constraints.maxHeight),
                );
              },
            ),
          ],
        );
      },
    );
  }
}
