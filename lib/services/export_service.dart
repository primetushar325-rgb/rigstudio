import 'dart:async';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:gal/gal.dart';
import 'package:image/image.dart' as img;
import 'package:path/path.dart' as p;
import 'package:share_plus/share_plus.dart';

import '../models/animation_clip.dart';
import '../models/skeleton.dart';
import '../rendering/fk.dart';
import '../rendering/rig_painter.dart';
import 'storage_service.dart';

enum ExportFormat { gif, pngSequence, mp4 }

class ExportSettings {
  const ExportSettings({
    this.format = ExportFormat.gif,
    this.fps = 24,
    this.seconds = 3.0,
    this.width = 512,
    this.background,
    this.watermark = true,
  });

  final ExportFormat format;
  final int fps;

  /// Total output length. Shorter than the clip = trim, longer = loop-fill.
  final double seconds;

  /// Output width in pixels (height follows the rig aspect).
  final int width;

  /// `null` renders a transparent background (GIF gets 1-bit transparency).
  final Color? background;

  final bool watermark;

  int get frameCount => (seconds * fps).round().clamp(1, 900);

  ExportSettings copyWith({
    ExportFormat? format,
    int? fps,
    double? seconds,
    int? width,
    Color? background,
    bool clearBackground = false,
    bool? watermark,
  }) =>
      ExportSettings(
        format: format ?? this.format,
        fps: fps ?? this.fps,
        seconds: seconds ?? this.seconds,
        width: width ?? this.width,
        background: clearBackground ? null : (background ?? this.background),
        watermark: watermark ?? this.watermark,
      );
}

class ExportResult {
  const ExportResult({required this.path, required this.format, required this.frames});
  final String path;
  final ExportFormat format;
  final int frames;
}

/// Renders the rig frame by frame with a [ui.PictureRecorder] and encodes the
/// result. GIF is the default (no native dependency); mp4 is a documented
/// drop-in via ffmpeg — see [encodeMp4].
class ExportService {
  static Future<ExportResult> export({
    required Skeleton skeleton,
    required Map<String, ui.Image> images,
    required AnimationClip clip,
    required ExportSettings settings,
    required String characterName,
    void Function(double progress, String stage)? onProgress,
    Directory? outputDirectory,
  }) async {
    final frames = <Uint8List>[];
    final n = settings.frameCount;

    // Frame the rig using the union of the posed bounds across the clip, so a
    // waving arm or a jump never gets cropped.
    final bounds = _clipBounds(skeleton, clip, samples: 16);
    final outW = settings.width;
    final aspect = bounds.height / bounds.width;
    final outH = (outW * aspect).round().clamp(16, 4096);

    int? width, height;
    for (var i = 0; i < n; i++) {
      final tSeconds = i / settings.fps;
      final t = clip.loop
          ? (tSeconds / clip.durationSeconds) % 1.0
          : (tSeconds / clip.durationSeconds).clamp(0.0, 1.0);

      final image = await _renderFrame(
        skeleton: skeleton,
        images: images,
        pose: clip.sample(t),
        bounds: bounds,
        outSize: Size(outW.toDouble(), outH.toDouble()),
        background: settings.background,
        watermark: settings.watermark,
      );
      width = image.width;
      height = image.height;
      final data = await image.toByteData(format: ui.ImageByteFormat.rawRgba);
      image.dispose();
      frames.add(data!.buffer.asUint8List());
      onProgress?.call((i + 1) / n * 0.7, 'Rendering frame ${i + 1}/$n');
    }

    onProgress?.call(0.75, 'Encoding…');
    final dir = outputDirectory ?? await StorageService.instance.exportDir();
    final stamp = DateTime.now().millisecondsSinceEpoch;
    final safeName = characterName.replaceAll(RegExp(r'[^A-Za-z0-9_-]'), '_');
    final base = '${safeName}_${clip.name}_$stamp';

    switch (settings.format) {
      case ExportFormat.gif:
        final gif = await compute(_encodeGifWorker, {
          'frames': frames,
          'width': width,
          'height': height,
          'delay': (100 / settings.fps).round().clamp(2, 100),
          'transparent': settings.background == null,
        });
        final path = p.join(dir.path, '$base.gif');
        await File(path).writeAsBytes(gif, flush: true);
        onProgress?.call(1.0, 'Done');
        return ExportResult(path: path, format: ExportFormat.gif, frames: n);

      case ExportFormat.pngSequence:
        final seqDir = Directory(p.join(dir.path, base))..createSync(recursive: true);
        for (var i = 0; i < frames.length; i++) {
          final png = await compute(_encodePngWorker, {
            'bytes': frames[i],
            'width': width,
            'height': height,
          });
          await File(p.join(seqDir.path, 'frame_${i.toString().padLeft(4, '0')}.png'))
              .writeAsBytes(png, flush: true);
          onProgress?.call(0.75 + (i / frames.length) * 0.25, 'Writing PNG ${i + 1}/$n');
        }
        onProgress?.call(1.0, 'Done');
        return ExportResult(path: seqDir.path, format: ExportFormat.pngSequence, frames: n);

      case ExportFormat.mp4:
        final seqDir = Directory(p.join(dir.path, '${base}_frames'))
          ..createSync(recursive: true);
        for (var i = 0; i < frames.length; i++) {
          final png = await compute(_encodePngWorker, {
            'bytes': frames[i],
            'width': width,
            'height': height,
          });
          await File(p.join(seqDir.path, 'frame_${i.toString().padLeft(4, '0')}.png'))
              .writeAsBytes(png, flush: true);
        }
        final out = p.join(dir.path, '$base.mp4');
        final ok = await encodeMp4(
          framesDir: seqDir.path,
          outputPath: out,
          fps: settings.fps,
        );
        onProgress?.call(1.0, ok ? 'Done' : 'mp4 encoder unavailable');
        return ExportResult(
          path: ok ? out : seqDir.path,
          format: ok ? ExportFormat.mp4 : ExportFormat.pngSequence,
          frames: n,
        );
    }
  }

