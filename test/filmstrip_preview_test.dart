import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:rigstudio/data/animation_library.dart';
import 'package:rigstudio/data/standard_rig.dart';
import 'package:rigstudio/rendering/fk.dart';
import 'package:rigstudio/rendering/rig_painter.dart';
import 'package:rigstudio/services/chroma_key_service.dart';
import 'package:rigstudio/services/cut_service.dart';
import 'package:rigstudio/services/demo_character.dart';

/// Dev utility (not a real assertion suite): renders a contact-sheet filmstrip
/// per clip into `preview/` so animation timing can be reviewed without a
/// device. Run with:  flutter test test/filmstrip_preview_test.dart
void main() {
  testWidgets('filmstrips for every clip', (tester) async {
    await tester.runAsync(() async {
      final outDir = Directory('preview')..createSync(recursive: true);

      final source = await DemoCharacter.generatePng();
      final keyed = await ChromaKeyService.run(
        ChromaKeyRequest(source, const ChromaKeyParams(tolerance: 0.22, feather: 0.10)),
      );
      const canvas = DemoCharacter.size;
      final transform = RigTemplateTransform.fitTo(canvas);
      final skeleton = buildSkeletonFromTemplate(
        characterId: 'demo',
        canvasSize: canvas,
        transform: transform,
      );
      final cuts =
          await CutService.autoCropFromTemplate(imageBytes: keyed, transform: transform);
      final images = <String, ui.Image>{};
      for (final cut in cuts) {
        final bone = skeleton.byId(cut.boneId)!;
        bone.imagePath = 'memory';
        bone.imageRect = cut.rect;
        final codec = await ui.instantiateImageCodec(cut.pngBytes);
        images[cut.boneId] = (await codec.getNextFrame()).image;
      }

      const cols = 8;
      const cellW = 220;

      for (final clip in kAnimationLibrary) {
        // union bounds across the clip keeps every frame in the same box
        Rect? acc;
        for (var i = 0; i < 24; i++) {
          final world = PoseSolver.solve(skeleton,
              pose: clip.sample(i / 24), offsetScale: PoseSolver.rigHeight(skeleton));
          final b = PoseSolver.posedBounds(skeleton, world);
          acc = acc == null ? b : acc.expandToInclude(b);
        }
        final bounds = acc!.inflate(acc.longestSide * 0.05);
        final cellH = (cellW * bounds.height / bounds.width).round();

        final sheet = img.Image(width: cellW * cols, height: cellH, numChannels: 4);
        img.fill(sheet, color: img.ColorRgba8(16, 18, 22, 255));

        for (var i = 0; i < cols; i++) {
          final recorder = ui.PictureRecorder();
          final c = Canvas(recorder);
          c.scale(cellW / bounds.width);
          c.translate(-bounds.left, -bounds.top);
          RigPainter(
            skeleton: skeleton,
            images: images,
            pose: clip.sample(i / cols),
            fit: false,
          ).paint(c, bounds.size);
          final frame = await recorder.endRecording().toImage(cellW, cellH);
          final data = await frame.toByteData(format: ui.ImageByteFormat.rawRgba);
          final decoded = img.Image.fromBytes(
            width: cellW,
            height: cellH,
            bytes: data!.buffer,
            numChannels: 4,
            order: img.ChannelOrder.rgba,
          );
          img.compositeImage(sheet, decoded, dstX: i * cellW);
          frame.dispose();
        }
        File('${outDir.path}/filmstrip_${clip.name}.png')
            .writeAsBytesSync(img.encodePng(sheet));
      }
      expect(File('${outDir.path}/filmstrip_walk.png').existsSync(), isTrue);
    });
  }, timeout: const Timeout(Duration(minutes: 5)));
}
