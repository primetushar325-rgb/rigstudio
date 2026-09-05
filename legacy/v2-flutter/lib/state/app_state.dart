import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/standard_rig.dart';
import '../models/bone_part.dart';
import '../models/character.dart';
import '../models/prop.dart';
import '../models/skeleton.dart';
import '../services/chroma_key_service.dart';
import '../services/cut_service.dart';
import '../services/prop_library.dart';
import '../services/storage_service.dart';
import 'history.dart';

// ---------------------------------------------------------------------------
// Settings + monetization flag
// ---------------------------------------------------------------------------

class AppSettings {
  const AppSettings({
    this.premium = false,
    this.previewBackground = const Color(0xFF101216),
    this.transparentPreview = false,
  });

  final bool premium;
  final Color previewBackground;
  final bool transparentPreview;

  AppSettings copyWith({bool? premium, Color? previewBackground, bool? transparentPreview}) =>
      AppSettings(
        premium: premium ?? this.premium,
        previewBackground: previewBackground ?? this.previewBackground,
        transparentPreview: transparentPreview ?? this.transparentPreview,
      );
}

class SettingsController extends Notifier<AppSettings> {
  @override
  AppSettings build() {
    _load();
    return const AppSettings();
  }

  Future<void> _load() async {
    final m = await StorageService.instance.loadSettings();
    state = AppSettings(
      premium: m['premium'] as bool? ?? false,
      previewBackground: Color(m['previewBackground'] as int? ?? 0xFF101216),
      transparentPreview: m['transparentPreview'] as bool? ?? false,
    );
  }

  Future<void> _persist() async {
    await StorageService.instance.saveSettings({
      'premium': state.premium,
      'previewBackground': state.previewBackground.toARGB32(),
      'transparentPreview': state.transparentPreview,
    });
  }

  void setPremium(bool value) {
    state = state.copyWith(premium: value);
    _persist();
  }

  void setPreviewBackground(Color c, {bool transparent = false}) {
    state = state.copyWith(previewBackground: c, transparentPreview: transparent);
    _persist();
  }
}

final settingsProvider =
    NotifierProvider<SettingsController, AppSettings>(SettingsController.new);

/// Free-tier limits, all enforced in the UI (swap for real IAP later).
class Limits {
  static const int freeCharacterSlots = 2;
  static const double freeMaxExportSeconds = 3.0;
  static const int freeMaxExportWidth = 512;
  static bool clipLocked(bool premium, bool clipIsPremium) => clipIsPremium && !premium;
  static bool manualCutLocked(bool premium) => !premium;
}

// ---------------------------------------------------------------------------
// Character library
// ---------------------------------------------------------------------------

class LibraryController extends AsyncNotifier<List<Character>> {
  @override
  Future<List<Character>> build() => StorageService.instance.loadLibrary();

  Future<void> refresh() async {
    state = const AsyncValue.loading();
    state = AsyncValue.data(await StorageService.instance.loadLibrary());
  }

  Future<Character> createFromBytes(Uint8List bytes, {String? name}) async {
    final id = DateTime.now().microsecondsSinceEpoch.toRadixString(36);
    final path = await StorageService.instance.writeSourceImage(id, bytes);
    final thumb = await StorageService.instance.writeThumbnail(id, bytes);
    final c = Character(
      id: id,
      name: name ?? 'Character ${DateTime.now().day}/${DateTime.now().month}',
      sourceImagePath: path,
      thumbnailPath: thumb,
      createdAt: DateTime.now(),
      updatedAt: DateTime.now(),
    );
    await StorageService.instance.saveCharacter(c);
    await refresh();
    return c;
  }

  Future<void> save(Character c) async {
    await StorageService.instance.saveCharacter(c);
    await refresh();
  }

  Future<void> delete(String id) async {
    await StorageService.instance.deleteCharacter(id);
    await refresh();
  }
}

final libraryProvider =
    AsyncNotifierProvider<LibraryController, List<Character>>(LibraryController.new);

