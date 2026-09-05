import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';

import '../data/standard_rig.dart';

/// Procedurally drawn stand-in character (front-facing, standard proportions)
/// on a solid green backdrop. Lets every screen — chroma key, template align,
/// auto-crop, playback, export — be exercised before a real photo is imported.
class DemoCharacter {
  static const Size size = Size(800, 1200);

  static Future<Uint8List> generatePng({
    Color background = const Color(0xFF00B140),
    Color skin = const Color(0xFFF2C7A0),
    Color shirt = const Color(0xFF3D6DF2),
    Color pants = const Color(0xFF2B3450),
  }) async {
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder, Offset.zero & size);
    canvas.drawRect(Offset.zero & size, Paint()..color = background);

    final t = RigTemplateTransform(
      center: Offset(size.width / 2, size.height / 2),
      scale: size.height * 0.92,
    );

    Offset pt(Offset norm) => t.mapPoint(norm);

    void limb(TemplateBone b, Color color, {double widthScale = 1.0}) {
      final paint = Paint()
        ..color = color
        ..strokeCap = StrokeCap.round
        ..strokeWidth = t.thicknessPx(b) * widthScale
        ..style = PaintingStyle.stroke;
      canvas.drawLine(pt(b.start), pt(b.end), paint);
    }

    TemplateBone bone(String id) => templateBoneById(id);

    // legs (behind)
    for (final id in ['thigh_r', 'shin_r', 'thigh_l', 'shin_l']) {
      limb(bone(id), pants);
    }
    for (final id in ['foot_r', 'foot_l']) {
      limb(bone(id), const Color(0xFF1A1F2E), widthScale: 1.1);
    }

    // torso
    final torso = bone('torso');
    final torsoRect = RRect.fromRectAndRadius(
      Rect.fromPoints(
        pt(torso.start) + Offset(-t.thicknessPx(torso) / 2, 0),
        pt(torso.end) + Offset(t.thicknessPx(torso) / 2, 0),
      ),
      const Radius.circular(38),
    );
    canvas.drawRRect(torsoRect, Paint()..color = shirt);

    // arms
    limb(bone('upper_arm_r'), shirt);
    limb(bone('forearm_r'), skin);
    limb(bone('upper_arm_l'), shirt);
    limb(bone('forearm_l'), skin);
    for (final id in ['hand_l', 'hand_r']) {
      final b = bone(id);
      canvas.drawCircle(pt(b.mid), t.thicknessPx(b) * 0.55, Paint()..color = skin);
    }

    // head
    final head = bone('head');
    final headCenter = pt(head.mid);
    final headR = t.thicknessPx(head) * 0.62;
    canvas.drawOval(
      Rect.fromCenter(center: headCenter, width: headR * 1.75, height: headR * 2.15),
      Paint()..color = skin,
    );
    // hair
    canvas.drawArc(
      Rect.fromCenter(center: headCenter, width: headR * 1.8, height: headR * 2.2),
      3.4,
      2.5,
      true,
      Paint()..color = const Color(0xFF3A2B22),
    );
    // eyes + mouth
    final eye = Paint()..color = const Color(0xFF20242E);
    canvas.drawCircle(headCenter + Offset(-headR * 0.34, -headR * 0.1), headR * 0.10, eye);
    canvas.drawCircle(headCenter + Offset(headR * 0.34, -headR * 0.1), headR * 0.10, eye);
    canvas.drawArc(
      Rect.fromCenter(
          center: headCenter + Offset(0, headR * 0.42),
          width: headR * 0.7,
          height: headR * 0.45),
      0.2,
      2.7,
      false,
      Paint()
        ..color = const Color(0xFF8A4438)
        ..style = PaintingStyle.stroke
        ..strokeWidth = headR * 0.10
        ..strokeCap = StrokeCap.round,
    );

    final picture = recorder.endRecording();
    final image = await picture.toImage(size.width.toInt(), size.height.toInt());
    final data = await image.toByteData(format: ui.ImageByteFormat.png);
    image.dispose();
    return data!.buffer.asUint8List();
  }
}
