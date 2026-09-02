import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models/bone_part.dart';
import '../state/app_state.dart';
import '../widgets/common.dart';
import 'animate_screen.dart';
import 'lasso_screen.dart';
import 'paywall_screen.dart';

/// Step 6 — layer order, mirroring, visibility and pivot tuning.
class LayersScreen extends ConsumerWidget {
  const LayersScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final editor = ref.watch(editorProvider);
    final notifier = ref.read(editorProvider.notifier);
    final skeleton = editor.skeleton;
    final premium = ref.watch(settingsProvider).premium;

    if (skeleton == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('Layers')),
        body: const Center(child: Text('Rig the character first.')),
      );
    }

    // top of the stack first
    final ordered = skeleton.drawOrder.reversed.toList();
    final missing = skeleton.missingParts;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Layers & pivots'),
        actions: [
          IconButton(
            tooltip: 'Mirror whole rig',
            icon: const Icon(Icons.flip),
            onPressed: notifier.mirrorWholeRig,
          ),
        ],
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: FilledButton.icon(
            onPressed: () => Navigator.push(
                context, MaterialPageRoute(builder: (_) => const AnimateScreen())),
            icon: const Icon(Icons.play_arrow),
            label: Text(missing.isEmpty
                ? 'Animate'
                : 'Animate anyway (${missing.length} parts missing)'),
          ),
        ),
      ),
      body: BusyOverlay(
        busy: editor.busy,
        status: editor.status,
        child: Column(
          children: [
            if (missing.isNotEmpty)
              Container(
                width: double.infinity,
                color: Colors.orange.withValues(alpha: 0.15),
                padding: const EdgeInsets.all(12),
                child: Text(
                  'Still uncut: ${missing.map((b) => b.label).join(', ')}',
                  style: const TextStyle(color: Colors.orangeAccent, fontSize: 12),
                ),
              ),
            Expanded(
              child: ReorderableListView.builder(
                padding: const EdgeInsets.only(bottom: 12),
                itemCount: ordered.length,
                onReorderItem: notifier.reorderLayers,
                itemBuilder: (context, index) {
                  final bone = ordered[index];
                  return _LayerTile(
                    key: ValueKey(bone.id),
                    bone: bone,
                    image: editor.partImages[bone.id],
                    index: index,
                    premium: premium,
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _LayerTile extends ConsumerWidget {
  const _LayerTile({
    super.key,
    required this.bone,
    required this.image,
    required this.index,
    required this.premium,
  });

  final BonePart bone;
  final ui.Image? image;
  final int index;
  final bool premium;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final notifier = ref.read(editorProvider.notifier);
    return Card(
      margin: const EdgeInsets.fromLTRB(14, 6, 14, 0),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
        child: Row(
          children: [
            ReorderableDragStartListener(
              index: index,
              child: const Padding(
                padding: EdgeInsets.symmetric(horizontal: 6),
                child: Icon(Icons.drag_handle, color: Colors.white38),
              ),
            ),
            Container(
              width: 46,
              height: 46,
              decoration: BoxDecoration(
                color: const Color(0xFF0B0D12),
                borderRadius: BorderRadius.circular(10),
              ),
              clipBehavior: Clip.antiAlias,
              child: bone.imagePath != null && File(bone.imagePath!).existsSync()
                  ? Image.file(File(bone.imagePath!), fit: BoxFit.contain)
                  : const Icon(Icons.help_outline, size: 18, color: Colors.white24),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(bone.label, style: const TextStyle(fontWeight: FontWeight.w700)),
                  Text('${bone.id} · z${bone.zIndex}',
                      style: const TextStyle(fontSize: 11, color: Colors.white38)),
                ],
              ),
            ),
            IconButton(
              tooltip: 'Mirror part',
              icon: Icon(Icons.flip,
                  size: 20, color: bone.mirrored ? Colors.amber : Colors.white54),
              onPressed: () => notifier.togglePartMirror(bone.id),
            ),
            IconButton(
              tooltip: 'Visibility',
              icon: Icon(bone.visible ? Icons.visibility : Icons.visibility_off,
                  size: 20, color: bone.visible ? Colors.white54 : Colors.redAccent),
              onPressed: () => notifier.togglePartVisible(bone.id),
            ),
            PopupMenuButton<String>(
              icon: const Icon(Icons.more_vert, size: 20),
              onSelected: (value) async {
                switch (value) {
                  case 'pivot':
                    await _adjustPivot(context, ref);
                    break;
                  case 'refine':
                    if (Limits.manualCutLocked(premium)) {
                      Navigator.push(
                        context,
                        MaterialPageRoute(
                          builder: (_) => const PaywallScreen(
                              reason: 'Refining a part with the lasso is a Premium tool.'),
                        ),
                      );
                      return;
                    }
                    Navigator.push(
                      context,
                      MaterialPageRoute(
                          builder: (_) => LassoScreen(initialBoneId: bone.id)),
                    );
                    break;
                  case 'clear':
                    await ref.read(editorProvider.notifier).clearPart(bone.id);
                    break;
                }
              },
              itemBuilder: (context) => const [
                PopupMenuItem(value: 'pivot', child: Text('Adjust pivot')),
                PopupMenuItem(value: 'refine', child: Text('Refine this part (lasso)')),
                PopupMenuItem(value: 'clear', child: Text('Clear cut')),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _adjustPivot(BuildContext context, WidgetRef ref) async {
    final editor = ref.read(editorProvider);
    final img = editor.partImages[bone.id];
    if (img == null || bone.imageRect == Rect.zero) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Cut this part first.')),
      );
      return;
    }
    var pivot = bone.pivot;
    await showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Pivot · ${bone.label}'),
        content: SizedBox(
          width: 320,
          height: 320,
          child: StatefulBuilder(
            builder: (ctx, setLocal) => LayoutBuilder(
              builder: (ctx, constraints) {
                final fit = CanvasFit.contain(
                  bone.imageRect.size,
                  Size(constraints.maxWidth, constraints.maxHeight),
                  padding: 8,
                );
                Offset toLocalCanvas(Offset widgetPoint) =>
                    fit.toCanvas(widgetPoint) + bone.imageRect.topLeft;
                return GestureDetector(
                  onPanDown: (d) => setLocal(() => pivot = toLocalCanvas(d.localPosition)),
                  onPanUpdate: (d) =>
                      setLocal(() => pivot = toLocalCanvas(d.localPosition)),
                  child: CustomPaint(
                    painter: _PivotPainter(
                      image: img,
                      rect: bone.imageRect,
                      pivot: pivot,
                      fit: fit,
                    ),
                    size: Size(constraints.maxWidth, constraints.maxHeight),
                  ),
                );
              },
            ),
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          FilledButton(
            onPressed: () {
              ref.read(editorProvider.notifier).setPivot(bone.id, pivot);
              Navigator.pop(ctx);
            },
            child: const Text('Set pivot'),
          ),
        ],
      ),
    );
  }
}

class _PivotPainter extends CustomPainter {
  _PivotPainter({
    required this.image,
    required this.rect,
    required this.pivot,
    required this.fit,
  });

  final ui.Image image;
  final Rect rect;
  final Offset pivot;
  final CanvasFit fit;

  @override
  void paint(Canvas canvas, Size size) {
    final dst = fit.toWidgetRect(Offset.zero & rect.size);
    canvas.drawRect(dst, Paint()..color = const Color(0xFF0B0D12));
    canvas.drawImageRect(
      image,
      Rect.fromLTWH(0, 0, image.width.toDouble(), image.height.toDouble()),
      dst,
      Paint()..filterQuality = FilterQuality.medium,
    );
    final p = fit.toWidget(pivot - rect.topLeft);
    canvas.drawCircle(p, 9, Paint()..color = Colors.amber.withValues(alpha: 0.35));
    canvas.drawCircle(
        p,
        9,
        Paint()
          ..color = Colors.amber
          ..style = PaintingStyle.stroke
          ..strokeWidth = 2);
    canvas.drawLine(Offset(p.dx - 16, p.dy), Offset(p.dx + 16, p.dy),
        Paint()..color = Colors.amber);
    canvas.drawLine(Offset(p.dx, p.dy - 16), Offset(p.dx, p.dy + 16),
        Paint()..color = Colors.amber);
  }

  @override
  bool shouldRepaint(covariant _PivotPainter old) => old.pivot != pivot;
}
