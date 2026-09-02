import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:image/image.dart' as img;
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import '../models/character.dart';

/// ---------------------------------------------------------------------------
/// On-device persistence. Layout under the app documents directory:
///
///   `characters/[id]/source.png`        original import
///   `characters/[id]/working.png`       chroma-keyed version (optional)
///   `characters/[id]/thumb.png`
///   `characters/[id]/parts/[bone].png`  one cut-out per bone
///   `characters/[id]/character.json`    Character + Skeleton
///   settings.json                     app settings (premium flag, defaults)
///
/// Paths stored in JSON are *relative* to the documents dir, because iOS
/// changes the sandbox container path between installs.
/// ---------------------------------------------------------------------------
class StorageService {
  StorageService._();
  static final StorageService instance = StorageService._();

  Directory? _root;

  Future<Directory> get root async =>
      _root ??= await getApplicationDocumentsDirectory();

  Future<String> absolute(String relative) async =>
      p.join((await root).path, relative);

  String relative(String absolutePath, String rootPath) =>
      p.relative(absolutePath, from: rootPath);

  Future<Directory> characterDir(String id) async {
    final dir = Directory(p.join((await root).path, 'characters', id));
    if (!dir.existsSync()) dir.createSync(recursive: true);
    final parts = Directory(p.join(dir.path, 'parts'));
    if (!parts.existsSync()) parts.createSync(recursive: true);
    return dir;
  }

  // --------------------------------------------------------------- characters

  Future<List<Character>> loadLibrary() async {
    final dir = Directory(p.join((await root).path, 'characters'));
    if (!dir.existsSync()) return [];
    final out = <Character>[];
    for (final entity in dir.listSync().whereType<Directory>()) {
      final f = File(p.join(entity.path, 'character.json'));
      if (!f.existsSync()) continue;
      try {
        final json = jsonDecode(await f.readAsString()) as Map<String, dynamic>;
        out.add(Character.fromJson(json));
      } catch (_) {
        // corrupt entry — skip rather than blocking the whole library
      }
    }
    out.sort((a, b) => b.updatedAt.compareTo(a.updatedAt));
    return out;
  }

  Future<void> saveCharacter(Character c) async {
    final dir = await characterDir(c.id);
    c.updatedAt = DateTime.now();
    await File(p.join(dir.path, 'character.json'))
        .writeAsString(jsonEncode(c.toJson()));
  }

  Future<void> deleteCharacter(String id) async {
    final dir = Directory(p.join((await root).path, 'characters', id));
    if (dir.existsSync()) dir.deleteSync(recursive: true);
  }

  /// Copies an imported file into the character folder as `source.png`.
  Future<String> writeSourceImage(String characterId, Uint8List bytes) async {
    final dir = await characterDir(characterId);
    final path = p.join(dir.path, 'source.png');
    await File(path).writeAsBytes(_ensurePng(bytes), flush: true);
    return path;
  }

  Future<String> writeWorkingImage(String characterId, Uint8List pngBytes) async {
    final dir = await characterDir(characterId);
    final path = p.join(dir.path, 'working.png');
    await File(path).writeAsBytes(pngBytes, flush: true);
    return path;
  }

  Future<String> writePart(String characterId, String boneId, Uint8List pngBytes) async {
    final dir = await characterDir(characterId);
    final path = p.join(dir.path, 'parts', '$boneId.png');
    await File(path).writeAsBytes(pngBytes, flush: true);
    return path;
  }

  Future<String> writeThumbnail(String characterId, Uint8List pngBytes) async {
    final dir = await characterDir(characterId);
    final path = p.join(dir.path, 'thumb.png');
    final decoded = img.decodeImage(pngBytes);
    final small = decoded == null
        ? pngBytes
        : img.encodePng(img.copyResize(decoded,
            width: decoded.width >= decoded.height ? 320 : null,
            height: decoded.height > decoded.width ? 320 : null));
    await File(path).writeAsBytes(small, flush: true);
    return path;
  }

  /// Exports live in their own folder so the gallery/share sheet can find them.
  Future<Directory> exportDir() async {
    final dir = Directory(p.join((await root).path, 'exports'));
    if (!dir.existsSync()) dir.createSync(recursive: true);
    return dir;
  }

  // ----------------------------------------------------------------- settings

  Future<Map<String, dynamic>> loadSettings() async {
    final f = File(p.join((await root).path, 'settings.json'));
    if (!f.existsSync()) return {};
    try {
      return (jsonDecode(await f.readAsString()) as Map).cast<String, dynamic>();
    } catch (_) {
      return {};
    }
  }

  Future<void> saveSettings(Map<String, dynamic> settings) async {
    final f = File(p.join((await root).path, 'settings.json'));
    await f.writeAsString(jsonEncode(settings));
  }

  // -------------------------------------------------------------------- utils

  Uint8List _ensurePng(Uint8List bytes) {
    // JPEG/HEIC from the gallery gets normalised to PNG so every later stage
    // can assume 8-bit RGBA.
    if (bytes.length > 8 &&
        bytes[0] == 0x89 &&
        bytes[1] == 0x50 &&
        bytes[2] == 0x4E &&
        bytes[3] == 0x47) {
      return bytes;
    }
    final decoded = img.decodeImage(bytes);
    if (decoded == null) return bytes;
    return img.encodePng(decoded);
  }

  static Future<ui.Image> decodeUiImage(Uint8List bytes) async {
    final codec = await ui.instantiateImageCodec(bytes);
    final frame = await codec.getNextFrame();
    return frame.image;
  }

  static Future<ui.Image?> loadUiImageFile(String path) async {
    final f = File(path);
    if (!f.existsSync()) return null;
    return decodeUiImage(await f.readAsBytes());
  }
}
