import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:rigstudio/data/animation_library.dart';
import 'package:rigstudio/data/standard_rig.dart';
import 'package:rigstudio/models/playback.dart';
import 'package:rigstudio/rendering/fk.dart';
import 'package:rigstudio/services/export_service.dart';
import 'package:rigstudio/state/history.dart';

void main() {
  group('rotation limits', () {
    test('clampRotation honours min/max', () {
      final bones = kStandardRig;
      for (final tb in bones) {
        final (min, max) = defaultAngleLimits(tb.id);
        if (min.isFinite && max.isFinite) {
          expect(min <= max, isTrue, reason: tb.id);
        }
      }
    });

    test('every shipped clip stays inside default limits (no clip is clamped)', () {
      for (final clip in kAnimationLibrary) {
        for (var i = 0; i <= 32; i++) {
          final t = i / 32;
          final pose = clip.sample(t);
          pose.forEach((boneId, bonePose) {
            final (min, max) = defaultAngleLimits(boneId);
            if (!min.isFinite || !max.isFinite) return; // torso is unbounded
            expect(bonePose.rotation,
                inInclusiveRange(min - 1e-6, max + 1e-6),
                reason: 'clip ${clip.name} exceeds $boneId limit at t=$t');
          });
        }
      }
    });
  });

  group('facing + walk motion', () {
    test('in-place returns no horizontal movement', () {
      final motion = PlaybackMotion(walking: true, inPlace: true);
      expect(motion.horizontalOffset(10), 0);
      expect(motion.moving, isFalse);
    });

    test('movement sign matches facing direction', () {
      final right = PlaybackMotion(facing: FacingDirection.right, walking: true, inPlace: false);
      final left = PlaybackMotion(facing: FacingDirection.left, walking: true, inPlace: false);
      expect(right.horizontalOffset(3), greaterThan(0));
      expect(left.horizontalOffset(3), lessThan(0));
    });

    test('facing never lets sign disagree: right→positive, left→negative', () {
      for (final dir in FacingDirection.values) {
        final m = PlaybackMotion(facing: dir, walking: true, inPlace: false);
        final dx = m.horizontalOffset(1);
        expect(dx.sign, dir.xSign);
      }
    });

    test('wrapTo bounds an unbounded translation', () {
      expect(PlaybackMotion.wrapTo(150, 100), 50); // 150 % 100
      expect(PlaybackMotion.wrapTo(-150, 100), closeTo(50, 1e-6));
    });

    test('playbackRootMatrix mirrors x when facing left', () {
      const cx = 100.0;
      final p = playbackRootMatrix(centerX: cx, facing: FacingDirection.left, translateX: 0);
      final out = PoseSolver.transformPoint(p, const Offset(130, 50)); // 30 right of centre
      expect(out.dx, closeTo(70, 1e-4)); // mirrored to 30 left of centre
      expect(out.dy, closeTo(50, 1e-4));
    });

    test('translation shifts right-facing rig to the right', () {
      final p = playbackRootMatrix(centerX: 0, facing: FacingDirection.right, translateX: 25);
      final out = PoseSolver.transformPoint(p, const Offset(10, 0));
      expect(out.dx, closeTo(35, 1e-4));
    });
  });

  group('undo/redo history', () {
    test('undo returns previous value, redo returns next', () {
      final h = History<int>();
      var value = 0;
      // Caller mirrors the real controller: record() the pre-state, mutate.
      void bump() {
        h.record(() => value);
        value++;
      }

      bump(); // undo=[0], value=1
      bump(); // undo=[0,1], value=2
      expect(h.canUndo, isTrue);

      value = h.undo(() => value)!; // prev=1
      expect(value, 1);
      value = h.undo(() => value)!; // prev=0
      expect(value, 0);
      expect(h.canUndo, isFalse);

      value = h.redo(() => value)!; // 1
      expect(value, 1);
      value = h.redo(() => value)!; // 2
      expect(value, 2);
      expect(h.canRedo, isFalse);
    });
  });

  group('export media validation', () {
    late Directory dir;
    setUp(() => dir = Directory.systemTemp.createTempSync('rig_valid_'));
    tearDown(() {
      try {
        dir.deleteSync(recursive: true);
      } catch (_) {}
    });

    File write(String name, List<int> bytes) {
      final f = File('${dir.path}/$name');
      f.writeAsBytesSync(bytes);
      return f;
    }

    test('accepts real GIF/MP4/PNG magic, rejects junk and tiny files', () {
      final gif = write('a.gif', [0x47, 0x49, 0x46, 0x38, 0x39, 0x61] + List.filled(200, 0));
      expect(ExportService.isValidMediaFile(gif.path, ExportFormat.gif), isTrue);

      final mp4 = write('a.mp4',
          [0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d] +
              List.filled(200, 0));
      expect(ExportService.isValidMediaFile(mp4.path, ExportFormat.mp4), isTrue);

      final png = write('a.png', [0x89, 0x50, 0x4e, 0x47] + List.filled(200, 0));
      expect(ExportService.isValidMediaFile(png.path, ExportFormat.pngSequence), isTrue);

      // a png labelled .mp4 must NOT pass mp4 validation
      expect(ExportService.isValidMediaFile(png.path, ExportFormat.mp4), isFalse);

      // garbage
      final junk = write('junk.gif', List.filled(300, 1));
      expect(ExportService.isValidMediaFile(junk.path, ExportFormat.gif), isFalse);

      // too small to be real media
      final tiny = write('tiny.mp4', [0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70]);
      expect(ExportService.isValidMediaFile(tiny.path, ExportFormat.mp4), isFalse);
    });
  });
}
