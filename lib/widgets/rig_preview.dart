import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart' show Ticker;

import '../models/animation_clip.dart';
import '../models/playback.dart';
import '../models/skeleton.dart';
import '../rendering/rig_painter.dart';

/// Plays an [AnimationClip] on a rigged [Skeleton]. Also renders a static rest
/// pose when [clip] is null, which is what the layers screen shows.
///
/// [facing] and [motion] drive whole-rig mirroring and horizontal walk
/// translation (the character can visibly walk across the screen).
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
    this.facing = FacingDirection.right,
    this.motion,
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
  final FacingDirection facing;
  final PlaybackMotion? motion;
  final ValueChanged<double>? onTime;

  @override
  State<RigPreview> createState() => _RigPreviewState();
}

class _RigPreviewState extends State<RigPreview>
    with TickerProviderStateMixin {
  late final AnimationController _c = AnimationController(vsync: this);
  Ticker? _moveTicker;
  Duration _lastMoveTick = Duration.zero;
  double _elapsed = 0;

  @override
  void initState() {
    super.initState();
    _c.addListener(() => widget.onTime?.call(_c.value));
    _sync();
  }

  @override
  void didUpdateWidget(covariant RigPreview old) {
    super.didUpdateWidget(old);
    if (old.clip?.name != widget.clip?.name ||
        old.playing != widget.playing ||
        old.speed != widget.speed ||
        old.motion != widget.motion) {
      _sync();
    }
  }

  void _sync() {
    final clip = widget.clip;
    if (clip == null) {
      _c.stop();
      _c.value = 0;
      _stopMoveTicker();
      return;
    }
    _c.duration = Duration(
      milliseconds:
          (clip.durationSeconds * 1000 / widget.speed.clamp(0.1, 4.0)).round(),
    );
    if (widget.playing) {
      clip.loop ? _c.repeat() : _c.forward(from: 0);
      _startMoveTicker();
    } else {
      _c.stop();
      _stopMoveTicker();
    }
  }

  void _startMoveTicker() {
    final m = widget.motion;
    if (m == null || !m.moving) {
      _elapsed = 0;
      return;
    }
    _lastMoveTick = Duration.zero;
    _elapsed = 0;
    _moveTicker ??= createTicker(_onMoveTick)..start();
  }

  void _stopMoveTicker() {
    _moveTicker?.stop();
    _moveTicker?.dispose();
    _moveTicker = null;
    _elapsed = 0;
  }

  void _onMoveTick(Duration elapsed) {
    if (_lastMoveTick == Duration.zero) {
      _lastMoveTick = elapsed;
      return;
    }
    final dt = (elapsed - _lastMoveTick).inMicroseconds / 1e6;
    _lastMoveTick = elapsed;
    if (!widget.playing) return;
    setState(() => _elapsed += dt);
  }

  @override
  void dispose() {
    _stopMoveTicker();
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        // Keep the character visible as it "walks": wrap within the canvas so it
        // exits one side and re-enters the other, facing whichever way it moves.
        final motion = widget.motion;
        final moving = motion != null && motion.moving;
        final raw = moving ? motion.horizontalOffset(_elapsed) : 0.0;
        final translateX = moving
            ? PlaybackMotion.wrapTo(raw, widget.skeleton.canvasSize.width)
            : 0.0;

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
                final pose =
                    widget.clip?.sample(_c.value) ?? const <String, BonePose>{};
                return CustomPaint(
                  painter: RigPainter(
                    skeleton: widget.skeleton,
                    images: widget.images,
                    pose: pose,
                    showBones: widget.showBones,
                    selectedBoneId: widget.selectedBoneId,
                    facing: widget.facing,
                    translateX: translateX,
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
