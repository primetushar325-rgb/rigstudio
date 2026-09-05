import 'dart:ui';

import 'geom_json.dart';

/// A decorative/held item (hat, glasses, stick, bag, phone, …) that rides on a
/// specific bone. Props are cosmetic — the bone rotation limits do NOT apply to
/// them. They inherit the bone's full world transform at render time, so a stick
/// attached to `hand_r` swings with the arm for free.
class PropAttachment {
  PropAttachment({
    required this.id,
    required this.imagePath,
    required this.attachedBoneId,
    this.localOffset = Offset.zero,
    this.localRotation = 0,
    this.scale = 1.0,
    this.zIndex = 50,
    this.visible = true,
    this.mirrored = false,
    this.label = '',
  });

  final String id;
  String label;

  /// Absolute path to the transparent PNG.
  String imagePath;

  /// Standard bone id it follows, e.g. `hand_r`, `head`, `torso`.
  String attachedBoneId;

  /// Position relative to the bone's pivot, in canvas pixels. (Bone pivot is the
  /// anchor this prop's local frame rotates/scales around.)
  Offset localOffset;

  /// Radians, relative to the bone.
  double localRotation;

  double scale;

  /// Draw order relative to body parts (body parts use [BonePart.zIndex]).
  int zIndex;

  bool visible;

  /// Flip the prop's bitmap left-right (set automatically on whole-rig mirror).
  bool mirrored;

  PropAttachment copyWith({
    String? label,
    String? imagePath,
    String? attachedBoneId,
    Offset? localOffset,
    double? localRotation,
    double? scale,
    int? zIndex,
    bool? visible,
    bool? mirrored,
  }) =>
      PropAttachment(
        id: id,
        label: label ?? this.label,
        imagePath: imagePath ?? this.imagePath,
        attachedBoneId: attachedBoneId ?? this.attachedBoneId,
        localOffset: localOffset ?? this.localOffset,
        localRotation: localRotation ?? this.localRotation,
        scale: scale ?? this.scale,
        zIndex: zIndex ?? this.zIndex,
        visible: visible ?? this.visible,
        mirrored: mirrored ?? this.mirrored,
      );

  /// For the prop image this is the canvas-space centre the bitmap occupies
  /// when drawn (width/height come from the decoded image). Used by painting.
  Map<String, dynamic> toJson() => {
        'id': id,
        'label': label,
        'imagePath': imagePath,
        'attachedBoneId': attachedBoneId,
        'localOffset': offsetToJson(localOffset),
        'localRotation': localRotation,
        'scale': scale,
        'zIndex': zIndex,
        'visible': visible,
        'mirrored': mirrored,
      };

  factory PropAttachment.fromJson(Map<String, dynamic> j) => PropAttachment(
        id: j['id'] as String,
        label: j['label'] as String? ?? '',
        imagePath: j['imagePath'] as String,
        attachedBoneId: j['attachedBoneId'] as String,
        localOffset:
            offsetFromJson((j['localOffset'] as Map?)?.cast<String, dynamic>()),
        localRotation: (j['localRotation'] as num?)?.toDouble() ?? 0,
        scale: (j['scale'] as num?)?.toDouble() ?? 1,
        zIndex: (j['zIndex'] as num?)?.toInt() ?? 50,
        visible: j['visible'] as bool? ?? true,
        mirrored: j['mirrored'] as bool? ?? false,
      );
}
