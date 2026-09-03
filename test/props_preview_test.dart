import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rigstudio/data/animation_library.dart';
import 'package:rigstudio/data/standard_rig.dart';
import 'package:rigstudio/models/prop.dart';
import 'package:rigstudio/rendering/rig_painter.dart';
import 'package:rigstudio/services/chroma_key_service.dart';
import 'package:rigstudio/services/cut_service.dart';
import 'package:rigstudio/services/demo_character.dart';
import 'package:rigstudio/services/prop_library.dart';

/// Dev preview: rigs the demo character, auto-crops it, adds a hat + glasses
/// prop, and renders an idle frame to `preview/props_idle.png`. Run with:
///   flutter test test/props_preview_test.dart
void main() {
  testWidgets('render demo character with props', (tester) async {
    await tester.runAsync(() async {
      final outDir = Directory('preview')..createSync(recursive: true);

      final source = await DemoCharacter.generatePng();
      final keyed = await ChromaKeyService.run(
        ChromaKeyRequest(source, const ChromaKeyParams(tolerance: 0.22, feather: 0.10)),
      );
      const canvas = DemoCharacter.size;
      final t = RigTemplateTransform.fitTo(canvas);
      final s = buildSkeletonFromTemplate(
        characterId: 'demo', canvasSize: canvas, transform: t);
      final cuts = await CutService.autoCropFromTemplate(imageBytes: keyed, transform: t);
      final parts = <String, ui.Image>{};
      for (final cut in cuts) {
        final b = s.byId(cut.boneId)!;
        b.imageRect = cut.rect;
        parts[cut.boneId] =
            (await (await ui.instantiateImageCodec(cut.pngBytes)).getNextFrame()).image;
      }

      final hatBytes = await generatePropPng('hat');
      final glassBytes = await generatePropPng('glasses');
      final hatImg = (await (await ui.instantiateImageCodec(hatBytes)).getNextFrame()).image;
      final glassImg = (await (await ui.instantiateImageCodec(glassBytes)).getNextFrame()).image;

      s.props.add(PropAttachment(
        id: 'hat', label: 'hat', imagePath: 'mem',
        attachedBoneId: 'head', localOffset: Offset(0, -canvas.height * 0.20), scale: 0.5,
      ));
      s.props.add(PropAttachment(
        id: 'glasses', label: 'glasses', imagePath: 'mem',
        attachedBoneId: 'head', localOffset: Offset(0, -canvas.height * 0.02), scale: 0.5,
      ));

      final w = 600.0;
      final rec = ui.PictureRecorder();
      final c = Canvas(rec);
      c.drawRect(const Rect.fromLTWH(0, 0, 600, 600), Paint()..color = const Color(0xFF101216));
      RigPainter(
        skeleton: s,
        images: parts,
        pose: kIdle.sample(0.5),
        props: s.props,
        propImages: {'hat': hatImg, 'glasses': glassImg},
        fit: false,
      ).paint(c, Size(w, w));
      final img = await rec.endRecording().toImage(w.round(), w.round());
      final data = await img.toByteData(format: ui.ImageByteFormat.png);
      File('${outDir.path}/props_idle.png').writeAsBytesSync(data!.buffer.asUint8List());
      img.dispose();
      expect(File('${outDir.path}/props_idle.png').existsSync(), isTrue);
    });
  }, timeout: const Timeout(Duration(minutes: 3)));
}
