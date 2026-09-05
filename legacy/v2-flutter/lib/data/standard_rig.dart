import 'dart:math' as math;
import 'dart:ui';

import '../models/bone_part.dart';
import '../models/skeleton.dart';

/// ---------------------------------------------------------------------------
/// The STANDARD SKELETON.
///
/// Both rigging paths (template auto-crop and manual lasso) produce bones with
/// these exact ids, which is why every shipped [AnimationClip] plays on every
/// rigged character without any per-character mapping.
///
/// Side convention: `_l` / `_r` are **screen** left / right of a front-facing
/// character. The whole-rig mirror button swaps them.
/// ---------------------------------------------------------------------------

/// Region shape used when auto-cropping a bone out of the source image.
enum RegionShape { capsule, ellipse, roundedRect }

class TemplateBone {
  const TemplateBone({
    required this.id,
    required this.parentId,
    required this.label,
    required this.start,
    required this.end,
    required this.thickness,
    this.shape = RegionShape.capsule,
    this.zIndex = 0,
    this.required_ = true,
  });

  final String id;
  final String? parentId;
  final String label;

  /// Joint (pivot) position in normalised template space: x,y in 0..1 where the
  /// box is the character's bounding box, y down.
  final Offset start;

  /// Far end of the bone in the same space.
  final Offset end;

  /// Region width as a fraction of the template height.
  final double thickness;

  final RegionShape shape;
  final int zIndex;
  // ignore: non_constant_identifier_names
  final bool required_;

  Offset get mid => Offset((start.dx + end.dx) / 2, (start.dy + end.dy) / 2);
  double get length => (end - start).distance;
}

/// Front-facing "T-ish/A-pose" proportions of a generic humanoid.
/// Tuned so that the auto-crop capsules land on a typical standing character.
const List<TemplateBone> kStandardRig = [
  TemplateBone(
    id: 'torso',
    parentId: null,
    label: 'Torso',
    start: Offset(0.500, 0.620), // pelvis = root pivot
    end: Offset(0.500, 0.300), // neck
    thickness: 0.230,
    shape: RegionShape.roundedRect,
    zIndex: 10,
  ),
  TemplateBone(
    id: 'head',
    parentId: 'torso',
    label: 'Head',
    start: Offset(0.500, 0.300), // neck joint
    end: Offset(0.500, 0.075),
    thickness: 0.215,
    shape: RegionShape.ellipse,
    zIndex: 40,
  ),
  // --- screen-left arm ---
  TemplateBone(
    id: 'upper_arm_l',
    parentId: 'torso',
    label: 'Left upper arm',
    start: Offset(0.395, 0.345),
    end: Offset(0.335, 0.475),
    thickness: 0.085,
    zIndex: 30,
  ),
  TemplateBone(
    id: 'forearm_l',
    parentId: 'upper_arm_l',
    label: 'Left forearm',
    start: Offset(0.335, 0.475),
    end: Offset(0.300, 0.605),
    thickness: 0.072,
    zIndex: 31,
  ),
  TemplateBone(
    id: 'hand_l',
    parentId: 'forearm_l',
    label: 'Left hand',
    start: Offset(0.300, 0.605),
    end: Offset(0.290, 0.665),
    thickness: 0.080,
    shape: RegionShape.ellipse,
    zIndex: 32,
    required_: false,
  ),
  // --- screen-right arm ---
  TemplateBone(
    id: 'upper_arm_r',
    parentId: 'torso',
    label: 'Right upper arm',
    start: Offset(0.605, 0.345),
    end: Offset(0.665, 0.475),
    thickness: 0.085,
    zIndex: 20,
  ),
  TemplateBone(
    id: 'forearm_r',
    parentId: 'upper_arm_r',
    label: 'Right forearm',
    start: Offset(0.665, 0.475),
    end: Offset(0.700, 0.605),
    thickness: 0.072,
    zIndex: 21,
  ),
  TemplateBone(
    id: 'hand_r',
    parentId: 'forearm_r',
    label: 'Right hand',
    start: Offset(0.700, 0.605),
    end: Offset(0.710, 0.665),
    thickness: 0.080,
    shape: RegionShape.ellipse,
    zIndex: 22,
    required_: false,
  ),
  // --- legs ---
  TemplateBone(
    id: 'thigh_l',
    parentId: 'torso',
    label: 'Left thigh',
    start: Offset(0.452, 0.615),
    end: Offset(0.438, 0.790),
    thickness: 0.105,
    zIndex: 8,
  ),
  TemplateBone(
    id: 'shin_l',
    parentId: 'thigh_l',
    label: 'Left shin',
    start: Offset(0.438, 0.790),
    end: Offset(0.430, 0.945),
    thickness: 0.085,
    zIndex: 7,
  ),
  TemplateBone(
    id: 'foot_l',
    parentId: 'shin_l',
    label: 'Left foot',
    start: Offset(0.430, 0.945),
    end: Offset(0.395, 0.990),
    thickness: 0.080,
    shape: RegionShape.roundedRect,
    zIndex: 6,
    required_: false,
  ),
  TemplateBone(
    id: 'thigh_r',
    parentId: 'torso',
    label: 'Right thigh',
    start: Offset(0.548, 0.615),
    end: Offset(0.562, 0.790),
    thickness: 0.105,
    zIndex: 5,
  ),
  TemplateBone(
    id: 'shin_r',
    parentId: 'thigh_r',
    label: 'Right shin',
    start: Offset(0.562, 0.790),
    end: Offset(0.570, 0.945),
    thickness: 0.085,
    zIndex: 4,
  ),
  TemplateBone(
    id: 'foot_r',
    parentId: 'shin_r',
    label: 'Right foot',
    start: Offset(0.570, 0.945),
    end: Offset(0.605, 0.990),
    thickness: 0.080,
    shape: RegionShape.roundedRect,
    zIndex: 3,
    required_: false,
  ),
];

