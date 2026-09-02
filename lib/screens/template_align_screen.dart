import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/standard_rig.dart';
import '../state/app_state.dart';
import '../widgets/common.dart';
import 'layers_screen.dart';

/// PATH A — align the standard skeleton over the artwork, then auto-cut.
///
/// One finger = move the rig, two fingers = scale + rotate. Switch to "Joints"
/// mode to nudge individual joints when a character has unusual proportions.
class TemplateAlignScreen extends ConsumerStatefulWidget {
  const TemplateAlignScreen({super.key});

  @override
  ConsumerState<TemplateAlignScreen> createState() => _TemplateAlignScreenState();
}

class _TemplateAlignScreenState extends ConsumerState<TemplateAlignScreen> {
  bool _jointMode = false;
  bool _showRegions = true;
  String? _grabbedJoint;

  // gesture start state
  late Offset _startCenter;
  late double _startScale;
  late double _startRotation;
  late Offset _startFocalCanvas;
  Offset _startTweak = Offset.zero;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final editor = ref.read(editorProvider);
      if (editor.template == null && editor.workingImage != null) {
        ref
            .read(editorProvider.notifier)
            .updateTemplate(RigTemplateTransform.fitTo(editor.canvasSize));
      }
    });
  }

  String? _hitJoint(Offset canvasPoint, RigTemplateTransform t, double pxPerCanvas) {
    final radius = 22 / pxPerCanvas;
    String? best;
    var bestD = double.infinity;
    for (final tb in kStandardRig) {
      final candidates = <String, Offset>{
        tb.id: t.joint(tb.id),
        if (!kStandardRig.any((c) => c.parentId == tb.id)) '${tb.id}#end': t.joint(tb.id, isEnd: true),
      };
      candidates.forEach((key, p) {
        final d = (p - canvasPoint).distance;
        if (d < radius && d < bestD) {
          bestD = d;
          best = key;
        }
      });
    }
    return best;
  }

  @override
  Widget build(BuildContext context) {
    final editor = ref.watch(editorProvider);
    final notifier = ref.read(editorProvider.notifier);
    final image = editor.workingImage;
    final template = editor.template;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Align skeleton'),
        actions: [
          IconButton(
            tooltip: 'Show cut regions',
            icon: Icon(_showRegions ? Icons.crop_free : Icons.crop_free_outlined,
                color: _showRegions ? Colors.amber : null),
            onPressed: () => setState(() => _showRegions = !_showRegions),
          ),
          IconButton(
            tooltip: 'Reset placement',
            icon: const Icon(Icons.restart_alt),
            onPressed: () => notifier.updateTemplate(
                RigTemplateTransform.fitTo(editor.canvasSize)),
          ),
        ],
      ),
      body: BusyOverlay(
        busy: editor.busy,
        status: editor.status ?? 'Working…',
        child: Column(
          children: [
            Expanded(
              child: (image == null || template == null)
                  ? const Center(child: CircularProgressIndicator())
                  : LayoutBuilder(
                      builder: (context, constraints) {
                        final fit = CanvasFit.contain(
                          editor.canvasSize,
                          Size(constraints.maxWidth, constraints.maxHeight),
                          padding: 16,
                        );
                        return GestureDetector(
                          behavior: HitTestBehavior.opaque,
                          onScaleStart: (d) {
                            _startCenter = template.center;
                            _startScale = template.scale;
                            _startRotation = template.rotation;
                            _startFocalCanvas = fit.toCanvas(d.localFocalPoint);
                            _grabbedJoint = _jointMode
                                ? _hitJoint(_startFocalCanvas, template, fit.scale)
                                : null;
                            _startTweak = _grabbedJoint == null
                                ? Offset.zero
                                : (template.jointTweaks[_grabbedJoint!] ?? Offset.zero);
                            setState(() {});
                          },
                          onScaleUpdate: (d) {
                            final focalCanvas = fit.toCanvas(d.localFocalPoint);
                            final t = template.clone();
                            if (_grabbedJoint != null) {
                              t.jointTweaks[_grabbedJoint!] =
                                  _startTweak + (focalCanvas - _startFocalCanvas);
                            } else {
                              t.center = _startCenter + (focalCanvas - _startFocalCanvas);
                              t.scale = (_startScale * d.scale).clamp(40.0, 12000.0);
                              t.rotation = _startRotation + d.rotation;
                            }
                            notifier.updateTemplate(t);
                          },
                          onScaleEnd: (_) => setState(() => _grabbedJoint = null),
                          child: CustomPaint(
                            painter: _TemplatePainter(
                              image: image,
                              transform: template,
                              fit: fit,
                              showRegions: _showRegions,
                              grabbed: _grabbedJoint,
                              jointMode: _jointMode,
                            ),
                            size: Size(constraints.maxWidth, constraints.maxHeight),
                          ),
                        );
                      },
                    ),
            ),
            _Toolbar(
              jointMode: _jointMode,
              onJointMode: (v) => setState(() => _jointMode = v),
              onConfirm: () async {
                await notifier.commitTemplateAndAutoCrop();
                if (!context.mounted) return;
                Navigator.pushReplacement(
                    context, MaterialPageRoute(builder: (_) => const LayersScreen()));
              },
            ),
          ],
        ),
      ),
    );
  }
}

