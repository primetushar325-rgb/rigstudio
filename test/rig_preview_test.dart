import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rigstudio/data/animation_library.dart';
import 'package:rigstudio/data/standard_rig.dart';
import 'package:rigstudio/models/animation_clip.dart';
import 'package:rigstudio/models/playback.dart';
import 'package:rigstudio/models/skeleton.dart';
import 'package:rigstudio/widgets/rig_preview.dart';

Skeleton buildRig() => buildSkeletonFromTemplate(
      characterId: 'test',
      canvasSize: const Size(800, 1200),
      transform: RigTemplateTransform.fitTo(const Size(800, 1200)),
    );

void main() {
  group('RigPreview playback', () {
    testWidgets('pause freezes the clip and play resumes from the same frame',
        (tester) async {
      var time = -1.0;

      Widget build(bool playing) => MaterialApp(
            home: Scaffold(
              body: RigPreview(
                skeleton: buildRig(),
                images: const {},
                clip: kIdle,
                playing: playing,
                onTime: (t) => time = t,
              ),
            ),
          );

      await tester.pumpWidget(build(true));
      await tester.pump(const Duration(milliseconds: 600));
      final mid = time;
      expect(mid, greaterThan(0.0));
      expect(mid, lessThan(1.0));

      // Pause: the playhead must freeze exactly where it was.
      await tester.pumpWidget(build(false));
      await tester.pump(const Duration(milliseconds: 600));
      expect(time, mid);

      // Resume: continues from mid — must NOT jump back to 0.
      await tester.pumpWidget(build(true));
      await tester.pump(const Duration(milliseconds: 100));
      expect(time, greaterThan(mid));
    });

    testWidgets('unrelated rebuilds do not restart the clip', (tester) async {
      var time = -1.0;

      // Simulates AnimateScreen: every build creates a brand-new
      // PlaybackMotion instance even though the walk config never changed.
      Widget build() => MaterialApp(
            home: Scaffold(
              body: RigPreview(
                skeleton: buildRig(),
                images: const {},
                clip: kIdle,
                playing: true,
                motion: PlaybackMotion(
                  facing: FacingDirection.right,
                  walking: true,
                  inPlace: false,
                ),
                onTime: (t) => time = t,
              ),
            ),
          );

      await tester.pumpWidget(build());
      await tester.pump(const Duration(milliseconds: 400));
      final t1 = time;
      expect(t1, greaterThan(0.0));

      // A rebuild with identical config (new widget + new motion instance)
      // must keep the clip running from t1, not restart it from 0.
      await tester.pumpWidget(build());
      await tester.pump(const Duration(milliseconds: 200));
      expect(time, greaterThan(t1));
    });

    testWidgets('switching clips restarts from zero', (tester) async {
      var time = -1.0;

      Widget build(AnimationClip clip) => MaterialApp(
            home: Scaffold(
              body: RigPreview(
                skeleton: buildRig(),
                images: const {},
                clip: clip,
                playing: true,
                onTime: (t) => time = t,
              ),
            ),
          );

      await tester.pumpWidget(build(kIdle));
      await tester.pump(const Duration(milliseconds: 400));
      expect(time, greaterThan(0.0));

      await tester.pumpWidget(build(kWave));
      await tester.pump(const Duration(milliseconds: 50));
      // kWave is 1.2s long, so 50ms in ≈ 0.04 — a restart, not a continuation
      // of the idle clip's playhead (which was ≈ 0.15).
      expect(time, lessThan(0.1));
    });
  });
}
