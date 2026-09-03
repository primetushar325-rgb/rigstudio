import 'dart:math' as math;
import 'dart:ui';

import 'package:flutter/foundation.dart';
import 'package:image/image.dart' as img;

import '../data/standard_rig.dart';

/// ---------------------------------------------------------------------------
/// Turns regions of the source image into per-bone transparent PNGs.
///
/// * [CutService.autoCropFromTemplate] — Path A. One capsule/ellipse region per
///   bone, derived from where the user placed the standard skeleton.
/// * [CutService.lassoCrop] — Path B. A user-drawn polygon.
///
/// Both run on a background isolate and return bytes + the canvas-space rect
/// the bitmap occupies, which is all [BonePart] needs.
/// ---------------------------------------------------------------------------

class CutResult {
  const CutResult({required this.boneId, required this.pngBytes, required this.rect});
  final String boneId;
  final Uint8List pngBytes;
  final Rect rect;
}

class CutService {
  /// Crops every bone region in one isolate hop.
  static Future<List<CutResult>> autoCropFromTemplate({
    required Uint8List imageBytes,
    required RigTemplateTransform transform,
    double bleed = 1.10,
    double featherPx = 2.0,
    double alphaBlur = 1.4,
  }) async {
    final regions = <Map<String, dynamic>>[];
    for (final tb in kStandardRig) {
      final a = transform.joint(tb.id);
      final b = transform.joint(tb.id, isEnd: true);
      regions.add({
        'id': tb.id,
        'ax': a.dx,
        'ay': a.dy,
        'bx': b.dx,
        'by': b.dy,
        'halfWidth': transform.thicknessPx(tb) * 0.5 * bleed,
        'shape': tb.shape.index,
        // capsule caps: a little past the joint so limbs overlap slightly and
        // never show a gap when they rotate
        'capStart': tb.shape == RegionShape.ellipse ? 0.0 : transform.thicknessPx(tb) * 0.28,
        'capEnd': tb.shape == RegionShape.ellipse ? 0.0 : transform.thicknessPx(tb) * 0.28,
      });
    }
    final raw = await compute(cutWorker, {
      'bytes': imageBytes,
      'regions': regions,
      'feather': featherPx,
      'alphaBlur': alphaBlur,
    });
    return raw.map(_toResult).toList();
  }

  /// Crops one user-drawn polygon (canvas-space points) for [boneId].
  static Future<CutResult?> lassoCrop({
    required Uint8List imageBytes,
    required String boneId,
    required List<Offset> polygon,
    double featherPx = 1.5,
    double alphaBlur = 1.2,
  }) async {
    if (polygon.length < 3) return null;
    final raw = await compute(cutWorker, {
      'bytes': imageBytes,
      'feather': featherPx,
      'alphaBlur': alphaBlur,
      'regions': [
        {
          'id': boneId,
          'shape': -1, // polygon
          'poly': polygon.expand((p) => [p.dx, p.dy]).toList(),
        }
      ],
    });
    if (raw.isEmpty) return null;
    return _toResult(raw.first);
  }

  static CutResult _toResult(Map<String, dynamic> m) => CutResult(
        boneId: m['id'] as String,
        pngBytes: m['bytes'] as Uint8List,
        rect: Rect.fromLTWH(
          (m['l'] as num).toDouble(),
          (m['t'] as num).toDouble(),
          (m['w'] as num).toDouble(),
          (m['h'] as num).toDouble(),
        ),
      );
}

// ---------------------------------------------------------------------------
// Isolate worker
// ---------------------------------------------------------------------------