  /// mp4 hook. Enable `ffmpeg_kit_flutter_new` in pubspec.yaml and replace the
  /// body with:
  ///
  /// ```dart
  /// final session = await FFmpegKit.execute(
  ///   '-y -framerate $fps -i ${framesDir}/frame_%04d.png '
  ///   '-c:v libx264 -pix_fmt yuv420p -vf "scale=trunc(iw/2)*2:trunc(ih/2)*2" '
  ///   '$outputPath');
  /// return ReturnCode.isSuccess(await session.getReturnCode());
  /// ```
  static Future<bool> encodeMp4({
    required String framesDir,
    required String outputPath,
    required int fps,
  }) async =>
      false;

  static Future<void> shareResult(ExportResult r) async {
    if (r.format == ExportFormat.pngSequence) {
      final files = Directory(r.path)
          .listSync()
          .whereType<File>()
          .map((f) => XFile(f.path))
          .toList();
      if (files.isEmpty) return;
      await SharePlus.instance.share(
        ShareParams(files: files, text: 'Animated with RigStudio'),
      );
      return;
    }
    await SharePlus.instance.share(
      ShareParams(files: [XFile(r.path)], text: 'Animated with RigStudio'),
    );
  }

  /// Saves to the device gallery. GIFs are not accepted by every platform's
  /// media store, in which case the caller should fall back to sharing.
  static Future<bool> saveToGallery(ExportResult r) async {
    try {
      if (r.format == ExportFormat.mp4) {
        await Gal.putVideo(r.path);
      } else if (r.format == ExportFormat.gif) {
        await Gal.putImage(r.path);
      } else {
        final first = Directory(r.path).listSync().whereType<File>().toList()..sort();
        if (first.isEmpty) return false;
        await Gal.putImage(first.first.path);
      }
      return true;
    } catch (_) {
      return false;
    }
  }

  // ------------------------------------------------------------------ helpers

  static Rect _clipBounds(Skeleton s, AnimationClip clip, {int samples = 12}) {
    Rect? acc;
    for (var i = 0; i < samples; i++) {
      final t = i / samples;
      final world = PoseSolver.solve(
        s,
        pose: clip.sample(t),
        offsetScale: PoseSolver.rigHeight(s),
      );
      final b = PoseSolver.posedBounds(s, world);
      acc = acc == null ? b : acc.expandToInclude(b);
    }
    final r = acc ?? Rect.fromLTWH(0, 0, s.canvasSize.width, s.canvasSize.height);
    return r.inflate(r.longestSide * 0.04);
  }

  static Future<ui.Image> _renderFrame({
    required Skeleton skeleton,
    required Map<String, ui.Image> images,
    required Map<String, BonePose> pose,
    required Rect bounds,
    required Size outSize,
    required Color? background,
    required bool watermark,
  }) async {
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder, Offset.zero & outSize);
    if (background != null) {
      canvas.drawRect(Offset.zero & outSize, Paint()..color = background);
    }
    final scale = outSize.width / bounds.width;
    canvas.save();
    canvas.scale(scale);
    canvas.translate(-bounds.left, -bounds.top);
    RigPainter(
      skeleton: skeleton,
      images: images,
      pose: pose,
      fit: false,
    ).paint(canvas, bounds.size);
    canvas.restore();

    if (watermark) _paintWatermark(canvas, outSize);

    final picture = recorder.endRecording();
    return picture.toImage(outSize.width.round(), outSize.height.round());
  }

  static void _paintWatermark(Canvas canvas, Size size) {
    final tp = TextPainter(
      text: TextSpan(
        text: 'RigStudio',
        style: TextStyle(
          color: Colors.white.withValues(alpha: 0.82),
          fontSize: size.width * 0.045,
          fontWeight: FontWeight.w700,
          shadows: const [Shadow(blurRadius: 4, color: Colors.black54)],
        ),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    tp.paint(
      canvas,
      Offset(size.width - tp.width - size.width * 0.03,
          size.height - tp.height - size.width * 0.03),
    );
  }
}

// ----------------------------------------------------------------- isolates

Uint8List _encodeGifWorker(Map<String, dynamic> a) {
  final frames = (a['frames'] as List).cast<Uint8List>();
  final w = a['width'] as int;
  final h = a['height'] as int;
  final delay = a['delay'] as int;
  final transparent = a['transparent'] as bool;

  final encoder = img.GifEncoder(samplingFactor: 10, repeat: 0);
  for (final f in frames) {
    var frame = img.Image.fromBytes(
      width: w,
      height: h,
      bytes: f.buffer,
      numChannels: 4,
      order: img.ChannelOrder.rgba,
    );
    if (transparent) {
      // GIF only has 1-bit alpha: hard-cut anything semi-transparent.
      for (final p in frame) {
        if (p.a < 128) p.setRgba(0, 0, 0, 0);
      }
    } else {
      frame = frame.convert(numChannels: 3);
    }
    encoder.addFrame(frame, duration: delay);
  }
  return encoder.finish() ?? Uint8List(0);
}

Uint8List _encodePngWorker(Map<String, dynamic> a) {
  final image = img.Image.fromBytes(
    width: a['width'] as int,
    height: a['height'] as int,
    bytes: (a['bytes'] as Uint8List).buffer,
    numChannels: 4,
    order: img.ChannelOrder.rgba,
  );
  return img.encodePng(image);
}
