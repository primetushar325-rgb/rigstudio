import 'dart:math' as math;
import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:rigstudio/data/animation_library.dart';
import 'package:rigstudio/data/standard_rig.dart';
import 'package:rigstudio/models/bone_part.dart';
import 'package:rigstudio/models/skeleton.dart';
import 'package:rigstudio/rendering/fk.dart';

void main() {
  Skeleton buildRig() => buildSkeletonFromTemplate(
        characterId: 'test',
        canvasSize: const Size(800, 1200),
        transform: RigTemplateTransform.fitTo(const Size(800, 1200)),
      );

  group('standard rig', () {
    test('hierarchy is complete and rooted at the torso', () {
      final s = buildRig();
      expect(s.root.id, 'torso');
      for (final b in s.bones) {
        if (b.parentId != null) {
          expect(s.byId(b.parentId!), isNotNull,
              reason: '${b.id} points at a missing parent');
        }
      }
      // parents always precede children
      final order = s.topologicalOrder.map((b) => b.id).toList();
      for (final b in s.bones) {
        if (b.parentId == null) continue;
        expect(order.indexOf(b.parentId!) < order.indexOf(b.id), isTrue);
      }
    });

    test('every clip only targets known bone ids', () {
      for (final clip in kAnimationLibrary) {
        for (final boneId in clip.tracks.keys) {
          expect(kBoneIds.contains(boneId), isTrue,
              reason: 'clip ${clip.name} targets unknown bone $boneId');
        }
      }
    });

    test('clips sample inside their keyed range', () {
      for (final clip in kAnimationLibrary) {
        for (final t in [0.0, 0.13, 0.5, 0.87, 1.0]) {
          final pose = clip.sample(t);
          expect(pose.isNotEmpty, isTrue);
          for (final p in pose.values) {
            expect(p.rotation.isFinite, isTrue);
            expect(p.rotation.abs() < math.pi * 2, isTrue);
          }
        }
      }
    });
  });

  group('forward kinematics', () {
    test('rotating the parent carries the child along', () {
      final s = buildRig();
      final elbowRest = s.byId('forearm_l')!.pivot;

      final rest = PoseSolver.solve(s);
      final restElbow = PoseSolver.transformPoint(rest['forearm_l']!, elbowRest);
      expect((restElbow - elbowRest).distance, lessThan(0.001));

      s.byId('upper_arm_l')!.rotation = math.pi / 2;
      final posed = PoseSolver.solve(s);
      final movedElbow = PoseSolver.transformPoint(posed['forearm_l']!, elbowRest);
      expect((movedElbow - elbowRest).distance, greaterThan(10));

      // the shoulder itself must not move
      final shoulder = s.byId('upper_arm_l')!.pivot;
      final movedShoulder = PoseSolver.transformPoint(posed['upper_arm_l']!, shoulder);
      expect((movedShoulder - shoulder).distance, lessThan(0.001));
    });

    test('whole-rig mirror swaps sides and flips geometry', () {
      final s = buildRig();
      final leftX = s.byId('upper_arm_l')!.pivot.dx;
      final m = s.mirroredRig();
      expect(m.rigMirrored, isTrue);
      expect(m.byId('upper_arm_l')!.pivot.dx, closeTo(800 - s.byId('upper_arm_r')!.pivot.dx, 0.001));
      expect(leftX, lessThan(400));
    });
  });

  group('serialisation', () {
    test('skeleton round-trips through json', () {
      final s = buildRig();
      s.byId('head')!
        ..imagePath = '/tmp/head.png'
        ..imageRect = const Rect.fromLTWH(10, 20, 100, 120)
        ..zIndex = 99;
      final copy = Skeleton.fromJson(s.toJson());
      final head = copy.byId('head')!;
      expect(head.imagePath, '/tmp/head.png');
      expect(head.imageRect, const Rect.fromLTWH(10, 20, 100, 120));
      expect(head.zIndex, 99);
      expect(copy.bones.length, s.bones.length);
    });

    test('bone part copyWith keeps identity fields', () {
      final p = BonePart(
        id: 'head',
        parentId: 'torso',
        label: 'Head',
        pivot: const Offset(1, 2),
      );
      final c = p.copyWith(zIndex: 5);
      expect(c.id, 'head');
      expect(c.parentId, 'torso');
      expect(c.zIndex, 5);
    });
  });
}