List<Map<String, dynamic>> cutWorker(Map<String, dynamic> a) {
  final src0 = img.decodeImage(a['bytes'] as Uint8List);
  if (src0 == null) return const [];
  final src = src0.numChannels < 4 ? src0.convert(numChannels: 4) : src0;
  final feather = (a['feather'] as num).toDouble();
  final alphaBlur = (a['alphaBlur'] as num?)?.toDouble() ?? 1.4;
  final regions = (a['regions'] as List).cast<Map<String, dynamic>>();

  final out = <Map<String, dynamic>>[];
  for (final r in regions) {
    final shape = (r['shape'] as num).toInt();
    late final _Mask mask;
    if (shape == -1) {
      final flat = (r['poly'] as List).cast<num>().map((e) => e.toDouble()).toList();
      mask = _PolygonMask(flat);
    } else {
      mask = _SegmentMask(
        ax: (r['ax'] as num).toDouble(),
        ay: (r['ay'] as num).toDouble(),
        bx: (r['bx'] as num).toDouble(),
        by: (r['by'] as num).toDouble(),
        halfWidth: (r['halfWidth'] as num).toDouble(),
        capStart: (r['capStart'] as num).toDouble(),
        capEnd: (r['capEnd'] as num).toDouble(),
        shape: RegionShape.values[shape],
      );
    }

    final bounds = mask.bounds(feather + 1);
    final x0 = bounds[0].floor().clamp(0, src.width - 1);
    final y0 = bounds[1].floor().clamp(0, src.height - 1);
    final x1 = bounds[2].ceil().clamp(0, src.width - 1);
    final y1 = bounds[3].ceil().clamp(0, src.height - 1);
    final w = x1 - x0 + 1;
    final h = y1 - y0 + 1;
    if (w <= 1 || h <= 1) continue;

    final dst = img.Image(width: w, height: h, numChannels: 4);
    // tight content bounds so parts don't carry big empty margins
    var tl = w, tt = h, tr = -1, tb = -1;

    for (var y = 0; y < h; y++) {
      for (var x = 0; x < w; x++) {
        final px = x0 + x, py = y0 + y;
        final cover = mask.coverage(px + 0.5, py + 0.5, feather);
        if (cover <= 0) continue;
        final p = src.getPixel(px, py);
        final alpha = (p.a.toDouble() * cover).round().clamp(0, 255);
        if (alpha == 0) continue;
        dst.setPixelRgba(x, y, p.r.toInt(), p.g.toInt(), p.b.toInt(), alpha);
        if (x < tl) tl = x;
        if (y < tt) tt = y;
        if (x > tr) tr = x;
        if (y > tb) tb = y;
      }
    }
    if (tr < 0) continue; // nothing but transparency here

    final trimmed = img.copyCrop(dst, x: tl, y: tt, width: tr - tl + 1, height: tb - tt + 1);
    if (alphaBlur > 0.05) _blurAlphaChannel(trimmed, alphaBlur);
    out.add({
      'id': r['id'],
      'bytes': img.encodePng(trimmed),
      'l': (x0 + tl).toDouble(),
      't': (y0 + tt).toDouble(),
      'w': (tr - tl + 1).toDouble(),
      'h': (tb - tt + 1).toDouble(),
    });
  }
  return out;
}

abstract class _Mask {
  /// [minX, minY, maxX, maxY] padded by [pad].
  List<double> bounds(double pad);

  /// 0..1 coverage of the pixel centre (soft edge over [feather] pixels).
  double coverage(double x, double y, double feather);
}

class _SegmentMask extends _Mask {
  _SegmentMask({
    required this.ax,
    required this.ay,
    required this.bx,
    required this.by,
    required this.halfWidth,
    required this.capStart,
    required this.capEnd,
    required this.shape,
  });

  final double ax, ay, bx, by, halfWidth, capStart, capEnd;
  final RegionShape shape;

  @override
  List<double> bounds(double pad) {
    final r = halfWidth + math.max(capStart, capEnd) + pad;
    return [
      math.min(ax, bx) - r,
      math.min(ay, by) - r,
      math.max(ax, bx) + r,
      math.max(ay, by) + r,
    ];
  }

  @override
  double coverage(double x, double y, double feather) {
    final dx = bx - ax, dy = by - ay;
    final len = math.sqrt(dx * dx + dy * dy);
    if (len < 0.001) return 0;
    final ux = dx / len, uy = dy / len;
    final rx = x - ax, ry = y - ay;
    final along = rx * ux + ry * uy; // 0..len
    final perp = (rx * -uy + ry * ux).abs();

    switch (shape) {
      case RegionShape.ellipse:
        // full ellipse centred on the segment midpoint
        final cx = (ax + bx) / 2, cy = (ay + by) / 2;
        final rrx = halfWidth, rry = len / 2 + halfWidth * 0.15;
        final lx = (x - cx) * ux + (y - cy) * uy; // along axis
        final ly = (x - cx) * -uy + (y - cy) * ux; // perpendicular
        final d = math.sqrt((lx / rry) * (lx / rry) + (ly / rrx) * (ly / rrx));
        return _soft((1 - d) * math.min(rrx, rry), feather);
      case RegionShape.roundedRect:
      case RegionShape.capsule:
        final outsideStart = -capStart - along; // >0 when before the start cap
        final outsideEnd = along - (len + capEnd);
        final axial = math.max(outsideStart, outsideEnd);
        final radial = perp - halfWidth;
        if (shape == RegionShape.capsule && along < 0) {
          final d = math.sqrt(rx * rx + ry * ry) - halfWidth;
          return _soft(-d, feather);
        }
        return _soft(-math.max(axial, radial), feather);
    }
  }