// ---------------------------------------------------------------------------
// Editor (the character currently being rigged)
// ---------------------------------------------------------------------------

class EditorState {
  const EditorState({
    this.character,
    this.workingBytes,
    this.workingImage,
    this.template,
    this.partImages = const {},
    this.propImages = const {},
    this.selectedBoneId,
    this.busy = false,
    this.status,
    this.chroma = const ChromaKeyParams(),
  });

  final Character? character;

  /// Bytes of the image parts are cut from (chroma-keyed if that ran).
  final Uint8List? workingBytes;
  final ui.Image? workingImage;

  /// Live placement of the standard skeleton over the image (Path A).
  final RigTemplateTransform? template;

  /// Decoded bitmaps per bone id, ready for the painter.
  final Map<String, ui.Image> partImages;

  /// Decoded bitmaps per prop id.
  final Map<String, ui.Image> propImages;

  final String? selectedBoneId;
  final bool busy;
  final String? status;
  final ChromaKeyParams chroma;

  Skeleton? get skeleton => character?.skeleton;

  Size get canvasSize => workingImage == null
      ? const Size(800, 1200)
      : Size(workingImage!.width.toDouble(), workingImage!.height.toDouble());

  EditorState copyWith({
    Character? character,
    Uint8List? workingBytes,
    ui.Image? workingImage,
    RigTemplateTransform? template,
    Map<String, ui.Image>? partImages,
    Map<String, ui.Image>? propImages,
    String? selectedBoneId,
    bool clearSelection = false,
    bool? busy,
    String? status,
    ChromaKeyParams? chroma,
  }) =>
      EditorState(
        character: character ?? this.character,
        workingBytes: workingBytes ?? this.workingBytes,
        workingImage: workingImage ?? this.workingImage,
        template: template ?? this.template,
        partImages: partImages ?? this.partImages,
        propImages: propImages ?? this.propImages,
        selectedBoneId: clearSelection ? null : (selectedBoneId ?? this.selectedBoneId),
        busy: busy ?? this.busy,
        status: status,
        chroma: chroma ?? this.chroma,
      );
}

class EditorController extends Notifier<EditorState> {
  @override
  EditorState build() => const EditorState();

  StorageService get _storage => StorageService.instance;

  /// Undo/redo over the rig. Each entry is a deep-copied skeleton plus a shallow
  /// copy of the decoded part images, so joint/crop/layer/mirror changes are all
  /// reversible.
  final History<(Skeleton, Map<String, ui.Image>, Map<String, ui.Image>)> _history =
      History();

  bool get canUndo => _history.canUndo;
  bool get canRedo => _history.canRedo;

  (Skeleton, Map<String, ui.Image>, Map<String, ui.Image>) _snapNow() {
    final skeleton = Skeleton.fromJson(state.character!.skeleton!.toJson());
    return (
      skeleton,
      Map<String, ui.Image>.from(state.partImages),
      Map<String, ui.Image>.from(state.propImages),
    );
  }

  /// Records the current state so the next mutation can be undone. Call at the
  /// top of every mutating editor action, or once at the start of a continuous
  /// gesture (drag) whose many updates should collapse into one undo step.
  void _recordHistory() {
    if (state.character?.skeleton == null) return;
    _history.record(_snapNow);
  }

  /// Public history marker for starting a discrete gesture (e.g. a pivot drag).
  void recordHistory() => _recordHistory();

  Future<void> undo() async {
    final snap = _history.undo(_snapNow);
    if (snap != null) await _restoreSnapshot(snap);
  }

  Future<void> redo() async {
    final snap = _history.redo(_snapNow);
    if (snap != null) await _restoreSnapshot(snap);
  }

  Future<void> _restoreSnapshot(
      (Skeleton, Map<String, ui.Image>, Map<String, ui.Image>) snap) async {
    final c = state.character;
    if (c == null) return;
    c.skeleton = Skeleton.fromJson(snap.$1.toJson());
    await _storage.saveCharacter(c);
    state = state.copyWith(
      character: c,
      partImages: Map<String, ui.Image>.from(snap.$2),
      propImages: Map<String, ui.Image>.from(snap.$3),
    );
    ref.read(libraryProvider.notifier).refresh();
  }

