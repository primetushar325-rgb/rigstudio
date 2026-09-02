import 'dart:ui' as ui;

import 'package:flutter/material.dart';

import '../data/standard_rig.dart';
import '../models/animation_clip.dart';
import '../models/skeleton.dart';
import 'fk.dart';

/// Draws a posed rig. If a bone has no bitmap yet it falls back to a stick
/// figure capsule, so every screen is testable before any cutting happens.
class RigPainter extends CustomPainter {
  RigPainter({
    required this.skeleton,
    required this.images,
    required this.pose,
    this.background,
    this.showBones = false,
    this.selectedBoneId,
    this.fit = true,
    this.padding = 12,
  });

  final Skeleton skeleton;
  final Map<String, ui.Image> images;
  final Map<String, BonePose> pose;
  final Color? background;
  final bool showBones;
  final String? selectedBoneId;
  final bool fit;
  final double padding;

  @override
  void paint(Canvas canvas, Size size) {
    if (background != null) {
      canvas.drawRect(Offset.zero & size, Paint()..color = background!);
    }

    canvas.save();
    if (fit) {
      final cs = skeleton.canvasSize;
      final scale = ((size.width - padding * 2) / cs.width)
          .clamp(0.0, double.infinity)
          .toDouble();
      final scaleY = (size.height - padding * 2) / cs.height;
      final s = scale < scaleY ? scale : scaleY;
      canvas.translate(
        (size.width - cs.width * s) / 2,
        (size.height - cs.height * s) / 2,
      );
      canvas.scale(s);
    }

    final world = PoseSolver.solve(
      skeleton,
      pose: pose,
      offsetScale: PoseSolver.rigHeight(skeleton),
    );

    final paintImg = Paint()..filterQuality = FilterQuality.medium;

    for (final bone in skeleton.drawOrder) {
      if (!bone.visible) continue;
      final m = world[bone.id];
      if (m == null) continue;

      canvas.save();
      canvas.transform(m.storage);

      if (bone.mirrored) {
        canvas.translate(bone.pivot.dx, 0);
        canvas.scale(-1, 1);
        canvas.translate(-bone.pivot.dx, 0);
      }

      final img = images[bone.id];
      if (img != null && bone.imageRect != Rect.zero) {
        canvas.drawImageRect(
          img,
          Rect.fromLTWH(0, 0, img.width.toDouble(), img.height.toDouble()),
          bone.imageRect,
          paintImg,
        );
      } else {
        _drawStick(canvas, bone.id, bone.pivot);
      }
      canvas.restore();
    }

    if (showBones) _drawBoneOverlay(canvas, world);
    canvas.restore();
  }

  void _drawStick(Canvas canvas, String boneId, Offset pivot) {
    TemplateBone? tb;
    for (final b in kStandardRig) {
      if (b.id == boneId) tb = b;
    }
    if (tb == null) return;
    final bone = skeleton.byId(boneId)!;
    // Reconstruct a rough segment: pivot -> child pivot (or template direction).
    final childPivot = _endPointFor(boneId) ?? (pivot + const Offset(0, 40));
    final width = (childPivot - pivot).distance * (tb.thickness / tb.length);
    final paint = Paint()
      ..color = (selectedBoneId == boneId ? Colors.amber : Colors.blueGrey)
          .withValues(alpha: 0.65)
      ..strokeWidth = width.clamp(6.0, 120.0)
      ..strokeCap = StrokeCap.round
      ..style = PaintingStyle.stroke;
    canvas.drawLine(bone.pivot, childPivot, paint);
  }

  Offset? _endPointFor(String boneId) {
    // Prefer the actual child joint so the stick figure follows fine tuning.
    for (final b in skeleton.bones) {
      if (b.parentId == boneId && b.id != 'head') return b.pivot;
    }
    final tb = kStandardRig.firstWhere((b) => b.id == boneId);
    final root = skeleton.byId('torso');
    if (root == null) return null;
    final dir = tb.end - tb.start;
    final scale = skeleton.canvasSize.height;
    return skeleton.byId(boneId)!.pivot + Offset(dir.dx * scale, dir.dy * scale);
  }

  void _drawBoneOverlay(Canvas canvas, Map<String, Matrix4> world) {
    final jointPaint = Paint()..color = Colors.amberAccent;
    final linePaint = Paint()
      ..color = Colors.amberAccent.withValues(alpha: 0.8)
      ..strokeWidth = 2;
    for (final bone in skeleton.bones) {
      final m = world[bone.id];
      if (m == null) continue;
      final p = PoseSolver.transformPoint(m, bone.pivot);
      final parent = bone.parentId == null ? null : skeleton.byId(bone.parentId!);
      if (parent != null) {
        final pm = world[parent.id];
        if (pm != null) {
          canvas.drawLine(PoseSolver.transformPoint(pm, parent.pivot), p, linePaint);
        }
      }
      canvas.drawCircle(p, bone.id == selectedBoneId ? 7 : 4, jointPaint);
    }
  }

  @override
  bool shouldRepaint(covariant RigPainter old) => true;
}

/// Transparent-background checkerboard, matching the export preview.
class CheckerboardPainter extends CustomPainter {
  CheckerboardPainter({this.cell = 12});
  final double cell;

  @override
  void paint(Canvas canvas, Size size) {
    final light = Paint()..color = const Color(0xFF3A3A3A);
    final dark = Paint()..color = const Color(0xFF2E2E2E);
    canvas.drawRect(Offset.zero & size, dark);
    for (var y = 0.0; y < size.height; y += cell) {
      for (var x = 0.0; x < size.width; x += cell) {
        final odd = ((x / cell).floor() + (y / cell).floor()) % 2 == 0;
        if (odd) canvas.drawRect(Rect.fromLTWH(x, y, cell, cell), light);
      }
    }
  }

  @override
  bool shouldRepaint(covariant CheckerboardPainter oldDelegate) => false;
}