TemplateBone templateBoneById(String id) =>
    kStandardRig.firstWhere((b) => b.id == id);

const List<String> kBoneIds = [
  'torso',
  'head',
  'upper_arm_l',
  'forearm_l',
  'hand_l',
  'upper_arm_r',
  'forearm_r',
  'hand_r',
  'thigh_l',
  'shin_l',
  'foot_l',
  'thigh_r',
  'shin_r',
  'foot_r',
];

/// User-placed transform of the whole template over the source image.
/// Maps normalised template space -> canvas (image pixel) space.
class RigTemplateTransform {
  RigTemplateTransform({
    required this.center,
    required this.scale,
    this.rotation = 0,
    Map<String, Offset>? jointTweaks,
  }) : jointTweaks = jointTweaks ?? <String, Offset>{};

  /// Where template point (0.5, 0.5) lands, in canvas pixels.
  Offset center;

  /// Template height in canvas pixels.
  double scale;

  /// Whole-rig rotation in radians.
  double rotation;

  /// Per-joint fine tuning, canvas-space deltas applied after the global map.
  final Map<String, Offset> jointTweaks;

  /// A sensible starting placement for an image of [imageSize].
  factory RigTemplateTransform.fitTo(Size imageSize) => RigTemplateTransform(
        center: Offset(imageSize.width / 2, imageSize.height / 2),
        scale: imageSize.height * 0.92,
      );

  Offset mapPoint(Offset normalised) {
    final v = Offset(
      (normalised.dx - 0.5) * scale * _aspect,
      (normalised.dy - 0.5) * scale,
    );
    final c = math.cos(rotation), s = math.sin(rotation);
    return center + Offset(v.dx * c - v.dy * s, v.dx * s + v.dy * c);
  }

  /// The template box is 1.0 tall; this keeps limbs from getting squashed.
  static const double _aspect = 1.0;

  /// Joint position in canvas space, including its fine-tune tweak.
  Offset joint(String boneId, {bool isEnd = false}) {
    final tb = templateBoneById(boneId);
    final base = mapPoint(isEnd ? tb.end : tb.start);
    final key = isEnd ? '$boneId#end' : boneId;
    return base + (jointTweaks[key] ?? Offset.zero);
  }

  double thicknessPx(TemplateBone b) => b.thickness * scale;

  RigTemplateTransform clone() => RigTemplateTransform(
        center: center,
        scale: scale,
        rotation: rotation,
        jointTweaks: Map<String, Offset>.from(jointTweaks),
      );
}

/// Builds an *uncut* skeleton (pivots placed, no bitmaps yet) from a template
/// placement. Auto-crop then fills in `imagePath` / `imageRect` per bone.
Skeleton buildSkeletonFromTemplate({
  required String characterId,
  required Size canvasSize,
  required RigTemplateTransform transform,
}) {
  final bones = <BonePart>[];
  for (final tb in kStandardRig) {
    final lim = defaultAngleLimits(tb.id);
    bones.add(BonePart(
      id: tb.id,
      parentId: tb.parentId,
      label: tb.label,
      pivot: transform.joint(tb.id),
      zIndex: tb.zIndex,
      required_: tb.required_,
      minAngleRad: lim.$1,
      maxAngleRad: lim.$2,
    ));
  }
  return Skeleton(characterId: characterId, bones: bones, canvasSize: canvasSize);
}

/// Default rotation limits (radians, clockwise-positive) per standard bone.
///
/// Deliberately wide enough that every shipped [AnimationClip] stays inside them
/// (asserted in a unit test), but bounded so a user keyframe or live pose drag
/// can't spin a limb through >~170° and visually "break" the joint. `torso` is
/// left unbounded because the sleep clip lays the whole body down (−90°).
const double _deg = 0.017453292519943295; // one degree in radians

(double, double) defaultAngleLimits(String id) => switch (id) {
      'torso' => (-double.infinity, double.infinity),
      'head' => (-80 * _deg, 80 * _deg),
      'upper_arm_l' => (-170 * _deg, 170 * _deg),
      'upper_arm_r' => (-170 * _deg, 170 * _deg),
      'forearm_l' => (-170 * _deg, 170 * _deg),
      'forearm_r' => (-170 * _deg, 170 * _deg),
      'hand_l' => (-100 * _deg, 100 * _deg),
      'hand_r' => (-100 * _deg, 100 * _deg),
      'thigh_l' => (-110 * _deg, 110 * _deg),
      'thigh_r' => (-110 * _deg, 110 * _deg),
      'shin_l' => (-170 * _deg, 170 * _deg),
      'shin_r' => (-170 * _deg, 170 * _deg),
      'foot_l' => (-45 * _deg, 45 * _deg),
      'foot_r' => (-45 * _deg, 45 * _deg),
      _ => (-double.infinity, double.infinity),
    };