  // ------------------------------------------------------------------ opening

  Future<void> open(Character c) async {
    state = EditorState(character: c, busy: true, status: 'Loading…');
    final bytes = await File(c.cutSource).readAsBytes();
    final image = await StorageService.decodeUiImage(bytes);
    final parts = <String, ui.Image>{};
    for (final b in c.skeleton?.bones ?? const <BonePart>[]) {
      if (b.imagePath == null) continue;
      final im = await StorageService.loadUiImageFile(b.imagePath!);
      if (im != null) parts[b.id] = im;
    }
    final props = <String, ui.Image>{};
    for (final p in c.skeleton?.props ?? const <PropAttachment>[]) {
      final im = await StorageService.loadUiImageFile(p.imagePath);
      if (im != null) props[p.id] = im;
    }
    state = state.copyWith(
      workingBytes: bytes,
      workingImage: image,
      partImages: parts,
      propImages: props,
      template: c.skeleton == null
          ? RigTemplateTransform.fitTo(
              Size(image.width.toDouble(), image.height.toDouble()))
          : null,
      busy: false,
    );
  }

  void close() => state = const EditorState();

  // -------------------------------------------------------------- chroma key

  void setChroma(ChromaKeyParams p) => state = state.copyWith(chroma: p);

  /// Fast, downscaled preview for slider dragging.
  Future<Uint8List?> previewChroma({int maxDimension = 480}) async {
    final src = state.character;
    if (src == null) return null;
    final original = await File(src.sourceImagePath).readAsBytes();
    return ChromaKeyService.run(ChromaKeyRequest(
      original,
      state.chroma,
      maxDimension: maxDimension,
    ));
  }

  /// Full-resolution bake, saved as `working.png`.
  Future<void> applyChroma() async {
    final c = state.character;
    if (c == null) return;
    state = state.copyWith(busy: true, status: 'Removing background…');
    final original = await File(c.sourceImagePath).readAsBytes();
    final keyed = await ChromaKeyService.run(ChromaKeyRequest(original, state.chroma));
    final path = await _storage.writeWorkingImage(c.id, keyed);
    c.workingImagePath = path;
    await _storage.writeThumbnail(c.id, keyed);
    await _storage.saveCharacter(c);
    final image = await StorageService.decodeUiImage(keyed);
    state = state.copyWith(
      character: c,
      workingBytes: keyed,
      workingImage: image,
      busy: false,
      status: null,
      template: RigTemplateTransform.fitTo(
          Size(image.width.toDouble(), image.height.toDouble())),
    );
    ref.read(libraryProvider.notifier).refresh();
  }

  Future<void> skipChroma() async {
    final c = state.character;
    if (c == null) return;
    c.workingImagePath = null;
    await _storage.saveCharacter(c);
  }

  // ---------------------------------------------------------- template (Path A)

  void updateTemplate(RigTemplateTransform t) => state = state.copyWith(template: t);

  void selectBone(String? id) => id == null
      ? state = state.copyWith(clearSelection: true)
      : state = state.copyWith(selectedBoneId: id);

  /// Path A: build the skeleton from the placement, then auto-crop every bone.
  Future<void> commitTemplateAndAutoCrop() async {
    final c = state.character;
    final t = state.template;
    final bytes = state.workingBytes;
    if (c == null || t == null || bytes == null) return;
    _recordHistory();

    state = state.copyWith(busy: true, status: 'Cutting parts…');
    final skeleton = buildSkeletonFromTemplate(
      characterId: c.id,
      canvasSize: state.canvasSize,
      transform: t,
    );
    final cuts = await CutService.autoCropFromTemplate(imageBytes: bytes, transform: t);

    final images = <String, ui.Image>{};
    for (final cut in cuts) {
      final path = await _storage.writePart(c.id, cut.boneId, cut.pngBytes);
      final bone = skeleton.byId(cut.boneId);
      if (bone == null) continue;
      bone.imagePath = path;
      bone.imageRect = cut.rect;
      images[cut.boneId] = await StorageService.decodeUiImage(cut.pngBytes);
    }

    c.skeleton = skeleton;
    await _storage.saveCharacter(c);
    state = state.copyWith(
      character: c,
      partImages: images,
      busy: false,
      status: null,
    );
    ref.read(libraryProvider.notifier).refresh();
  }

