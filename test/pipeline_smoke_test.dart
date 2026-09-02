import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rigstudio/data/animation_library.dart';
import 'package:rigstudio/data/standard_rig.dart';
import 'package:rigstudio/services/chroma_key_service.dart';
import 'package:rigstudio/services/cut_service.dart';
import 'package:rigstudio/services/demo_character.dart';
import 'package:rigstudio/services/export_service.dart';

/// End-to-end smoke test of the whole on-device pipeline, with no UI and no
/// device: demo character -> chroma key -> template auto-cut -> FK render ->
/// GIF encode. Artefacts land in `preview/` so you can eyeball them.
void main() {
  testWidgets('demo character rigs, animates and exports', (tester) async {
    await tester.runAsync(() async {
      final outDir = Directory('preview')..createSync(recursive: true);

      // 1. source artwork on a green screen
      final source = await DemoCharacter.generatePng();
      File('${outDir.path}/01_source.png').writeAsBytesSync(source);
      expect(source.length, greaterThan(1000));

      // 2. chroma key
      final keyed = await ChromaKeyService.run(
        ChromaKeyRequest(source, const ChromaKeyParams(tolerance: 0.22, feather: 0.10)),
      );
      File('${outDir.path}/02_keyed.png').writeAsBytesSync(keyed);

      // 3. standard template placement + auto-cut
      const canvas = DemoCharacter.size;
      final transform = RigTemplateTransform.fitTo(canvas);
      final skeleton = buildSkeletonFromTemplate(
        characterId: 'demo',
        canvasSize: canvas,
        transform: transform,
      );
      final cuts = await CutService.autoCropFromTemplate(
        imageBytes: keyed,
        transform: transform,
      );
      expect(cuts.length, kStandardRig.length,
          reason: 'every bone should produce a cut-out');

      final images = <String, ui.Image>{};
      final partsDir = Directory('${outDir.path}/parts')..createSync(recursive: true);
      for (final cut in cuts) {
        final path = '${partsDir.path}/${cut.boneId}.png';
        File(path).writeAsBytesSync(cut.pngBytes);
        final bone = skeleton.byId(cut.boneId)!;
        bone.imagePath = path;
        bone.imageRect = cut.rect;
        final codec = await ui.instantiateImageCodec(cut.pngBytes);
        images[cut.boneId] = (await codec.getNextFrame()).image;
      }
      expect(skeleton.isComplete, isTrue);
      expect(skeleton.missingParts, isEmpty);

      // 4. render + encode every shipped clip that matters most
      for (final clip in [kWalk, kWave, kIdle]) {
        final result = await ExportService.export(
          skeleton: skeleton,
          images: images,
          clip: clip,
          settings: ExportSettings(
            fps: 20,
            seconds: clip.durationSeconds,
            width: 360,
            background: const Color(0xFF101216),
            watermark: true,
          ),
          characterName: 'demo',
          outputDirectory: outDir,
        );
        final file = File(result.path);
        expect(file.existsSync(), isTrue);
        expect(file.lengthSync(), greaterThan(2000),
            reason: '${clip.name} gif looks empty');
        // stable filename for the README gallery
        file.renameSync('${outDir.path}/${clip.name}.gif');
      }

      // 5. transparent-background export path
      final transparent = await ExportService.export(
        skeleton: skeleton,
        images: images,
        clip: kRun,
        settings: const ExportSettings(fps: 16, seconds: 0.62, width: 300),
        characterName: 'demo_transparent',
        outputDirectory: outDir,
      );
      expect(File(transparent.path).existsSync(), isTrue);
      File(transparent.path).renameSync('${outDir.path}/run_transparent.gif');
    });
  }, timeout: const Timeout(Duration(minutes: 5)));
}
