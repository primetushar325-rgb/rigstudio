import 'dart:convert';
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

    test('template skeleton survives real JSON (torso has infinite limits)', () {
      // defaultAngleLimits('torso') is (±∞). jsonEncode cannot serialise
      // infinities, so toJson must omit unbounded limits — otherwise saving
      // ANY rigged character throws and the rig is lost.
      final s = buildRig();
      final text = jsonEncode(s.toJson()); // must not throw
      final back = Skeleton.fromJson(jsonDecode(text) as Map<String, dynamic>);
      final torso = back.byId('torso')!;
      expect(torso.minAngleRad, isNull); // unbounded stays unbounded
      expect(torso.maxAngleRad, isNull);
      expect(back.byId('head')!.minAngleRad, closeTo(-80 * math.pi / 180, 1e-9));
      expect(back.byId('foot_l')!.maxAngleRad, closeTo(45 * math.pi / 180, 1e-9));
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

  group('whole-rig mirror', () {
    test('rotation limits survive the mirror (sign-flipped and swapped)', () {
      final s = buildRig();
      // Tune an ASYMMETRIC limit on the right foot (limits are final fields,
      // so swap the bone via copyWith) and a pose on the right arm.
      expect(s.byId('foot_r')!.minAngleRad, isNotNull);
      final i = s.bones.indexWhere((b) => b.id == 'foot_r');
      s.bones[i] = s.bones[i].copyWith(minAngleRad: -0.5, maxAngleRad: 0.25);
      s.byId('upper_arm_r')!.rotation = 0.3;
      s.byId('upper_arm_r')!.translation = const Offset(10, 4);

      final m = s.mirroredRig();

      // No bone may lose its clamp protection (head/feet are always limited).
      for (final b in m.bones) {
        if (b.id == 'torso') continue; // deliberately unbounded
        expect(b.hasAngleLimits, isTrue, reason: '${b.id} lost its limits');
      }

      // foot_l now carries foot_r's mirrored limits: [-0.25, +0.5].
      final footL = m.byId('foot_l')!;
      expect(footL.minAngleRad, closeTo(-0.25, 1e-9));
      expect(footL.maxAngleRad, closeTo(0.5, 1e-9));

      // The mirrored pose: rotation and x-translation flip sign.
      final armL = m.byId('upper_arm_l')!;
      expect(armL.rotation, closeTo(-0.3, 1e-9));
      expect(armL.translation, const Offset(-10, 4));
    });

    test('mirrored rig still round-trips through json', () {
      final s = buildRig();
      final m = s.mirroredRig();
      final copy = Skeleton.fromJson(m.toJson());
      // JSON cannot carry ±Infinity: unbounded (infinite) limits and absent
      // (null) limits mean the same thing after a reload.
      double? norm(double? v) => (v == null || !v.isFinite) ? null : v;
      for (final b in copy.bones) {
        final orig = m.byId(b.id)!;
        expect(norm(b.minAngleRad), norm(orig.minAngleRad),
            reason: '${b.id} limits lost');
        expect(norm(b.maxAngleRad), norm(orig.maxAngleRad));
        expect(b.rotation, orig.rotation);
      }
    });
  });
}