  /// Ensures a skeleton exists even before any cutting (manual-only flow).
  void ensureSkeleton() {
    final c = state.character;
    if (c == null || c.skeleton != null) return;
    final t = state.template ?? RigTemplateTransform.fitTo(state.canvasSize);
    c.skeleton = buildSkeletonFromTemplate(
      characterId: c.id,
      canvasSize: state.canvasSize,
      transform: t,
    );
    state = state.copyWith(character: c, template: t);
  }

  // -------------------------------------------------------------- lasso (Path B)

  Future<void> applyLasso(String boneId, List<Offset> polygon) async {
    final c = state.character;
    final bytes = state.workingBytes;
    if (c == null || bytes == null) return;
    ensureSkeleton();
    _recordHistory();
    state = state.copyWith(busy: true, status: 'Cutting ${boneId.replaceAll('_', ' ')}…');

    final cut = await CutService.lassoCrop(
      imageBytes: bytes,
      boneId: boneId,
      polygon: polygon,
    );
    if (cut == null) {
      state = state.copyWith(busy: false, status: 'Selection was empty');
      return;
    }
    final path = await _storage.writePart(c.id, boneId, cut.pngBytes);
    final bone = c.skeleton!.byId(boneId);
    if (bone != null) {
      bone.imagePath = path;
      bone.imageRect = cut.rect;
      // Keep an existing (tuned) pivot; otherwise start at the joint end of the
      // selection, which is the sensible default for a limb.
      if (bone.pivot == Offset.zero) bone.pivot = cut.rect.topCenter;
    }
    final images = Map<String, ui.Image>.from(state.partImages);
    images[boneId] = await StorageService.decodeUiImage(cut.pngBytes);
    await _storage.saveCharacter(c);
    state = state.copyWith(character: c, partImages: images, busy: false, status: null);
    ref.read(libraryProvider.notifier).refresh();
  }

  // ------------------------------------------------------------------- layers

  Future<void> reorderLayers(int oldIndex, int newIndex) async {
    final c = state.character;
    final s = c?.skeleton;
    if (c == null || s == null) return;
    _recordHistory();
    // The list is shown top-of-stack first, so it is the reverse of drawOrder.
    final ordered = s.drawOrder.reversed.toList();
    final item = ordered.removeAt(oldIndex);
    ordered.insert(newIndex, item);
    for (var i = 0; i < ordered.length; i++) {
      ordered[i].zIndex = ordered.length - i;
    }
    await _storage.saveCharacter(c);
    state = state.copyWith(character: c);
  }

  Future<void> togglePartMirror(String boneId) async {
    final bone = state.character?.skeleton?.byId(boneId);
    if (bone == null) return;
    _recordHistory();
    bone.mirrored = !bone.mirrored;
    await _persist();
  }

  Future<void> togglePartVisible(String boneId) async {
    final bone = state.character?.skeleton?.byId(boneId);
    if (bone == null) return;
    _recordHistory();
    bone.visible = !bone.visible;
    await _persist();
  }

  Future<void> setPivot(String boneId, Offset pivot) async {
    final bone = state.character?.skeleton?.byId(boneId);
    if (bone == null) return;
    // History is recorded once when the drag begins (see recordHistory above).
    bone.pivot = pivot;
    await _persist();
  }

  Future<void> mirrorWholeRig() async {
    final c = state.character;
    final s = c?.skeleton;
    if (c == null || s == null) return;
    _recordHistory();
    c.skeleton = s.mirroredRig();
    // bitmaps swap sides too
    final swapped = <String, ui.Image>{};
    state.partImages.forEach((id, image) {
      swapped[Skeleton.swappedSideId(id)] = image;
    });
    await _storage.saveCharacter(c);
    state = state.copyWith(character: c, partImages: swapped);
  }

