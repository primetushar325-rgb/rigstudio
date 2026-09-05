import 'dart:ui';

/// One keyframe on one bone's track. [t] is normalised 0..1 clip time.
class BoneKeyframe {
  const BoneKeyframe(this.t, this.rotation, [this.offset = Offset.zero]);

  final double t;
  final double rotation; // radians
  final Offset offset; // canvas-space translation offset

  static BoneKeyframe lerp(BoneKeyframe a, BoneKeyframe b, double u) {
    return BoneKeyframe(
      a.t + (b.t - a.t) * u,
      a.rotation + (b.rotation - a.rotation) * u,
      Offset.lerp(a.offset, b.offset, u) ?? Offset.zero,
    );
  }
}

/// The pose of one bone at one instant.
class BonePose {
  const BonePose({this.rotation = 0, this.offset = Offset.zero});
  final double rotation;
  final Offset offset;
}

/// A hand-authored animation. Tracks are keyed by the *standard bone ids*, so
/// any character rigged with the standard skeleton plays every clip with no
/// remapping.
class AnimationClip {
  const AnimationClip({
    required this.name,
    required this.label,
    required this.durationSeconds,
    required this.loop,
    required this.tracks,
    this.premium = false,
    this.smooth = true,
  });

  final String name;
  final String label;
  final double durationSeconds;
  final bool loop;
  final Map<String, List<BoneKeyframe>> tracks;

  /// Gated behind the paid tier (UI only for now).
  final bool premium;

  /// Smoothstep between keys instead of hard linear.
  final bool smooth;

  /// Sample the whole clip at normalised time [t] (0..1).
  Map<String, BonePose> sample(double t) {
    final time = loop ? t % 1.0 : t.clamp(0.0, 1.0);
    final out = <String, BonePose>{};
    tracks.forEach((boneId, keys) {
      out[boneId] = _sampleTrack(keys, time);
    });
    return out;
  }

  BonePose _sampleTrack(List<BoneKeyframe> keys, double t) {
    if (keys.isEmpty) return const BonePose();
    if (keys.length == 1) {
      return BonePose(rotation: keys.first.rotation, offset: keys.first.offset);
    }
    BoneKeyframe a = keys.first;
    BoneKeyframe b = keys.last;
    if (t <= keys.first.t) {
      if (loop) {
        // wrap from the last key back to the first
        a = keys.last;
        b = keys.first;
        final span = (1.0 - a.t) + b.t;
        final u = span <= 0 ? 0.0 : ((1.0 - a.t) + t) / span;
        return _blend(a, b, u);
      }
      return BonePose(rotation: a.rotation, offset: a.offset);
    }
    if (t >= keys.last.t) {
      if (loop) {
        a = keys.last;
        b = keys.first;
        final span = (1.0 - a.t) + b.t;
        final u = span <= 0 ? 0.0 : (t - a.t) / span;
        return _blend(a, b, u);
      }
      return BonePose(rotation: b.rotation, offset: b.offset);
    }
    for (var i = 0; i < keys.length - 1; i++) {
      if (t >= keys[i].t && t <= keys[i + 1].t) {
        a = keys[i];
        b = keys[i + 1];
        final span = b.t - a.t;
        final u = span <= 0 ? 0.0 : (t - a.t) / span;
        return _blend(a, b, u);
      }
    }
    return BonePose(rotation: b.rotation, offset: b.offset);
  }

  BonePose _blend(BoneKeyframe a, BoneKeyframe b, double u) {
    final e = smooth ? u * u * (3 - 2 * u) : u; // smoothstep
    return BonePose(
      rotation: a.rotation + (b.rotation - a.rotation) * e,
      offset: Offset.lerp(a.offset, b.offset, e) ?? Offset.zero,
    );
  }
}
