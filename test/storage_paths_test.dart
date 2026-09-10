import 'dart:convert';
import 'dart:io';
import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:path/path.dart' as p;
import 'package:rigstudio/data/standard_rig.dart';
import 'package:rigstudio/models/character.dart';
import 'package:rigstudio/services/storage_service.dart';

void main() {
  late Directory root;
  late Directory oldRoot; // simulates the container path BEFORE an app update

  setUp(() async {
    root = await Directory.systemTemp.createTemp('rigstudio_root_');
    oldRoot = await Directory.systemTemp.createTemp('rigstudio_old_');
    StorageService.instance.debugSetRoot(root);
  });

  tearDown(() async {
    StorageService.instance.debugSetRoot(null);
    try {
      await root.delete(recursive: true);
    } catch (_) {}
    try {
      await oldRoot.delete(recursive: true);
    } catch (_) {}
  });

  /// A character with real files written under the CURRENT root, one bone cut.
  Future<Character> characterWithFiles(String id) async {
    final png = img.encodePng(img.Image(width: 4, height: 4));
    final src = await StorageService.instance.writeSourceImage(id, png);
    final thumb = await StorageService.instance.writeThumbnail(id, png);
    final skeleton = buildSkeletonFromTemplate(
      characterId: id,
      canvasSize: const Size(800, 1200),
      transform: RigTemplateTransform.fitTo(const Size(800, 1200)),
    );
    final torso = skeleton.byId('torso')!;
    torso.imagePath = await StorageService.instance.writePart(id, 'torso', png);
    torso.imageRect = const Rect.fromLTWH(10, 20, 30, 40);
    return Character(
      id: id,
      name: 'Test $id',
      sourceImagePath: src,
      thumbnailPath: thumb,
      skeleton: skeleton,
      createdAt: DateTime.now(),
      updatedAt: DateTime.now(),
    );
  }

  group('path storage contract', () {
    test('character.json stores app-relative paths', () async {
      final c = await characterWithFiles('rel1');
      await StorageService.instance.saveCharacter(c);

      final raw = jsonDecode(await File(
              p.join(root.path, 'characters', c.id, 'character.json'))
          .readAsString()) as Map<String, dynamic>;
      expect(raw['sourceImagePath'], 'characters/rel1/source.png');
      expect(raw['thumbnailPath'], 'characters/rel1/thumb.png');
      final bones = (raw['skeleton'] as Map)['bones'] as List;
      final torso = bones.firstWhere((b) => b['id'] == 'torso') as Map;
      expect(torso['imagePath'], 'characters/rel1/parts/torso.png');
    });

    test('loadLibrary resolves every path onto the current root', () async {
      final c = await characterWithFiles('rel2');
      await StorageService.instance.saveCharacter(c);

      final lib = await StorageService.instance.loadLibrary();
      final loaded = lib.singleWhere((x) => x.id == 'rel2');
      expect(p.isAbsolute(loaded.sourceImagePath), isTrue);
      expect(File(loaded.sourceImagePath).existsSync(), isTrue);
      expect(File(loaded.thumbnailPath!).existsSync(), isTrue);
      final torso = loaded.skeleton!.byId('torso')!;
      expect(File(torso.imagePath!).existsSync(), isTrue);
    });
  });

  group('legacy installs (absolute paths in character.json)', () {
    test('paths are rebased after the container moved (iOS app update)',
        () async {
      // Build a character whose files live under the CURRENT root…
      final c = await characterWithFiles('legacy1');
      // …but save it the way the OLD app version did: absolute paths pointing
      // at the pre-update container.
      final j = c.toJson()
        ..['sourceImagePath'] =
            p.join(oldRoot.path, 'characters', 'legacy1', 'source.png')
        ..['thumbnailPath'] =
            p.join(oldRoot.path, 'characters', 'legacy1', 'thumb.png');
      final sk = j['skeleton'] as Map<String, dynamic>;
      for (final b in (sk['bones'] as List).cast<Map<String, dynamic>>()) {
        if (b['imagePath'] != null) {
          b['imagePath'] = p.join(
              oldRoot.path, 'characters', 'legacy1', 'parts', 'torso.png');
        }
      }
      await File(p.join(root.path, 'characters', 'legacy1', 'character.json'))
          .writeAsString(jsonEncode(j));

      final lib = await StorageService.instance.loadLibrary();
      final loaded = lib.singleWhere((x) => x.id == 'legacy1');
      // Rebased onto the CURRENT root, and the files are found again.
      expect(loaded.sourceImagePath.startsWith(root.path), isTrue,
          reason: 'path was not rebased: ${loaded.sourceImagePath}');
      expect(File(loaded.sourceImagePath).existsSync(), isTrue);
      expect(File(loaded.skeleton!.byId('torso')!.imagePath!).existsSync(),
          isTrue);
    });

    test('already-relative paths still load after a root move', () async {
      final c = await characterWithFiles('legacy2');
      await StorageService.instance.saveCharacter(c);

      // New root = the same files copied elsewhere (fresh install restore).
      final newRoot = await Directory.systemTemp.createTemp('rigstudio_new_');
      try {
        await _copyTree(
            Directory(p.join(root.path, 'characters')),
            Directory(p.join(newRoot.path, 'characters')));
        StorageService.instance.debugSetRoot(newRoot);

        final lib = await StorageService.instance.loadLibrary();
        final loaded = lib.singleWhere((x) => x.id == 'legacy2');
        expect(File(loaded.sourceImagePath).existsSync(), isTrue);
        expect(File(loaded.skeleton!.byId('torso')!.imagePath!).existsSync(),
            isTrue);
      } finally {
        StorageService.instance.debugSetRoot(root);
        try {
          await newRoot.delete(recursive: true);
        } catch (_) {}
      }
    });
  });

  test('relativize/absolutize round-trips a path outside the app folders',
      () {
    // Unknown absolute paths must pass through untouched (never mangled).
    const external = '/sdcard/DCIM/something.png';
    expect(StorageService.relativizePath(external, root.path), external);
    expect(StorageService.absolutizePath(external, root.path), external);

    // Relative forms resolve against whatever the current root is.
    expect(StorageService.absolutizePath('characters/a/b.png', root.path),
        p.join(root.path, 'characters', 'a', 'b.png'));
  });
}

Future<void> _copyTree(Directory src, Directory dst) async {
  await dst.create(recursive: true);
  await for (final e in src.list()) {
    if (e is Directory) {
      await _copyTree(e, Directory(p.join(dst.path, p.basename(e.path))));
    } else if (e is File) {
      await e.copy(p.join(dst.path, p.basename(e.path)));
    }
  }
}
