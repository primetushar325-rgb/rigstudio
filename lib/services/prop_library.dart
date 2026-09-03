import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';

/// Procedurally drawn built-in props (transparent PNGs), so the Props panel has
/// something to hand over immediately. Each is ~ a few hundred px tall.
class PropTemplate {
  const PropTemplate(this.id, this.label, this.icon);
  final String id;
  final String label;
  final IconData icon;
}

const List<PropTemplate> kBuiltInPropTemplates = [
  PropTemplate('hat', 'Hat', Icons.emoji_people),
  PropTemplate('glasses', 'Glasses', Icons.visibility),
  PropTemplate('stick', 'Stick', Icons.gesture),
  PropTemplate('phone', 'Phone', Icons.smartphone),
  PropTemplate('bag', 'Bag', Icons.work_outline),
];

/// Which bone a template sensibly defaults to.
String defaultBoneForProp(String templateId) => switch (templateId) {
      'hat' => 'head',
      'glasses' => 'head',
      'stick' => 'hand_r',
      'phone' => 'hand_r',
      'bag' => 'upper_arm_r',
      _ => 'head',
    };

/// Generates a transparent PNG for a built-in prop.
Future<Uint8List> generatePropPng(String templateId) async {
  final w = 512, h = 512;
  final recorder = ui.PictureRecorder();
  final canvas = Canvas(recorder, Rect.fromLTWH(0, 0, w.toDouble(), h.toDouble()));

  switch (templateId) {
    case 'hat':
      _hat(canvas, w, h);
    case 'glasses':
      _glasses(canvas, w, h);
    case 'stick':
      _stick(canvas, w, h);
    case 'phone':
      _phone(canvas, w, h);
    case 'bag':
      _bag(canvas, w, h);
    default:
      _hat(canvas, w, h);
  }

  final picture = recorder.endRecording();
  final image = await picture.toImage(w, h);
  final data = await image.toByteData(format: ui.ImageByteFormat.png);
  image.dispose();
  return data!.buffer.asUint8List();
}

void _hat(Canvas c, int w, int h) {
  // wide brim
  c.drawOval(
      Rect.fromLTWH(w * 0.12, h * 0.38, w * 0.76, h * 0.16),
      Paint()..color = const Color(0xFF5A3E28));
  // crown
  c.drawRRect(
      RRect.fromRectAndRadius(
          Rect.fromLTWH(w * 0.30, h * 0.06, w * 0.40, h * 0.40),
          Radius.circular(w * 0.04)),
      Paint()..color = const Color(0xFF7A5232));
  // band
  c.drawRRect(
      RRect.fromRectAndRadius(
          Rect.fromLTWH(w * 0.30, h * 0.30, w * 0.40, h * 0.10),
          Radius.circular(w * 0.02)),
      Paint()..color = const Color(0xFFC0392B));
}

void _glasses(Canvas c, int w, int h) {
  final p = Paint()
    ..style = PaintingStyle.stroke
    ..strokeWidth = w * 0.06
    ..color = const Color(0xFF20242E);
  c.drawCircle(Offset(w * 0.33, h * 0.42), w * 0.20, p);
  c.drawCircle(Offset(w * 0.67, h * 0.42), w * 0.20, p);
  c.drawLine(Offset(w * 0.53, h * 0.42), Offset(w * 0.47, h * 0.42), p..strokeWidth = w * 0.045);
  c.drawLine(Offset(w * 0.13, h * 0.40), Offset(w * 0.03, h * 0.30), p);
  c.drawLine(Offset(w * 0.87, h * 0.40), Offset(w * 0.97, h * 0.30), p);
}

void _stick(Canvas c, int w, int h) {
  final p = Paint()
    ..strokeWidth = w * 0.08
    ..strokeCap = StrokeCap.round
    ..color = const Color(0xFF8A5A2B);
  c.drawLine(Offset(w * 0.45, h * 1.0), Offset(w * 0.55, 0.0), p);
}

void _phone(Canvas c, int w, int h) {
  // rounded body
  c.drawRRect(
      RRect.fromRectAndRadius(
          Rect.fromLTWH(w * 0.28, h * 0.06, w * 0.44, h * 0.86),
          Radius.circular(w * 0.08)),
      Paint()..color = const Color(0xFF1F2329));
  c.drawRRect(
      RRect.fromRectAndRadius(
          Rect.fromLTWH(w * 0.33, h * 0.13, w * 0.34, h * 0.68),
          Radius.circular(w * 0.04)),
      Paint()..color = const Color(0xFF2E86C1));
}

void _bag(Canvas c, int w, int h) {
  final body = Paint()..color = const Color(0xFF70441F);
  // handles
  final hp = Paint()
    ..style = PaintingStyle.stroke
    ..strokeWidth = w * 0.06
    ..color = const Color(0xFF9E6A33);
  c.drawArc(Rect.fromLTWH(w * 0.30, h * 0.06, w * 0.40, h * 0.30), 3.2, 3.8, false, hp);
  // body
  c.drawRRect(
      RRect.fromRectAndRadius(
          Rect.fromLTWH(w * 0.18, h * 0.28, w * 0.64, h * 0.66),
          Radius.circular(w * 0.05)),
      body);
  // flap
  c.drawRRect(
      RRect.fromRectAndRadius(
          Rect.fromLTWH(w * 0.18, h * 0.30, w * 0.64, h * 0.30),
          Radius.circular(w * 0.05)),
      Paint()..color = const Color(0xFF5A371B));
}
