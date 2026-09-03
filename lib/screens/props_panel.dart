import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/standard_rig.dart';
import '../models/prop.dart';
import '../services/prop_library.dart';
import '../state/app_state.dart';
import '../widgets/rig_preview.dart';

/// Adds and tunes wearable/held props. Each prop is a transparent PNG attached
/// to a bone; a live preview shows it inheriting the bone's transform.
class PropsPanel extends ConsumerWidget {
  const PropsPanel({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final editor = ref.watch(editorProvider);
    final notifier = ref.read(editorProvider.notifier);
    final skeleton = editor.skeleton;

    if (skeleton == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('Props')),
        body: const Center(child: Text('Rig the character first.')),
      );
    }
    final props = skeleton.props;

    return Scaffold(
      appBar: AppBar(title: const Text('Props & accessories')),
      body: Column(
        children: [
          // live preview
          Expanded(
            child: Container(
              margin: const EdgeInsets.fromLTRB(16, 8, 16, 8),
              clipBehavior: Clip.antiAlias,
              decoration: BoxDecoration(
                color: const Color(0xFF101216),
                borderRadius: BorderRadius.circular(18),
                border: Border.all(color: Colors.white10),
              ),
              child: RigPreview(
                skeleton: skeleton,
                images: editor.partImages,
                background: const Color(0xFF101216),
                propImages: editor.propImages,
              ),
            ),
          ),
          // add from built-in library
          SizedBox(
            height: 56,
            child: ListView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 12),
              children: [
                for (final t in kBuiltInPropTemplates)
                  Padding(
                    padding: const EdgeInsets.only(right: 10),
                    child: ActionChip(
                      avatar: Icon(t.icon, size: 18),
                      label: Text(t.label),
                      onPressed: () => notifier.addProp(t.id),
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(height: 4),
          // prop list
          Expanded(
            child: props.isEmpty
                ? const Center(
                    child: Text('No props yet. Pick one above to attach.',
                        style: TextStyle(color: Colors.white38)))
                : ListView.builder(
                    padding: const EdgeInsets.only(bottom: 16),
                    itemCount: props.length,
                    itemBuilder: (context, i) => _PropRow(
                      prop: props[i],
                      image: editor.propImages[props[i].id],
                      onChanged: (p) => notifier.updateProp(props[i].id, p),
                    ),
                  ),
          ),
        ],
      ),
    );
  }
}

class _PropRow extends ConsumerWidget {
  const _PropRow({
    required this.prop,
    required this.image,
    required this.onChanged,
  });

  final PropAttachment prop;
  final ui.Image? image;
  final ValueChanged<PropAttachment> onChanged;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final notifier = ref.read(editorProvider.notifier);
    final rigH = ref.read(editorProvider).canvasSize.height;

    Widget slider(String label, double value, double min, double max,
        ValueChanged<double> cb, String unit) {
      return Row(
        children: [
          SizedBox(width: 88, child: Text(label, style: const TextStyle(fontSize: 12))),
          Expanded(
            child: Slider(
              value: value.clamp(min, max),
              min: min,
              max: max,
              onChanged: cb,
            ),
          ),
          SizedBox(
            width: 46,
            child: Text(unit,
                textAlign: TextAlign.end, style: const TextStyle(fontSize: 10)),
          ),
        ],
      );
    }

    return Card(
      margin: const EdgeInsets.fromLTRB(14, 6, 14, 0),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  width: 44,
                  height: 44,
                  decoration: BoxDecoration(
                      color: const Color(0xFF0B0D12),
                      borderRadius: BorderRadius.circular(10)),
                  clipBehavior: Clip.antiAlias,
                  child: image != null
                      ? RawImage(image: image!, fit: BoxFit.contain, width: 44, height: 44)
                      : const Icon(Icons.image_outlined, color: Colors.white24),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(prop.label.isNotEmpty ? prop.label : prop.id,
                          style: const TextStyle(fontWeight: FontWeight.w700)),
                      Text('attached to ${prop.attachedBoneId}',
                          style: const TextStyle(fontSize: 11, color: Colors.white54)),
                    ],
                  ),
                ),
                IconButton(
                  tooltip: 'Mirror',
                  icon: Icon(Icons.flip, color: prop.mirrored ? Colors.amber : Colors.white54),
                  onPressed: () => onChanged(prop.copyWith(mirrored: !prop.mirrored)),
                ),
                IconButton(
                  tooltip: 'Visibility',
                  icon: Icon(prop.visible ? Icons.visibility : Icons.visibility_off,
                      color: prop.visible ? Colors.white54 : Colors.redAccent),
                  onPressed: () => onChanged(prop.copyWith(visible: !prop.visible)),
                ),
                IconButton(
                  tooltip: 'Remove',
                  icon: const Icon(Icons.delete_outline, color: Colors.redAccent),
                  onPressed: () => notifier.removeProp(prop.id),
                ),
              ],
            ),
            // re-parent to another bone
            Row(
              children: [
                const Text('Bone  ', style: TextStyle(fontSize: 13)),
                Expanded(
                  child: DropdownButton<String>(
                    isExpanded: true,
                    value: prop.attachedBoneId,
                    items: [
                      for (final tb in kStandardRig)
                        DropdownMenuItem(
                            value: tb.id, child: Text('${tb.label} (${tb.id})')),
                    ],
                    onChanged: (v) =>
                        v == null ? null : onChanged(prop.copyWith(attachedBoneId: v)),
                  ),
                ),
              ],
            ),
            slider('Scale', prop.scale, 0.1, 3.0,
                (v) => onChanged(prop.copyWith(scale: v)), '${prop.scale.toStringAsFixed(2)}×'),
            slider('Rotation', prop.localRotation, -3.14, 3.14,
                (v) => onChanged(prop.copyWith(localRotation: v)),
                '${(prop.localRotation * 57.2958).round()}°'),
            slider('X offset', prop.localOffset.dx, -rigH * 0.45, rigH * 0.45,
                (v) => onChanged(
                    prop.copyWith(localOffset: Offset(v, prop.localOffset.dy))),
                '${prop.localOffset.dx.round()}px'),
            slider('Y offset', prop.localOffset.dy, -rigH * 0.6, rigH * 0.4,
                (v) => onChanged(
                    prop.copyWith(localOffset: Offset(prop.localOffset.dx, v))),
                '${prop.localOffset.dy.round()}px'),
          ],
        ),
      ),
    );
  }
}
