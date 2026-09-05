import 'skeleton.dart';

/// One saved character: the imported artwork plus (optionally) its rig.
class Character {
  Character({
    required this.id,
    required this.name,
    required this.sourceImagePath,
    this.workingImagePath,
    this.thumbnailPath,
    this.skeleton,
    required this.createdAt,
    required this.updatedAt,
    this.lastClip = 'idle',
  });

  final String id;
  String name;

  /// Original picked image (never modified).
  String sourceImagePath;

  /// Post-chroma-key transparent PNG that parts get cut from.
  /// Falls back to [sourceImagePath] when the user skipped background removal.
  String? workingImagePath;

  String? thumbnailPath;
  Skeleton? skeleton;

  final DateTime createdAt;
  DateTime updatedAt;
  String lastClip;

  String get cutSource => workingImagePath ?? sourceImagePath;

  bool get isRigged => skeleton != null && skeleton!.isComplete;

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'sourceImagePath': sourceImagePath,
        'workingImagePath': workingImagePath,
        'thumbnailPath': thumbnailPath,
        'skeleton': skeleton?.toJson(),
        'createdAt': createdAt.toIso8601String(),
        'updatedAt': updatedAt.toIso8601String(),
        'lastClip': lastClip,
      };

  factory Character.fromJson(Map<String, dynamic> j) => Character(
        id: j['id'] as String,
        name: j['name'] as String? ?? 'Character',
        sourceImagePath: j['sourceImagePath'] as String,
        workingImagePath: j['workingImagePath'] as String?,
        thumbnailPath: j['thumbnailPath'] as String?,
        skeleton: j['skeleton'] == null
            ? null
            : Skeleton.fromJson((j['skeleton'] as Map).cast<String, dynamic>()),
        createdAt: DateTime.tryParse(j['createdAt'] as String? ?? '') ?? DateTime.now(),
        updatedAt: DateTime.tryParse(j['updatedAt'] as String? ?? '') ?? DateTime.now(),
        lastClip: j['lastClip'] as String? ?? 'idle',
      );
}
