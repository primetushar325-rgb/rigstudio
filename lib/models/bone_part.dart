import 'dart:ui';

import 'geom_json.dart';

/// A single rigged body part: one cropped bitmap bound to one bone of the
/// skeleton. Everything is expressed in *canvas space* — the coordinate system
/// of the source character image (pixels of [Skeleton.canvasSize]).
class BonePart {
  BonePart({
    required this.id,
    required this.parentId,
    required this.label,
    required this.pivot,
    this.imagePath,
    this.imageRect = Rect.zero,
    this.zIndex = 0,
    this.rotation = 0,
    this.translation = Offset.zero,
    this.mirrored = false,
    this.visible = true,
    this.required_ = true,
  });

  /// Standard bone id, e.g. `upper_arm_l`. Animation clips key off this.
  final String id;

  /// Parent bone id; `null` for the root bone (`torso`).
  final String? parentId;

  /// User-facing name, e.g. "Left upper arm".
  final String label;

  /// Rotation anchor (the joint) in canvas space.
  Offset pivot;

  /// Cropped, transparent PNG for this part. Null until the part is cut.
  String? imagePath;

  /// Where [imagePath] sits in canvas space at rest.
  Rect imageRect;

  /// Draw order, higher draws on top.
  int zIndex;

  /// Live pose values (written by the animation player / manual posing).
  double rotation;
  Offset translation;

  /// Flip just this part's bitmap around its pivot's vertical axis.
  bool mirrored;

  bool visible;

  /// Whether the rig is considered incomplete without this part.
  // ignore: non_constant_identifier_names
  final bool required_;

  bool get isCut => imagePath != null;

  BonePart copyWith({
    Offset? pivot,
    String? imagePath,
    bool clearImagePath = false,
    Rect? imageRect,
    int? zIndex,
    double? rotation,
    Offset? translation,
    bool? mirrored,
    bool? visible,
  }) {
    return BonePart(
      id: id,
      parentId: parentId,
      label: label,
      pivot: pivot ?? this.pivot,
      imagePath: clearImagePath ? null : (imagePath ?? this.imagePath),
      imageRect: imageRect ?? this.imageRect,
      zIndex: zIndex ?? this.zIndex,
      rotation: rotation ?? this.rotation,
      translation: translation ?? this.translation,
      mirrored: mirrored ?? this.mirrored,
      visible: visible ?? this.visible,
      required_: required_,
    );
  }

  Map<String, dynamic> toJson() => {
        'id': id,
        'parentId': parentId,
        'label': label,
        'pivot': offsetToJson(pivot),
        'imagePath': imagePath,
        'imageRect': rectToJson(imageRect),
        'zIndex': zIndex,
        'mirrored': mirrored,
        'visible': visible,
        'required': required_,
      };

  factory BonePart.fromJson(Map<String, dynamic> j) => BonePart(
        id: j['id'] as String,
        parentId: j['parentId'] as String?,
        label: j['label'] as String? ?? j['id'] as String,
        pivot: offsetFromJson((j['pivot'] as Map).cast<String, dynamic>()),
        imagePath: j['imagePath'] as String?,
        imageRect: rectFromJson((j['imageRect'] as Map?)?.cast<String, dynamic>()),
        zIndex: (j['zIndex'] as num?)?.toInt() ?? 0,
        mirrored: j['mirrored'] as bool? ?? false,
        visible: j['visible'] as bool? ?? true,
        required_: j['required'] as bool? ?? true,
      );
}
