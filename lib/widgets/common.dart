import 'package:flutter/material.dart';

/// Maps between canvas (image pixel) space and widget space for a
/// contain-fitted image. Every interactive rig screen uses this so gestures and
/// painting agree on coordinates.
class CanvasFit {
  const CanvasFit({required this.scale, required this.offset, required this.canvasSize});

  final double scale;
  final Offset offset;
  final Size canvasSize;

  factory CanvasFit.contain(Size canvas, Size widget, {double padding = 8}) {
    if (canvas.width <= 0 || canvas.height <= 0) {
      return CanvasFit(scale: 1, offset: Offset.zero, canvasSize: canvas);
    }
    final sx = (widget.width - padding * 2) / canvas.width;
    final sy = (widget.height - padding * 2) / canvas.height;
    final s = sx < sy ? sx : sy;
    return CanvasFit(
      scale: s,
      offset: Offset(
        (widget.width - canvas.width * s) / 2,
        (widget.height - canvas.height * s) / 2,
      ),
      canvasSize: canvas,
    );
  }

  Offset toWidget(Offset canvasPoint) => canvasPoint * scale + offset;

  Offset toCanvas(Offset widgetPoint) => (widgetPoint - offset) / scale;

  Rect toWidgetRect(Rect r) => Rect.fromLTWH(
        r.left * scale + offset.dx,
        r.top * scale + offset.dy,
        r.width * scale,
        r.height * scale,
      );
}

/// Full-screen "working…" veil used while isolates crunch pixels.
class BusyOverlay extends StatelessWidget {
  const BusyOverlay({super.key, required this.busy, this.status, required this.child});

  final bool busy;
  final String? status;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        child,
        if (busy)
          Positioned.fill(
            child: ColoredBox(
              color: Colors.black.withValues(alpha: 0.6),
              child: Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const CircularProgressIndicator(),
                    if (status != null) ...[
                      const SizedBox(height: 16),
                      Text(status!, style: const TextStyle(color: Colors.white)),
                    ],
                  ],
                ),
              ),
            ),
          ),
      ],
    );
  }
}

/// Small labelled slider used across the tool panels.
class LabeledSlider extends StatelessWidget {
  const LabeledSlider({
    super.key,
    required this.label,
    required this.value,
    required this.onChanged,
    this.min = 0,
    this.max = 1,
    this.digits = 2,
    this.suffix = '',
    this.divisions,
  });

  final String label;
  final double value;
  final ValueChanged<double> onChanged;
  final double min;
  final double max;
  final int digits;
  final String suffix;
  final int? divisions;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        SizedBox(width: 96, child: Text(label, style: const TextStyle(fontSize: 13))),
        Expanded(
          child: Slider(
            value: value.clamp(min, max),
            min: min,
            max: max,
            divisions: divisions,
            onChanged: onChanged,
          ),
        ),
        SizedBox(
          width: 56,
          child: Text('${value.toStringAsFixed(digits)}$suffix',
              textAlign: TextAlign.end, style: const TextStyle(fontSize: 12)),
        ),
      ],
    );
  }
}

class PremiumChip extends StatelessWidget {
  const PremiumChip({super.key, this.label = 'PRO'});
  final String label;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
        decoration: BoxDecoration(
          gradient: const LinearGradient(colors: [Color(0xFFFFC46B), Color(0xFFFF8A3D)]),
          borderRadius: BorderRadius.circular(20),
        ),
        child: Text(label,
            style: const TextStyle(
                fontSize: 10, fontWeight: FontWeight.w900, color: Colors.black)),
      );
}
