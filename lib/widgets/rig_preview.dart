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
    this.propImages = const <String, ui.Image>{},
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
  final Map<String, ui.Image> propImages;
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
    // Looping is driven here (forward → completed → forward) instead of
    // AnimationController.repeat() so pause/resume is exact: `stop()` freezes
    // the clip and `forward()` continues from the same frame.
    _c.addStatusListener((status) {
      if (status == AnimationStatus.completed &&
          widget.clip?.loop == true &&
          widget.playing) {
        _c.forward(from: 0);
      }
    });
    _sync(fromStart: true);
  }

  @override
  void didUpdateWidget(covariant RigPreview old) {
    super.didUpdateWidget(old);
    // Compare motion by VALUE — the parent rebuilds a fresh PlaybackMotion on
    // every frame, so an identity check would restart the clip whenever an
    // unrelated control (bones toggle, background picker) changes.
    final clipChanged = old.clip?.name != widget.clip?.name;
    if (clipChanged ||
        old.playing != widget.playing ||
        old.speed != widget.speed ||
        !_sameMotion(old.motion, widget.motion)) {
      _sync(fromStart: clipChanged);
    }
  }

  static bool _sameMotion(PlaybackMotion? a, PlaybackMotion? b) {
    if (identical(a, b)) return true;
    if (a == null || b == null) return false;
    return a.facing == b.facing &&
        a.walking == b.walking &&
        a.inPlace == b.inPlace &&
        a.walkSpeed == b.walkSpeed &&
        a.wrap == b.wrap;
  }

  /// Reconciles the controller with the current widget config. Only restarts
  /// the animation when it must (new clip / finished clip); a pause → play
  /// resumes from the paused frame instead of jumping back to zero.
  void _sync({bool fromStart = false}) {
    final clip = widget.clip;
    if (clip == null) {
      _c.stop();
      _c.value = 0;
      _stopMoveTicker();
      return;
    }
    final dur = Duration(
      milliseconds:
          (clip.durationSeconds * 1000 / widget.speed.clamp(0.1, 4.0)).round(),
    );
    if (fromStart) {
      // New clip: always start its first frame, even if the duration is
      // identical to the previous clip's.
      _c.stop();
      if (_c.duration != dur) _c.duration = dur;
      if (widget.playing) {
        _c.forward(from: 0);
      } else {
        _c.value = 0;
      }
    } else if (_c.duration != dur) {
      // A running animation keeps its original duration, so a speed change
      // restarts the simulation — from the current frame, not from zero.
      final resumeFrom = _c.value.clamp(0.0, 1.0);
      _c.stop();
      _c.duration = dur;
      if (widget.playing) {
        _c.forward(from: resumeFrom >= 1.0 ? 0.0 : resumeFrom);
      } else {
        _c.value = resumeFrom;
      }
    } else if (widget.playing && !_c.isAnimating) {
      if (_c.value >= 1.0) {
        _c.forward(from: 0);
      } else {
        _c.forward(); // resume exactly where it was paused
      }
    } else if (!widget.playing && _c.isAnimating) {
      _c.stop();
    }
    if (widget.playing) {
      _startMoveTicker();
    } else {
      _stopMoveTicker();
    }
  }

  void _startMoveTicker() {
    final m = widget.motion;
    if (m == null || !m.moving) {
      _stopMoveTicker();
      return;
    }
    if (_moveTicker != null && _moveTicker!.isActive) return; // keep walking
    _lastMoveTick = Duration.zero;
    _elapsed = 0;
    _moveTicker ??= createTicker(_onMoveTick);
    _moveTicker!.start();
  }

  void _stopMoveTicker() {
    _moveTicker?.stop();
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
    _moveTicker?.dispose();
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
                    props: widget.skeleton.props,
                    propImages: widget.propImages,
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
