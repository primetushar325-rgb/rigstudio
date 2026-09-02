import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/painting.dart';
import 'package:image/image.dart' as img;

/// ---------------------------------------------------------------------------
/// Chroma key (solid-colour background removal), 100% on-device.
///
/// Distance is measured in chroma (Cb/Cr) space rather than plain RGB, which
/// keeps shadows and shading on the character while still killing a flat green
/// / blue / any-colour backdrop.
/// ---------------------------------------------------------------------------
class ChromaKeyParams {
  const ChromaKeyParams({
    this.keyColor = const Color(0xFF00B140), // broadcast green
    this.tolerance = 0.28,
    this.feather = 0.16,
    this.despill = true,
  });

  final Color keyColor;

  /// 0..1 — everything closer than this to the key colour becomes transparent.
  final double tolerance;

  /// 0..1 — width of the soft edge beyond [tolerance].
  final double feather;

  /// Removes the colour cast the backdrop bounces onto the subject's edges.
  final bool despill;

  ChromaKeyParams copyWith({
    Color? keyColor,
    double? tolerance,
    double? feather,
    bool? despill,
  }) =>
      ChromaKeyParams(
        keyColor: keyColor ?? this.keyColor,
        tolerance: tolerance ?? this.tolerance,
        feather: feather ?? this.feather,
        despill: despill ?? this.despill,
      );

  Map<String, dynamic> toMap() => {
        'r': (keyColor.r * 255).round(),
        'g': (keyColor.g * 255).round(),
        'b': (keyColor.b * 255).round(),
        'tolerance': tolerance,
        'feather': feather,
        'despill': despill,
      };
}

class ChromaKeyRequest {
  const ChromaKeyRequest(this.pngBytes, this.params, {this.maxDimension});
  final Uint8List pngBytes;
  final ChromaKeyParams params;

  /// Downscale first (used for the live preview so the slider stays at 60fps).
  final int? maxDimension;

  Map<String, dynamic> toMap() => {
        'bytes': pngBytes,
        'maxDimension': maxDimension,
        ...params.toMap(),
      };
}

class ChromaKeyService {
  /// Runs the key on a background isolate and returns transparent PNG bytes.
  static Future<Uint8List> run(ChromaKeyRequest request) =>
      compute(chromaKeyWorker, request.toMap());

  /// Guesses the backdrop colour from the image corners (median of samples).
  static Future<Color> guessKeyColor(Uint8List pngBytes) async {
    final rgb = await compute(_guessKeyWorker, pngBytes);
    return Color.fromARGB(255, rgb[0], rgb[1], rgb[2]);
  }
}

/// Top-level so it can run inside `compute()`.
Uint8List chromaKeyWorker(Map<String, dynamic> a) {
  final src0 = img.decodeImage(a['bytes'] as Uint8List);
  if (src0 == null) return a['bytes'] as Uint8List;

  img.Image src = src0;
  final maxDim = a['maxDimension'] as int?;
  if (maxDim != null && (src.width > maxDim || src.height > maxDim)) {
    src = src.width >= src.height
        ? img.copyResize(src, width: maxDim)
        : img.copyResize(src, height: maxDim);
  }
  if (src.numChannels < 4) {
    src = src.convert(numChannels: 4);
  }

  final kr = (a['r'] as num).toDouble();
  final kg = (a['g'] as num).toDouble();
  final kb = (a['b'] as num).toDouble();
  final tolerance = (a['tolerance'] as num).toDouble();
  final feather = (a['feather'] as num).toDouble();
  final despill = a['despill'] as bool;

  // Key colour in chroma space.
  final kCb = -0.168736 * kr - 0.331264 * kg + 0.5 * kb;
  final kCr = 0.5 * kr - 0.418688 * kg - 0.081312 * kb;

  // Max meaningful chroma distance (~180 for 8-bit) normalises tolerance 0..1.
  const maxDist = 180.0;
  final t0 = tolerance * maxDist;
  final t1 = t0 + feather.clamp(0.001, 1.0) * maxDist;

  for (final p in src) {
    final r = p.r.toDouble(), g = p.g.toDouble(), b = p.b.toDouble();
    final cb = -0.168736 * r - 0.331264 * g + 0.5 * b;
    final cr = 0.5 * r - 0.418688 * g - 0.081312 * b;
    final d = _hypot(cb - kCb, cr - kCr);

    double alpha;
    if (d <= t0) {
      alpha = 0;
    } else if (d >= t1) {
      alpha = 1;
    } else {
      final u = (d - t0) / (t1 - t0);
      alpha = u * u * (3 - 2 * u); // smoothstep edge
    }

    if (alpha <= 0) {
      p.setRgba(0, 0, 0, 0);
      continue;
    }

    var nr = r, ng = g, nb = b;
    if (despill && alpha < 1.0) {
      // Pull the edge pixel away from the key hue proportionally to how much
      // of the backdrop is still mixed into it.
      final mix = 1.0 - alpha;
      nr = r - (kr - 128) * mix * 0.5;
      ng = g - (kg - 128) * mix * 0.5;
      nb = b - (kb - 128) * mix * 0.5;
    }
    p.setRgba(
      nr.clamp(0, 255).round(),
      ng.clamp(0, 255).round(),
      nb.clamp(0, 255).round(),
      (alpha * p.a.toDouble()).clamp(0, 255).round(),
    );
  }

  return img.encodePng(src);
}

List<int> _guessKeyWorker(Uint8List bytes) {
  final src = img.decodeImage(bytes);
  if (src == null) return [0, 177, 64];
  final samples = <List<int>>[];
  const inset = 4;
  final pts = <List<int>>[
    [inset, inset],
    [src.width - 1 - inset, inset],
    [inset, src.height - 1 - inset],
    [src.width - 1 - inset, src.height - 1 - inset],
    [src.width ~/ 2, inset],
    [src.width ~/ 2, src.height - 1 - inset],
  ];
  for (final pt in pts) {
    final x = pt[0].clamp(0, src.width - 1);
    final y = pt[1].clamp(0, src.height - 1);
    final p = src.getPixel(x, y);
    samples.add([p.r.round(), p.g.round(), p.b.round()]);
  }
  samples.sort((a, b) => (a[0] + a[1] + a[2]).compareTo(b[0] + b[1] + b[2]));
  return samples[samples.length ~/ 2];
}

double _hypot(double a, double b) => math.sqrt(a * a + b * b);
