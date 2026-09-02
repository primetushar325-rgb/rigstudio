import 'dart:math' as math;
import 'dart:ui';

import 'package:vector_math/vector_math_64.dart' show Matrix4, Vector3;

import '../models/animation_clip.dart';
import '../models/bone_part.dart';
import '../models/skeleton.dart';

/// ---------------------------------------------------------------------------
/// Pure 2D forward kinematics.
///
///   world(bone) = world(parent) · T(translation) · T(pivot) · R(θ) · T(-pivot)
///
/// Everything lives in canvas space, so a bone's pivot is simply the joint
/// position in the source image and children inherit their parent's transform
/// automatically. No 3D engine, no skinning — classic cut-out animation.
/// ---------------------------------------------------------------------------
class PoseSolver {
  /// Resolves world matrices for every bone.
  ///
  /// [pose] holds the animated local values; bones missing from it fall back to
  /// the [BonePart.rotation] / [BonePart.translation] currently on the model.
  /// [offsetScale] converts clip offsets (fractions of rig height) to pixels.
  static Map<String, Matrix4> solve(
    Skeleton skeleton, {
    Map<String, BonePose> pose = const {},
    double offsetScale = 1.0,
    Matrix4? rootMatrix,
  }) {
    final result = <String, Matrix4>{};
    for (final bone in skeleton.topologicalOrder) {
      final p = pose[bone.id];
      final rot = (p?.rotation ?? 0) + bone.rotation;
      final trs = (p?.offset ?? Offset.zero) * offsetScale + bone.translation;

      final local = Matrix4.identity()
        ..translateByVector3(Vector3(bone.pivot.dx + trs.dx, bone.pivot.dy + trs.dy, 0))
        ..rotateZ(rot)
        ..translateByVector3(Vector3(-bone.pivot.dx, -bone.pivot.dy, 0));

      final parentMatrix = bone.parentId == null
          ? (rootMatrix ?? Matrix4.identity())
          : (result[bone.parentId] ?? rootMatrix ?? Matrix4.identity());

      result[bone.id] = parentMatrix.multiplied(local);
    }
    return result;
  }

  /// Applies a matrix to a point (z ignored).
  static Offset transformPoint(Matrix4 m, Offset p) {
    final s = m.storage;
    return Offset(
      s[0] * p.dx + s[4] * p.dy + s[12],
      s[1] * p.dx + s[5] * p.dy + s[13],
    );
  }

  /// Rough rig height in pixels — used to scale clip translation offsets so the
  /// same clip data works for a 300px and a 3000px character.
  static double rigHeight(Skeleton s) {
    if (s.bones.isEmpty) return s.canvasSize.height;
    var minY = double.infinity, maxY = -double.infinity;
    for (final b in s.bones) {
      minY = math.min(minY, b.pivot.dy);
      maxY = math.max(maxY, b.pivot.dy);
      if (b.imageRect != Rect.zero) {
        minY = math.min(minY, b.imageRect.top);
        maxY = math.max(maxY, b.imageRect.bottom);
      }
    }
    final h = maxY - minY;
    return h.isFinite && h > 1 ? h : s.canvasSize.height;
  }

  /// Axis-aligned bounds of the posed rig (used by the exporter to frame).
  static Rect posedBounds(Skeleton s, Map<String, Matrix4> world) {
    var l = double.infinity, t = double.infinity, r = -double.infinity, b = -double.infinity;
    for (final bone in s.bones) {
      final m = world[bone.id];
      if (m == null) continue;
      final rect = bone.imageRect == Rect.zero
          ? Rect.fromCircle(center: bone.pivot, radius: 8)
          : bone.imageRect;
      for (final p in [rect.topLeft, rect.topRight, rect.bottomLeft, rect.bottomRight]) {
        final w = transformPoint(m, p);
        l = math.min(l, w.dx);
        t = math.min(t, w.dy);
        r = math.max(r, w.dx);
        b = math.max(b, w.dy);
      }
    }
    if (!l.isFinite) return Rect.fromLTWH(0, 0, s.canvasSize.width, s.canvasSize.height);
    return Rect.fromLTRB(l, t, r, b);
  }
}

/// Convenience holder passed to the painter: the clip, the time and the images.
class PlaybackState {
  const PlaybackState({
    required this.clip,
    required this.time,
    this.paused = false,
  });

  final AnimationClip? clip;
  final double time; // normalised 0..1
  final bool paused;

  Map<String, BonePose> get pose => clip?.sample(time) ?? const {};
}
