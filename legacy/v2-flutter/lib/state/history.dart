/// A simple two-stack undo/redo history of immutable snapshots.
///
/// ```dart
/// final h = History<int>();
/// h.record(() => /* caller mutates its own state */);
/// h.canUndo; h.undo(); h.redo();
/// ```
/// The caller owns the actual state and supplies a snapshot + restore closure.
class History<T> {
  History({this.capacity = 40});

  final int capacity;
  final List<T> _undo = <T>[];
  final List<T> _redo = <T>[];

  bool get canUndo => _undo.isNotEmpty;
  bool get canRedo => _redo.isNotEmpty;

  /// Call *before* a mutating operation. [snapshotOf] returns the current
  /// (pre-operation) state which [restore] can later apply.
  void record(T Function() snapshotOf) {
    _undo.add(snapshotOf());
    if (_undo.length > capacity) _undo.removeAt(0);
    _redo.clear();
  }

  /// Returns the pre-operation snapshot to restore, or null if nothing to undo.
  /// The caller must call [restore] with it.
  T? undo(T Function() snapshotOfCurrent) {
    if (_undo.isEmpty) return null;
    final prev = _undo.removeLast();
    _redo.add(snapshotOfCurrent());
    return prev;
  }

  /// Returns the snapshot to restore when redoing, or null.
  T? redo(T Function() snapshotOfCurrent) {
    if (_redo.isEmpty) return null;
    final next = _redo.removeLast();
    _undo.add(snapshotOfCurrent());
    return next;
  }

  void clear() {
    _undo.clear();
    _redo.clear();
  }
}
