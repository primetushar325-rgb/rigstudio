import 'dart:ui';

import 'bone_part.dart';
import 'geom_json.dart';

/// A flat list of [BonePart]s whose hierarchy is resolved through `parentId`.
class Skeleton {
  Skeleton({
    required this.characterId,
    required this.bones,
    required this.canvasSize,
    this.rigMirrored = false,
  });

  final String characterId;
  final List<BonePart> bones;
  final Size canvasSize;

  /// Whole-rig left/right flip (see [mirroredRig]).
  final bool rigMirrored;

  BonePart? byId(String id) {
    for (final b in bones) {
      if (b.id == id) return b;
    }
    return null;
  }

  Iterable<BonePart> childrenOf(String? id) => bones.where((b) => b.parentId == id);

  BonePart get root => bones.firstWhere((b) => b.parentId == null);

  /// Parents always come before children — the order FK evaluation needs.
  List<BonePart> get topologicalOrder {
    final out = <BonePart>[];
    void walk(String? parentId) {
      for (final b in bones.where((b) => b.parentId == parentId)) {
        out.add(b);
        walk(b.id);
      }
    }

    walk(null);
    // Defensive: append orphans (a parent id that no longer exists).
    for (final b in bones) {
      if (!out.contains(b)) out.add(b);
    }
    return out;
  }

  List<BonePart> get drawOrder {
    final list = [...bones]..sort((a, b) => a.zIndex.compareTo(b.zIndex));
    return list;
  }

  bool get isComplete => bones.where((b) => b.required_).every((b) => b.isCut);

  List<BonePart> get missingParts =>
      bones.where((b) => b.required_ && !b.isCut).toList();

  Skeleton copyWith({List<BonePart>? bones, Size? canvasSize, bool? rigMirrored}) =>
      Skeleton(
        characterId: characterId,
        bones: bones ?? this.bones,
        canvasSize: canvasSize ?? this.canvasSize,
        rigMirrored: rigMirrored ?? this.rigMirrored,
      );

  /// Whole-rig mirror: reflects every pivot/rect about the canvas centre line
  /// and swaps the `_l` / `_r` assignments so animations keep making sense.
  Skeleton mirroredRig() {
    final cx = canvasSize.width / 2;
    Offset flipP(Offset p) => Offset(2 * cx - p.dx, p.dy);
    Rect flipR(Rect r) => Rect.fromLTWH(2 * cx - r.right, r.top, r.width, r.height);

    // 1. geometry flip, keeping each bone's own bitmap
    final flipped = <String, BonePart>{};
    for (final b in bones) {
      flipped[b.id] = b.copyWith(
        pivot: flipP(b.pivot),
        imageRect: flipR(b.imageRect),
        mirrored: !b.mirrored,
      );
    }

    // 2. swap left/right payloads (bitmap + geometry) between mirrored bones
    final result = <BonePart>[];
    for (final b in bones) {
      final swapId = swappedSideId(b.id);
      final src = flipped[swapId] ?? flipped[b.id]!;
      result.add(BonePart(
        id: b.id,
        parentId: b.parentId,
        label: b.label,
        pivot: src.pivot,
        imagePath: src.imagePath,
        imageRect: src.imageRect,
        zIndex: (flipped[swapId] ?? flipped[b.id]!).zIndex,
        mirrored: src.mirrored,
        visible: src.visible,
        required_: b.required_,
      ));
    }
    return copyWith(bones: result, rigMirrored: !rigMirrored);
  }

  /// `upper_arm_l` <-> `upper_arm_r`, everything else unchanged.
  static String swappedSideId(String id) {
    if (id.endsWith('_l')) return '${id.substring(0, id.length - 2)}_r';
    if (id.endsWith('_r')) return '${id.substring(0, id.length - 2)}_l';
    return id;
  }

  Map<String, dynamic> toJson() => {
        'characterId': characterId,
        'canvasSize': sizeToJson(canvasSize),
        'rigMirrored': rigMirrored,
        'bones': bones.map((b) => b.toJson()).toList(),
      };

  factory Skeleton.fromJson(Map<String, dynamic> j) => Skeleton(
        characterId: j['characterId'] as String,
        canvasSize: sizeFromJson((j['canvasSize'] as Map).cast<String, dynamic>()),
        rigMirrored: j['rigMirrored'] as bool? ?? false,
        bones: (j['bones'] as List)
            .map((e) => BonePart.fromJson((e as Map).cast<String, dynamic>()))
            .toList(),
      );
}
