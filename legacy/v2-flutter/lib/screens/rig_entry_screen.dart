import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../state/app_state.dart';
import '../widgets/common.dart';
import 'lasso_screen.dart';
import 'paywall_screen.dart';
import 'template_align_screen.dart';

/// Pick a rigging path. Both produce the exact same [Skeleton] shape, so the
/// downstream screens (layers, animate, export) never care which was used.
class RigEntryScreen extends ConsumerWidget {
  const RigEntryScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final premium = ref.watch(settingsProvider).premium;

    return Scaffold(
      appBar: AppBar(title: const Text('Rig setup')),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          _PathCard(
            title: 'Standard skeleton template',
            badge: 'RECOMMENDED',
            badgeColor: const Color(0xFF3DD68C),
            icon: Icons.accessibility_new,
            body: 'Drop a ready-made skeleton on your character, drag/pinch/rotate '
                'it until the limbs line up, and RigStudio cuts every body part '
                'automatically. Fastest path — no manual cutting.',
            onTap: () => Navigator.push(
              context,
              MaterialPageRoute(builder: (_) => const TemplateAlignScreen()),
            ),
          ),
          const SizedBox(height: 16),
          _PathCard(
            title: 'Manual cut (lasso)',
            badge: premium ? 'PRO' : 'PRO — LOCKED',
            badgeColor: const Color(0xFFFFA94D),
            icon: Icons.gesture,
            body: 'Trace each body part with a polygon lasso and assign it to a '
                'bone yourself. Best for unusual shapes, props, or fixing a '
                'single limb the auto-cut got wrong.',
            onTap: () {
              if (Limits.manualCutLocked(premium)) {
                Navigator.push(
                  context,
                  MaterialPageRoute(
                    builder: (_) => const PaywallScreen(
                        reason: 'Precision lasso cutting is a Premium tool.'),
                  ),
                );
                return;
              }
              ref.read(editorProvider.notifier).ensureSkeleton();
              Navigator.push(
                context,
                MaterialPageRoute(builder: (_) => const LassoScreen()),
              );
            },
          ),
          const SizedBox(height: 28),
          const Text(
            'Bone ids are identical in both paths (torso, head, upper_arm_l, '
            'forearm_l, thigh_r, …) which is why every animation clip plays on '
            'every rigged character with no extra mapping.',
            style: TextStyle(color: Colors.white38, fontSize: 12),
          ),
        ],
      ),
    );
  }
}

class _PathCard extends StatelessWidget {
  const _PathCard({
    required this.title,
    required this.badge,
    required this.badgeColor,
    required this.icon,
    required this.body,
    required this.onTap,
  });

  final String title;
  final String badge;
  final Color badgeColor;
  final IconData icon;
  final String body;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 54,
                height: 54,
                decoration: BoxDecoration(
                  color: badgeColor.withValues(alpha: 0.16),
                  borderRadius: BorderRadius.circular(16),
                ),
                child: Icon(icon, color: badgeColor),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(title,
                              style: const TextStyle(
                                  fontSize: 17, fontWeight: FontWeight.w800)),
                        ),
                        PremiumChip(label: badge),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Text(body,
                        style: const TextStyle(color: Colors.white60, height: 1.35)),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
