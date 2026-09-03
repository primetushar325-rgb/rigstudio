import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rigstudio/data/standard_rig.dart';
import 'package:rigstudio/models/prop.dart';
import 'package:rigstudio/rendering/rig_painter.dart';
import 'package:rigstudio/services/prop_library.dart';

void main() {
  group('PropAttachment model', () {
    test('json round-trips', () {
      final p = PropAttachment(
        id: 'hat_1',
        label: 'hat',
        imagePath: '/tmp/hat.png',
        attachedBoneId: 'head',
        localOffset: const Offset(4, -10),
        localRotation: 0.2,
        scale: 0.7,
        zIndex: 80,
      );
      final c = PropAttachment.fromJson(p.toJson());
      expect(c.id, 'hat_1');
      expect(c.attachedBoneId, 'head');
      expect(c.localOffset.dx, 4);
      expect(c.scale, 0.7);
      expect(c.localRotation, 0.2);
      expect(c.visible, isTrue);
    });
  });

  group('whole-rig mirror flips props', () {
    test('skeleton.mirroredRig reparents + flips prop offsets', () {
      final s = buildSkeletonFromTemplate(
        characterId: 'x',
        canvasSize: const Size(800, 1200),
        transform: RigTemplateTransform.fitTo(const Size(800, 1200)),
      );
      s.props.add(PropAttachment(
        id: 'stick',
        label: 'stick',
        imagePath: '/tmp/stick.png',
        attachedBoneId: 'hand_r',
        localOffset: const Offset(30, 5),
      ));
      final m = s.mirroredRig();
      expect(m.props.single.attachedBoneId, 'hand_l'); // l/r swap
      expect(m.props.single.localOffset.dx, -30); // horizontal offset flips
      expect(m.props.single.mirrored, isTrue);
    });

    test('defaultBoneForProp maps sensibly', () {
      expect(defaultBoneForProp('hat'), 'head');
      expect(defaultBoneForProp('stick'), 'hand_r');
    });
  });

  testWidgets('generatePropPng yields a real PNG and a rig+prop can be painted',
      (tester) async {
    await tester.runAsync(() async {
      final bytes = await generatePropPng('hat');
      expect(bytes.length, greaterThan(100));

      final s = buildSkeletonFromTemplate(
        characterId: 'x',
        canvasSize: const Size(400, 600),
        transform: RigTemplateTransform.fitTo(const Size(400, 600)),
      );
      final codec = await ui.instantiateImageCodec(bytes);
      final propImage = (await codec.getNextFrame()).image;
      s.props.add(PropAttachment(
        id: 'hat',
        label: 'hat',
        imagePath: 'mem',
        attachedBoneId: 'head',
        localOffset: const Offset(0, -60),
        scale: 0.5,
      ));

      // Paint the rig (standing rest pose) with the prop to a real surface.
      final rec = ui.PictureRecorder();
      final canvas = Canvas(rec, const Rect.fromLTWH(0, 0, 400, 600));
      RigPainter(
        skeleton: s,
        images: const {},
        pose: const {},
        props: s.props,
        propImages: {'hat': propImage},
        fit: true,
      ).paint(canvas, const Size(400, 600));
      final img = await rec.endRecording().toImage(400, 600);
      expect(img.width, 400);
      expect(img.height, 600);
      img.dispose();
      propImage.dispose();
    });
  });
}
