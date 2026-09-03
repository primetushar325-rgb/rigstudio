import 'package:vector_math/vector_math_64.dart' show Matrix4;

/// Which horizontal way the character faces. `_l`/`_r` bone ids and authored
/// clip directions are always authored for a front-facing, **right**-facing
/// character; flipping facing mirrors the whole rig about its horizontal
/// centre so the character walks the other way without re-authoring anything.
enum FacingDirection { right, left }

extension FacingDirectionX on FacingDirection {
  /// +1 when facing right (the authored direction), −1 when facing left.
  double get xSign => this == FacingDirection.right ? 1.0 : -1.0;

  FacingDirection get flipped =>
      this == FacingDirection.right ? FacingDirection.left : FacingDirection.right;
}

/// Runtime "walk" controls shared by live preview and export.
///
/// When [walking] and not [inPlace], the character is translated across the
/// canvas by [walkSpeed] (rig-canvas pixels per second) in the direction it
/// faces, so it visibly walks rather than treading air. [inPlace] keeps it
/// centred (for seamless loops / looping backgrounds). The facing and the
/// movement sign are always driven from the same [facing] so they can never
/// disagree.
class PlaybackMotion {
  PlaybackMotion({
    this.facing = FacingDirection.right,
    this.walking = false,
    this.inPlace = true,
    this.walkSpeed = 140,
    this.wrap = true,
  });

  FacingDirection facing;
  bool walking;
  bool inPlace;
  double walkSpeed;

  /// When true the character wraps from one side of the travel window back to
  /// the other so a "walk across" can keep looping.
  bool wrap;

  bool get moving => walking && !inPlace && walkSpeed > 0;

  /// Horizontal canvas-space translation after [elapsed] seconds.
  double horizontalOffset(double elapsed) {
    if (!moving) return 0;
    // Raw signed distance travelled.
    final d = elapsed * walkSpeed * facing.xSign;
    // If we're on a phone there is no real floor; wrap at a window slightly
    // wider than the rig so it exits one edge and re-enters the other.
    if (wrap) {
      // Travel window is expressed by the caller via [windowWidth]. Fall back to
      // 1.6x rig by storing nothing here — see [wrapOffset] below.
      return d;
    }
    return d;
  }

  /// Wraps a continuous [raw] horizontal offset into [windowWidth] so the
  /// character loops cleanly around the visible floor.
  static double wrapTo(double raw, double windowWidth) {
    if (windowWidth <= 0) return raw;
    var d = raw % windowWidth;
    if (d > windowWidth / 2) d -= windowWidth;
    return d;
  }
}

/// Builds the world-space root matrix for a posed rig so that
/// `world(bone) = root · local(bone)`. Pass it to `PoseSolver.solve(rootMatrix:)`.
///
/// * [translateX]: horizontal walk translation in canvas pixels.
/// * when [facing] == left, x is reflected about [centerX] (the character's
///   horizontal centre line) so the whole rig walks the other way.
Matrix4 playbackRootMatrix({
  required double centerX,
  required FacingDirection facing,
  required double translateX,
}) {
  var m = Matrix4.identity();
  if (facing == FacingDirection.left) {
    m = Matrix4.translationValues(centerX, 0, 0)
      ..multiply(Matrix4.diagonal3Values(-1, 1, 1))
      ..multiply(Matrix4.translationValues(-centerX, 0, 0));
  }
  if (translateX != 0) {
    m = m.multiplied(Matrix4.translationValues(translateX, 0, 0));
  }
  return m;
}