  double _soft(double signedInsideDistance, double feather) {
    if (feather <= 0) return signedInsideDistance >= 0 ? 1 : 0;
    final u = (signedInsideDistance / feather + 0.5).clamp(0.0, 1.0);
    return u * u * (3 - 2 * u);
  }
}

class _PolygonMask extends _Mask {
  _PolygonMask(this.flat);
  final List<double> flat; // x0,y0,x1,y1,...

  int get n => flat.length ~/ 2;
  double px(int i) => flat[i * 2];
  double py(int i) => flat[i * 2 + 1];

  @override
  List<double> bounds(double pad) {
    var minX = double.infinity, minY = double.infinity;
    var maxX = -double.infinity, maxY = -double.infinity;
    for (var i = 0; i < n; i++) {
      minX = math.min(minX, px(i));
      minY = math.min(minY, py(i));
      maxX = math.max(maxX, px(i));
      maxY = math.max(maxY, py(i));
    }
    return [minX - pad, minY - pad, maxX + pad, maxY + pad];
  }

  @override
  double coverage(double x, double y, double feather) {
    final inside = _contains(x, y);
    if (feather <= 0) return inside ? 1 : 0;
    // cheap soft edge: distance to the nearest polygon edge
    var best = double.infinity;
    for (var i = 0; i < n; i++) {
      final j = (i + 1) % n;
      best = math.min(best, _distToSeg(x, y, px(i), py(i), px(j), py(j)));
    }
    final signed = inside ? best : -best;
    final u = (signed / feather + 0.5).clamp(0.0, 1.0);
    return u * u * (3 - 2 * u);
  }

  bool _contains(double x, double y) {
    var inside = false;
    for (var i = 0, j = n - 1; i < n; j = i++) {
      final xi = px(i), yi = py(i), xj = px(j), yj = py(j);
      if (((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / ((yj - yi) + 1e-12) + xi)) {
        inside = !inside;
      }
    }
    return inside;
  }

  double _distToSeg(double x, double y, double ax, double ay, double bx, double by) {
    final dx = bx - ax, dy = by - ay;
    final l2 = dx * dx + dy * dy;
    if (l2 < 1e-9) return math.sqrt((x - ax) * (x - ax) + (y - ay) * (y - ay));
    var t = ((x - ax) * dx + (y - ay) * dy) / l2;
    t = t.clamp(0.0, 1.0);
    final cx = ax + t * dx, cy = ay + t * dy;
    return math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy));
  }
}

/// Gaussian-blurs ONLY the alpha channel of [im] (RGB untouched). This softens
/// the 1-2px edge a chroma-key or mask leaves behind, which removes the hard
/// "green fringe" halo on the finished body part. Kept lightweight because it
/// runs inside the cut isolate.
void _blurAlphaChannel(img.Image im, double sigma) {
  final w = im.width, h = im.height;
  if (w < 3 || h < 3 || sigma <= 0) return;
  final r = sigma.clamp(0.5, 2.0).ceil();
  final n = r * 2 + 1;
  final kernel = List<double>.filled(n, 0);
  var sum = 0.0;
  for (var i = -r; i <= r; i++) {
    final g = math.exp(-(i * i) / (2 * sigma * sigma));
    kernel[i + r] = g;
    sum += g;
  }
  for (var i = 0; i < n; i++) {
    kernel[i] /= sum;
  }

  final srcA = List<double>.generate(w * h, (i) => im.getPixel(i % w, i ~/ w).a.toDouble());
  final tmp = List<double>.filled(w * h, 0);

  // horizontal pass
  for (var y = 0; y < h; y++) {
    final row = y * w;
    for (var x = 0; x < w; x++) {
      var acc = 0.0;
      for (var k = -r; k <= r; k++) {
        final xx = (x + k).clamp(0, w - 1);
        acc += srcA[row + xx] * kernel[k + r];
      }
      tmp[row + x] = acc;
    }
  }
  // vertical pass
  for (var x = 0; x < w; x++) {
    for (var y = 0; y < h; y++) {
      var acc = 0.0;
      for (var k = -r; k <= r; k++) {
        final yy = (y + k).clamp(0, h - 1);
        acc += tmp[yy * w + x] * kernel[k + r];
      }
      final p = im.getPixel(x, y);
      im.setPixelRgba(x, y, p.r.toInt(), p.g.toInt(), p.b.toInt(),
          acc.round().clamp(0, 255));
    }
  }
}
