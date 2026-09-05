import 'dart:convert';
import 'dart:math' as math;
import 'dart:ui';

/// ---------------------------------------------------------------------------
/// Small JSON helpers for dart:ui geometry types. Kept in one place so every
/// model file serialises geometry identically.
/// ---------------------------------------------------------------------------

Map<String, dynamic> offsetToJson(Offset o) => {'x': o.dx, 'y': o.dy};

Offset offsetFromJson(Map<String, dynamic>? j) =>
    j == null ? Offset.zero : Offset((j['x'] as num).toDouble(), (j['y'] as num).toDouble());

Map<String, dynamic> sizeToJson(Size s) => {'w': s.width, 'h': s.height};

Size sizeFromJson(Map<String, dynamic>? j) =>
    j == null ? Size.zero : Size((j['w'] as num).toDouble(), (j['h'] as num).toDouble());

Map<String, dynamic> rectToJson(Rect r) =>
    {'l': r.left, 't': r.top, 'w': r.width, 'h': r.height};

Rect rectFromJson(Map<String, dynamic>? j) => j == null
    ? Rect.zero
    : Rect.fromLTWH(
        (j['l'] as num).toDouble(),
        (j['t'] as num).toDouble(),
        (j['w'] as num).toDouble(),
        (j['h'] as num).toDouble(),
      );

String prettyJson(Object? o) => const JsonEncoder.withIndent('  ').convert(o);

double degToRad(double d) => d * math.pi / 180.0;
double radToDeg(double r) => r * 180.0 / math.pi;