class _Toolbar extends StatelessWidget {
  const _Toolbar({
    required this.jointMode,
    required this.onJointMode,
    required this.onConfirm,
  });

  final bool jointMode;
  final ValueChanged<bool> onJointMode;
  final VoidCallback onConfirm;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(
        color: Color(0xFF13161D),
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            SegmentedButton<bool>(
              segments: const [
                ButtonSegment(
                    value: false,
                    icon: Icon(Icons.open_with),
                    label: Text('Whole rig')),
                ButtonSegment(
                    value: true,
                    icon: Icon(Icons.control_point),
                    label: Text('Joints')),
              ],
              selected: {jointMode},
              onSelectionChanged: (s) => onJointMode(s.first),
            ),
            const SizedBox(height: 8),
            Text(
              jointMode
                  ? 'Drag any joint dot to fine-tune a limb.'
                  : 'Drag to move · pinch to scale · twist to rotate.',
              style: const TextStyle(color: Colors.white54, fontSize: 12),
            ),
            const SizedBox(height: 10),
            SizedBox(
              width: double.infinity,
              child: FilledButton.icon(
                onPressed: onConfirm,
                icon: const Icon(Icons.content_cut),
                label: const Text('Confirm & auto-cut parts'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _TemplatePainter extends CustomPainter {
  _TemplatePainter({
    required this.image,
    required this.transform,
    required this.fit,
    required this.showRegions,
    required this.grabbed,
    required this.jointMode,
  });

  final ui.Image image;
  final RigTemplateTransform transform;
  final CanvasFit fit;
  final bool showRegions;
  final String? grabbed;
  final bool jointMode;

  @override
  void paint(Canvas canvas, Size size) {
    // artwork
    final dst = fit.toWidgetRect(Offset.zero & fit.canvasSize);
    canvas.drawImageRect(
      image,
      Rect.fromLTWH(0, 0, image.width.toDouble(), image.height.toDouble()),
      dst,
      Paint()..filterQuality = FilterQuality.medium,
    );
    canvas.drawRect(dst, Paint()
      ..color = Colors.white12
      ..style = PaintingStyle.stroke);

    // bone regions (exactly what auto-cut will crop)
    for (final tb in kStandardRig) {
      final a = fit.toWidget(transform.joint(tb.id));
      final b = fit.toWidget(transform.joint(tb.id, isEnd: true));
      final w = transform.thicknessPx(tb) * fit.scale;

      if (showRegions) {
        final regionPaint = Paint()
          ..color = Colors.cyanAccent.withValues(alpha: 0.16)
          ..style = PaintingStyle.fill;
        if (tb.shape == RegionShape.ellipse) {
          final c = Offset((a.dx + b.dx) / 2, (a.dy + b.dy) / 2);
          final len = (b - a).distance;
          canvas.save();
          canvas.translate(c.dx, c.dy);
          canvas.rotate(math.atan2(b.dy - a.dy, b.dx - a.dx) - math.pi / 2);
          canvas.drawOval(
            Rect.fromCenter(center: Offset.zero, width: w * 1.05, height: len + w * 0.3),
            regionPaint,
          );
          canvas.restore();
        } else {
          canvas.drawLine(
            a,
            b,
            Paint()
              ..color = Colors.cyanAccent.withValues(alpha: 0.16)
              ..strokeWidth = w
              ..strokeCap = tb.shape == RegionShape.capsule
                  ? StrokeCap.round
                  : StrokeCap.square,
          );
        }
      }

      // bone line
      canvas.drawLine(
        a,
        b,
        Paint()
          ..color = Colors.amberAccent.withValues(alpha: 0.9)
          ..strokeWidth = 2.5
          ..strokeCap = StrokeCap.round,
      );
    }

    // joints
    for (final tb in kStandardRig) {
      final isLeaf = !kStandardRig.any((c) => c.parentId == tb.id);
      final pts = <String, Offset>{
        tb.id: transform.joint(tb.id),
        if (isLeaf) '${tb.id}#end': transform.joint(tb.id, isEnd: true),
      };
      pts.forEach((key, p) {
        final w = fit.toWidget(p);
        final selected = key == grabbed;
        canvas.drawCircle(
            w,
            selected ? 11 : (jointMode ? 8 : 5),
            Paint()..color = selected ? Colors.amber : Colors.white);
        canvas.drawCircle(
            w,
            selected ? 11 : (jointMode ? 8 : 5),
            Paint()
              ..color = Colors.black54
              ..style = PaintingStyle.stroke
              ..strokeWidth = 1.5);
      });
    }
  }

  @override
  bool shouldRepaint(covariant _TemplatePainter old) => true;
}
