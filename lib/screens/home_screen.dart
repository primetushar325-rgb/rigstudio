import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models/character.dart';
import '../state/app_state.dart';
import '../widgets/common.dart';
import 'animate_screen.dart';
import 'import_screen.dart';
import 'paywall_screen.dart';
import 'rig_entry_screen.dart';

class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final library = ref.watch(libraryProvider);
    final settings = ref.watch(settingsProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('RigStudio', style: TextStyle(fontWeight: FontWeight.w800)),
        actions: [
          if (settings.premium)
            const Padding(padding: EdgeInsets.only(right: 12), child: Center(child: PremiumChip())),
          IconButton(
            tooltip: 'Upgrade',
            icon: const Icon(Icons.workspace_premium_outlined),
            onPressed: () => Navigator.push(
                context, MaterialPageRoute(builder: (_) => const PaywallScreen())),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _newCharacter(context, ref),
        icon: const Icon(Icons.add),
        label: const Text('New character'),
      ),
      body: library.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('Could not load library:\n$e')),
        data: (characters) {
          if (characters.isEmpty) return _Empty(onNew: () => _newCharacter(context, ref));
          return GridView.builder(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 96),
            gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
              maxCrossAxisExtent: 220,
              childAspectRatio: 0.72,
              crossAxisSpacing: 14,
              mainAxisSpacing: 14,
            ),
            itemCount: characters.length,
            itemBuilder: (context, i) => _CharacterTile(character: characters[i]),
          );
        },
      ),
    );
  }

  Future<void> _newCharacter(BuildContext context, WidgetRef ref) async {
    final settings = ref.read(settingsProvider);
    final count = ref.read(libraryProvider).value?.length ?? 0;
    if (!settings.premium && count >= Limits.freeCharacterSlots) {
      await Navigator.push(
        context,
        MaterialPageRoute(
          builder: (_) => const PaywallScreen(
            reason: 'The free tier keeps ${Limits.freeCharacterSlots} characters. '
                'Upgrade for unlimited slots.',
          ),
        ),
      );
      return;
    }
    if (!context.mounted) return;
    Navigator.push(context, MaterialPageRoute(builder: (_) => const ImportScreen()));
  }
}

class _Empty extends StatelessWidget {
  const _Empty({required this.onNew});
  final VoidCallback onNew;

  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.accessibility_new, size: 72, color: Colors.white24),
              const SizedBox(height: 16),
              const Text('No characters yet',
                  style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
              const SizedBox(height: 8),
              const Text(
                'Import a character image, drop the standard skeleton on top of it, '
                'and every animation just works.',
                textAlign: TextAlign.center,
                style: TextStyle(color: Colors.white60),
              ),
              const SizedBox(height: 24),
              FilledButton.icon(
                onPressed: onNew,
                icon: const Icon(Icons.add_photo_alternate_outlined),
                label: const Text('Import a character'),
              ),
            ],
          ),
        ),
      );
}

class _CharacterTile extends ConsumerWidget {
  const _CharacterTile({required this.character});
  final Character character;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final thumbPath = character.thumbnailPath ?? character.cutSource;
    final file = File(thumbPath);

    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () async {
          await ref.read(editorProvider.notifier).open(character);
          if (!context.mounted) return;
          Navigator.push(
            context,
            MaterialPageRoute(
              builder: (_) =>
                  character.isRigged ? const AnimateScreen() : const RigEntryScreen(),
            ),
          );
        },
        onLongPress: () => _menu(context, ref),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(
              child: Stack(
                fit: StackFit.expand,
                children: [
                  Container(color: const Color(0xFF0A0C10)),
                  if (file.existsSync())
                    Image.file(file, fit: BoxFit.contain)
                  else
                    const Icon(Icons.broken_image_outlined, color: Colors.white24),
                  Positioned(
                    left: 8,
                    top: 8,
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                      decoration: BoxDecoration(
                        color: character.isRigged
                            ? Colors.green.withValues(alpha: 0.85)
                            : Colors.orange.withValues(alpha: 0.85),
                        borderRadius: BorderRadius.circular(20),
                      ),
                      child: Text(
                        character.isRigged ? 'Rigged' : 'In progress',
                        style: const TextStyle(
                            fontSize: 10, fontWeight: FontWeight.w800, color: Colors.black),
                      ),
                    ),
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(character.name,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontWeight: FontWeight.w700)),
                  const SizedBox(height: 2),
                  Text(
                    character.skeleton == null
                        ? 'Not rigged'
                        : '${character.skeleton!.bones.where((b) => b.isCut).length}'
                            '/${character.skeleton!.bones.length} parts',
                    style: const TextStyle(fontSize: 11, color: Colors.white54),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  void _menu(BuildContext context, WidgetRef ref) {
    showModalBottomSheet(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.edit_outlined),
              title: const Text('Rename'),
              onTap: () async {
                Navigator.pop(ctx);
                final controller = TextEditingController(text: character.name);
                final name = await showDialog<String>(
                  context: context,
                  builder: (c) => AlertDialog(
                    title: const Text('Rename character'),
                    content: TextField(controller: controller, autofocus: true),
                    actions: [
                      TextButton(
                          onPressed: () => Navigator.pop(c), child: const Text('Cancel')),
                      FilledButton(
                          onPressed: () => Navigator.pop(c, controller.text),
                          child: const Text('Save')),
                    ],
                  ),
                );
                if (name != null && name.trim().isNotEmpty) {
                  character.name = name.trim();
                  await ref.read(libraryProvider.notifier).save(character);
                }
              },
            ),
            ListTile(
              leading: const Icon(Icons.tune),
              title: const Text('Re-rig'),
              onTap: () async {
                Navigator.pop(ctx);
                await ref.read(editorProvider.notifier).open(character);
                if (!context.mounted) return;
                Navigator.push(context,
                    MaterialPageRoute(builder: (_) => const RigEntryScreen()));
              },
            ),
            ListTile(
              leading: const Icon(Icons.delete_outline, color: Colors.redAccent),
              title: const Text('Delete', style: TextStyle(color: Colors.redAccent)),
              onTap: () async {
                Navigator.pop(ctx);
                await ref.read(libraryProvider.notifier).delete(character.id);
              },
            ),
          ],
        ),
      ),
    );
  }
}
