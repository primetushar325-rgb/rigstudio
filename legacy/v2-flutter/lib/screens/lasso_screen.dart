import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/standard_rig.dart';
import '../state/app_state.dart';
import '../widgets/common.dart';

/// PATH B — polygon/lasso cutting. Tap to drop points around a body part,
/// close the loop, choose the bone it belongs to, cut.
///
/// Opened either standalone (full manual rig) or with [initialBoneId] set, in
/// which case it refines exactly one part from the layers screen.
class LassoScreen extends ConsumerStatefulWidget {
  const LassoScreen({super.key, this.initialBoneId});

  final String? initialBoneId;

  @override
  ConsumerState<LassoScreen> createState() => _LassoScreenState();
}

class _LassoScreenState extends ConsumerState<LassoScreen> {
  final List<Offset> _points = []; // canvas space
  late String _boneId = widget.initialBoneId ?? 'head';
  bool _closed = false;

  @override
  Widget build(BuildContext context) {
    final editor = ref.watch(editorProvider);
    final notifier = ref.read(editorProvider.notifier);
    final image = editor.workingImage;
    final skeleton = editor.skeleton;

    return Scaffold(
      appBar: AppBar(
        title: Text(widget.initialBoneId == null ? 'Manual cut' : 'Refine part'),
        actions: [
          IconButton(
            tooltip: 'Undo point',
            icon: const Icon(Icons.undo),
            onPressed: _points.isEmpty
                ? null
                : () => setState(() {
                      _points.removeLast();
                      _closed = false;
                    }),
          ),
          IconButton(
            tooltip: 'Clear',
            icon: const Icon(Icons.clear),
            onPressed: () => setState(() {
              _points.clear();
              _closed = false;
            }),
          ),
        ],
      ),
      body: BusyOverlay(
        busy: editor.busy,
        status: editor.status,
        child: Column(
          children: [
            Expanded(
              child: image == null
                  ? const Center(child: CircularProgressIndicator())
                  : LayoutBuilder(
                      builder: (context, constraints) {
                        final fit = CanvasFit.contain(
                          editor.canvasSize,
                          Size(constraints.maxWidth, constraints.maxHeight),
                          padding: 12,
                        );
                        return GestureDetector(
                          behavior: HitTestBehavior.opaque,
                          onTapDown: (d) {
                            final p = fit.toCanvas(d.localPosition);
                            setState(() {
                              if (_points.length > 2 &&
                                  (fit.toWidget(_points.first) - d.localPosition)
                                          .distance <
                                      20) {
                                _closed = true;
                              } else {
                                _points.add(p);
                                _closed = false;
                              }
                            });
                          },
                          onPanUpdate: (d) {
                            // drag freehand — append while the finger moves
                            final p = fit.toCanvas(d.localPosition);
                            if (_points.isEmpty ||
                                (_points.last - p).distance * fit.scale > 12) {
                              setState(() => _points.add(p));
                            }
                          },
                          child: CustomPaint(
                            painter: _LassoPainter(
                              image: image,
                              fit: fit,
                              points: _points,
                              closed: _closed,
                            ),
                            size: Size(constraints.maxWidth, constraints.maxHeight),
                          ),
                        );
                      },
                    ),
            ),
            Container(
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
                    Row(
                      children: [
                        const Text('Assign to bone  '),
                        Expanded(
                          child: DropdownButton<String>(
                            isExpanded: true,
                            value: _boneId,
                            items: [
                              for (final tb in kStandardRig)
                                DropdownMenuItem(
                                  value: tb.id,
                                  child: Row(
                                    children: [
                                      Expanded(child: Text(tb.label)),
                                      if (skeleton?.byId(tb.id)?.isCut ?? false)
                                        const Icon(Icons.check_circle,
                                            size: 16, color: Colors.greenAccent),
                                    ],
                                  ),
                                ),
                            ],
                            onChanged: (v) => setState(() => _boneId = v ?? _boneId),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 4),
                    Text(
                      _points.length < 3
                          ? 'Tap around the body part to place points (or drag to trace).'
                          : _closed
                              ? '${_points.length} points · loop closed'
                              : '${_points.length} points · tap the first point to close',
                      style: const TextStyle(fontSize: 12, color: Colors.white54),
                    ),
                    const SizedBox(height: 10),
                    Row(
                      children: [
                        Expanded(
                          child: OutlinedButton(
                            onPressed: _points.length < 3
                                ? null
                                : () => setState(() => _closed = true),
                            child: const Text('Close loop'),
                          ),
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: FilledButton.icon(
                            onPressed: _points.length < 3
                                ? null
                                : () async {
                                    await notifier.applyLasso(_boneId, _points);
                                    if (!context.mounted) return;
                                    if (widget.initialBoneId != null) {
                                      Navigator.pop(context);
                                    } else {
                                      setState(() {
                                        _points.clear();
                                        _closed = false;
                                        _boneId = _nextUncut() ?? _boneId;
                                      });
                                    }
                                  },
                            icon: const Icon(Icons.content_cut),
                            label: const Text('Cut part'),
                          ),
                        ),
                      ],
                    ),
                    if (widget.initialBoneId == null && skeleton != null) ...[
                      const SizedBox(height: 10),
                      _Checklist(skeleton: skeleton),
                    ],
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  String? _nextUncut() {
    final s = ref.read(editorProvider).skeleton;
    if (s == null) return null;
    for (final tb in kStandardRig) {
      final b = s.byId(tb.id);
      if (b != null && b.required_ && !b.isCut) return tb.id;
    }
    return null;
  }
}

class _Checklist extends StatelessWidget {
  const _Checklist({required this.skeleton});
  final dynamic skeleton;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 34,
      child: ListView(
        scrollDirection: Axis.horizontal,
        children: [
          for (final tb in kStandardRig)
            Padding(
              padding: const EdgeInsets.only(right: 6),
              child: Chip(
                visualDensity: VisualDensity.compact,
                labelStyle: const TextStyle(fontSize: 10),
                avatar: Icon(
                  (skeleton.byId(tb.id)?.isCut ?? false)
                      ? Icons.check_circle
                      : Icons.radio_button_unchecked,
                  size: 14,
                  color: (skeleton.byId(tb.id)?.isCut ?? false)
                      ? Colors.greenAccent
                      : Colors.white38,
                ),
                label: Text(tb.id),
              ),
            ),
        ],
      ),
    );
  }
}

class _LassoPainter extends CustomPainter {
  _LassoPainter({
    required this.image,
    required this.fit,
    required this.points,
    required this.closed,
  });

  final ui.Image image;
  final CanvasFit fit;
  final List<Offset> points;
  final bool closed;

  @override
  void paint(Canvas canvas, Size size) {
    final dst = fit.toWidgetRect(Offset.zero & fit.canvasSize);
    canvas.drawImageRect(
      image,
      Rect.fromLTWH(0, 0, image.width.toDouble(), image.height.toDouble()),
      dst,
      Paint()..filterQuality = FilterQuality.medium,
    );
    if (points.isEmpty) return;

    final path = Path()..moveTo(fit.toWidget(points.first).dx, fit.toWidget(points.first).dy);
    for (final p in points.skip(1)) {
      final w = fit.toWidget(p);
      path.lineTo(w.dx, w.dy);
    }
    if (closed) path.close();

    if (closed) {
      canvas.drawPath(path, Paint()..color = Colors.amber.withValues(alpha: 0.22));
    }
    canvas.drawPath(
      path,
      Paint()
        ..color = Colors.amber
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2,
    );
    for (var i = 0; i < points.length; i++) {
      final w = fit.toWidget(points[i]);
      canvas.drawCircle(w, i == 0 ? 7 : 4, Paint()..color = Colors.white);
    }
  }

  @override
  bool shouldRepaint(covariant _LassoPainter old) => true;
}
