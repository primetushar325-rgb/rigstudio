import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../state/app_state.dart';
import '../widgets/common.dart';

/// Monetization UI. The gate is a local flag today — swap [SettingsController.
/// setPremium] for your IAP/subscription callback when you wire up billing.
class PaywallScreen extends ConsumerWidget {
  const PaywallScreen({super.key, this.reason});

  final String? reason;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final premium = ref.watch(settingsProvider).premium;

    Widget row(IconData icon, String free, String pro) => Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(icon, size: 18, color: Colors.white54),
              const SizedBox(width: 12),
              Expanded(child: Text(free, style: const TextStyle(color: Colors.white60))),
              const SizedBox(width: 12),
              Expanded(
                child: Text(pro,
                    style: const TextStyle(fontWeight: FontWeight.w700)),
              ),
            ],
          ),
        );

    return Scaffold(
      appBar: AppBar(title: const Text('RigStudio Premium')),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          if (reason != null)
            Container(
              padding: const EdgeInsets.all(14),
              margin: const EdgeInsets.only(bottom: 18),
              decoration: BoxDecoration(
                color: const Color(0xFFFFA94D).withValues(alpha: 0.14),
                borderRadius: BorderRadius.circular(14),
              ),
              child: Text(reason!, style: const TextStyle(color: Color(0xFFFFC46B))),
            ),
          const Row(
            children: [
              Expanded(child: SizedBox()),
              Expanded(
                  child: Text('FREE',
                      style: TextStyle(fontSize: 12, color: Colors.white38))),
              Expanded(child: PremiumChip(label: 'PREMIUM')),
            ],
          ),
          const Divider(height: 24),
          row(Icons.accessibility_new, 'Standard template rigging', 'Everything in Free'),
          row(Icons.gesture, 'No lasso tool', 'Precision lasso cutting & per-part refine'),
          row(Icons.animation, '8 core clips', 'Extra clip packs (jump, dance, combat…)'),
          row(Icons.hd, '512 px, watermarked', 'Up to 1440 px, no watermark, mp4'),
          row(Icons.timer, '3 s exports', 'Up to 30 s exports'),
          row(Icons.folder_copy_outlined,
              '${Limits.freeCharacterSlots} saved characters', 'Unlimited characters'),
          const SizedBox(height: 28),
          FilledButton(
            onPressed: () {
              ref.read(settingsProvider.notifier).setPremium(!premium);
              Navigator.pop(context);
            },
            child: Text(premium
                ? 'Switch back to Free (debug)'
                : 'Unlock Premium — \$4.99 / month'),
          ),
          const SizedBox(height: 10),
          const Center(
            child: Text(
              'Billing is not wired up in this build: the button flips a local '
              'flag so you can test both tiers.',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 11, color: Colors.white38),
            ),
          ),
        ],
      ),
    );
  }
}
