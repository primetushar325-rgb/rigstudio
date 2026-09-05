import 'dart:math' as math;

import 'package:flutter/material.dart';

/// Dependency-free HSV colour wheel + value slider. Used by the background
/// picker and the chroma-key colour picker.
class ColorWheelPicker extends StatefulWidget {
  const ColorWheelPicker({
    super.key,
    required this.color,
    required this.onChanged,
    this.size = 220,
  });

  final Color color;
  final ValueChanged<Color> onChanged;
  final double size;

  @override
  State<ColorWheelPicker> createState() => _ColorWheelPickerState();
}

class _ColorWheelPickerState extends State<ColorWheelPicker> {
  late HSVColor _hsv = HSVColor.fromColor(widget.color);

  @override
  void didUpdateWidget(covariant ColorWheelPicker oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.color != widget.color) {
      final incoming = HSVColor.fromColor(widget.color);
      if (incoming.toColor() != _hsv.toColor()) _hsv = incoming;
    }
  }

  void _emit(HSVColor v) {
    setState(() => _hsv = v);
    widget.onChanged(v.toColor());
  }

  void _handle(Offset local) {
    final r = widget.size / 2;
    final v = local - Offset(r, r);
    final dist = (v.distance / r).clamp(0.0, 1.0);
    var angle = math.atan2(v.dy, v.dx) * 180 / math.pi;
    if (angle < 0) angle += 360;
    _emit(_hsv.withHue(angle).withSaturation(dist));
  }

  @override
  Widget build(BuildContext context) {
    final r = widget.size / 2;
    final knob = Offset(r, r) +
        Offset(math.cos(_hsv.hue * math.pi / 180), math.sin(_hsv.hue * math.pi / 180)) *
            (_hsv.saturation * r);
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        GestureDetector(
          onPanDown: (d) => _handle(d.localPosition),
          onPanUpdate: (d) => _handle(d.localPosition),
          child: SizedBox(
            width: widget.size,
            height: widget.size,
            child: CustomPaint(
              painter: _WheelPainter(value: _hsv.value, knob: knob),
            ),
          ),
        ),
        const SizedBox(height: 12),
        Row(
          children: [
            const Icon(Icons.brightness_6, size: 18),
            Expanded(
              child: Slider(
                value: _hsv.value,
                onChanged: (v) => _emit(_hsv.withValue(v)),
              ),
            ),
            Container(
              width: 32,
              height: 32,
              decoration: BoxDecoration(
                color: _hsv.toColor(),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: Colors.white24),
              ),
            ),
          ],
        ),
      ],
    );
  }
}

class _WheelPainter extends CustomPainter {
  _WheelPainter({required this.value, required this.knob});
  final double value;
  final Offset knob;

  @override
  void paint(Canvas canvas, Size size) {
    final center = size.center(Offset.zero);
    final radius = size.width / 2;
    final rect = Rect.fromCircle(center: center, radius: radius);

    final hue = SweepGradient(
      colors: [
        for (var i = 0; i <= 360; i += 30) HSVColor.fromAHSV(1, i % 360.0, 1, value).toColor(),
      ],
    ).createShader(rect);
    canvas.drawCircle(center, radius, Paint()..shader = hue);

    final sat = RadialGradient(
      colors: [
        HSVColor.fromAHSV(1, 0, 0, value).toColor(),
        HSVColor.fromAHSV(0, 0, 0, value).toColor(),
      ],
    ).createShader(rect);
    canvas.drawCircle(center, radius, Paint()..shader = sat);

    canvas.drawCircle(
      knob,
      9,
      Paint()
        ..color = Colors.white
        ..style = PaintingStyle.stroke
        ..strokeWidth = 3,
    );
  }

  @override
  bool shouldRepaint(covariant _WheelPainter old) =>
      old.value != value || old.knob != knob;
}

/// Row of quick background swatches + "custom" + "transparent".
class BackgroundPickerBar extends StatelessWidget {
  const BackgroundPickerBar({
    super.key,
    required this.color,
    required this.transparent,
    required this.onPicked,
  });

  final Color color;
  final bool transparent;

  /// `null` colour = transparent background.
  final void Function(Color? color) onPicked;

  static const List<Color> presets = [
    Color(0xFF00B140), // chroma green
    Color(0xFF000000),
    Color(0xFFFFFFFF),
    Color(0xFF101216),
    Color(0xFF1E88E5),
  ];

  @override
  Widget build(BuildContext context) {
    Widget swatch({Color? c, required Widget child, required bool selected, VoidCallback? onTap}) {
      return GestureDetector(
        onTap: onTap,
        child: Container(
          width: 40,
          height: 40,
          margin: const EdgeInsets.only(right: 10),
          decoration: BoxDecoration(
            color: c,
            borderRadius: BorderRadius.circular(10),
            border: Border.all(
              color: selected ? Colors.amber : Colors.white24,
              width: selected ? 3 : 1,
            ),
          ),
          child: child,
        ),
      );
    }

    return SizedBox(
      height: 48,
      child: ListView(
        scrollDirection: Axis.horizontal,
        children: [
          for (final c in presets)
            swatch(
              c: c,
              selected: !transparent && c.toARGB32() == color.toARGB32(),
              onTap: () => onPicked(c),
              child: const SizedBox.shrink(),
            ),
          swatch(
            selected: transparent,
            onTap: () => onPicked(null),
            child: const Icon(Icons.grid_on, size: 18),
          ),
          swatch(
            c: color,
            selected: false,
            onTap: () async {
              var picked = color;
              final ok = await showDialog<bool>(
                context: context,
                builder: (ctx) => AlertDialog(
                  title: const Text('Custom colour'),
                  content: SingleChildScrollView(
                    child: ColorWheelPicker(
                      color: color,
                      onChanged: (c) => picked = c,
                    ),
                  ),
                  actions: [
                    TextButton(
                        onPressed: () => Navigator.pop(ctx, false),
                        child: const Text('Cancel')),
                    FilledButton(
                        onPressed: () => Navigator.pop(ctx, true),
                        child: const Text('Use colour')),
                  ],
                ),
              );
              if (ok == true) onPicked(picked);
            },
            child: const Icon(Icons.colorize, size: 18),
          ),
        ],
      ),
    );
  }
}