  Future<void> clearPart(String boneId) async {
    final bone = state.character?.skeleton?.byId(boneId);
    if (bone == null) return;
    _recordHistory();
    bone.imagePath = null;
    bone.imageRect = Rect.zero;
    final images = Map<String, ui.Image>.from(state.partImages)..remove(boneId);
    state = state.copyWith(partImages: images);
    await _persist();
  }

  Future<void> rename(String name) async {
    final c = state.character;
    if (c == null) return;
    c.name = name;
    await _persist();
    ref.read(libraryProvider.notifier).refresh();
  }

  Future<void> setLastClip(String clipName) async {
    final c = state.character;
    if (c == null) return;
    c.lastClip = clipName;
    await _storage.saveCharacter(c);
  }

  // ---------------------------------------------------------------------- props

  /// Adds a built-in prop (generated transparent PNG) to the rig.
  Future<void> addProp(String templateId) async {
    final c = state.character;
    final s = c?.skeleton;
    if (c == null || s == null) return;
    final id = '${templateId}_${DateTime.now().microsecondsSinceEpoch.toRadixString(36)}';
    _recordHistory();
    final png = await generatePropPng(templateId);
    final path = await _storage.writeProp(c.id, id, png);
    final image = await StorageService.decodeUiImage(png);
    final rigH = state.canvasSize.height;
    final bone = defaultBoneForProp(templateId);
    final lift = (bone == 'head') ? -rigH * 0.20 : rigH * 0.0; // raise headwear
    final prop = PropAttachment(
      id: id,
      label: templateId,
      imagePath: path,
      attachedBoneId: bone,
      localOffset: bone == 'head'
          ? Offset(0, lift)
          : (templateId == 'stick' ? Offset(rigH * 0.04, rigH * 0.04) : Offset(rigH * 0.06, 0)),
      scale: 0.5, // generated art is ~512px; start half size
    );
    s.props.add(prop);
    await _storage.saveCharacter(c);
    state = state.copyWith(
      character: c,
      propImages: {...state.propImages, id: image},
    );
    ref.read(libraryProvider.notifier).refresh();
  }

  Future<void> removeProp(String propId) async {
    final c = state.character;
    final s = c?.skeleton;
    if (c == null || s == null) return;
    _recordHistory();
    s.props.removeWhere((p) => p.id == propId);
    await _storage.deletePropFile(c.id, propId);
    final props = Map<String, ui.Image>.from(state.propImages)..remove(propId);
    await _storage.saveCharacter(c);
    state = state.copyWith(character: c, propImages: props);
    ref.read(libraryProvider.notifier).refresh();
  }

  Future<void> updateProp(String propId, PropAttachment changes) async {
    final s = state.character?.skeleton;
    if (s == null) return;
    final idx = s.props.indexWhere((p) => p.id == propId);
    if (idx < 0) return;
    s.props[idx] = changes;
    await _persist();
  }

  /// Reparents a prop to [boneId] (keeps geometry; you re-fine-tune after).
  Future<void> movePropToBone(String propId, String boneId) async {
    final props = state.character?.skeleton?.props ?? const <PropAttachment>[];
    PropAttachment? found;
    for (final x in props) {
      if (x.id == propId) found = x;
    }
    if (found == null) return;
    _recordHistory();
    final s = state.character!.skeleton!;
    final idx = s.props.indexWhere((x) => x.id == propId);
    s.props[idx] = found.copyWith(attachedBoneId: boneId);
    await _persist();
  }

  Future<void> _persist() async {
    final c = state.character;
    if (c == null) return;
    await _storage.saveCharacter(c);
    state = state.copyWith(character: c);
    ref.read(libraryProvider.notifier).refresh();
  }
}

final editorProvider =
    NotifierProvider<EditorController, EditorState>(EditorController.new);
