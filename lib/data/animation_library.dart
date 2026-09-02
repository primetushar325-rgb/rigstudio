import 'dart:ui';

import '../models/animation_clip.dart';
import '../models/geom_json.dart';

/// ---------------------------------------------------------------------------
/// Hand-authored animation library.
///
/// Every track targets a STANDARD bone id (see data/standard_rig.dart), so a
/// character only has to be rigged once. Angles are authored in degrees for
/// readability and converted to radians here.
///
/// Convention: positive rotation = clockwise on screen (y is down).
/// Root motion (bob, lean, sitting height) is authored as a translation offset
/// on `torso`, which every other bone inherits through FK.
/// ---------------------------------------------------------------------------

/// [t, degrees] or [t, degrees, dx, dy] — dx/dy are fractions of rig height,
/// scaled at playback time by [AnimationPlayback.rigHeight] (see fk.dart).
List<BoneKeyframe> _k(List<List<double>> rows) => rows
    .map((r) => BoneKeyframe(
          r[0],
          degToRad(r[1]),
          Offset(r.length > 2 ? r[2] : 0, r.length > 3 ? r[3] : 0),
        ))
    .toList();

// ---------------------------------------------------------------------------
// IDLE — quiet breathing, tiny arm sway.
// ---------------------------------------------------------------------------
final AnimationClip kIdle = AnimationClip(
  name: 'idle',
  label: 'Idle',
  durationSeconds: 2.6,
  loop: true,
  tracks: {
    'torso': _k([
      [0.0, 0, 0, 0],
      [0.5, 1.0, 0, -0.006],
      [1.0, 0, 0, 0],
    ]),
    'head': _k([
      [0.0, 0],
      [0.35, -1.5],
      [0.75, 1.5],
      [1.0, 0],
    ]),
    'upper_arm_l': _k([
      [0.0, 3],
      [0.5, 6],
      [1.0, 3],
    ]),
    'forearm_l': _k([
      [0.0, 2],
      [0.5, 5],
      [1.0, 2],
    ]),
    'upper_arm_r': _k([
      [0.0, -3],
      [0.5, -6],
      [1.0, -3],
    ]),
    'forearm_r': _k([
      [0.0, -2],
      [0.5, -5],
      [1.0, -2],
    ]),
    'thigh_l': _k([
      [0.0, 0],
      [1.0, 0],
    ]),
    'thigh_r': _k([
      [0.0, 0],
      [1.0, 0],
    ]),
  },
);

// ---------------------------------------------------------------------------
// STAND — neutral rest pose (also used as the "reset" clip).
// ---------------------------------------------------------------------------
final AnimationClip kStand = AnimationClip(
  name: 'stand',
  label: 'Stand',
  durationSeconds: 1.0,
  loop: true,
  tracks: {
    for (final id in [
      'torso',
      'head',
      'upper_arm_l',
      'forearm_l',
      'hand_l',
      'upper_arm_r',
      'forearm_r',
      'hand_r',
      'thigh_l',
      'shin_l',
      'foot_l',
      'thigh_r',
      'shin_r',
      'foot_r',
    ])
      id: _k([
        [0.0, 0],
        [1.0, 0],
      ]),
  },
);

// ---------------------------------------------------------------------------
// WALK — 1s cycle, contralateral arms, vertical bob at mid-stance.
// ---------------------------------------------------------------------------
final AnimationClip kWalk = AnimationClip(
  name: 'walk',
  label: 'Walk',
  durationSeconds: 1.0,
  loop: true,
  tracks: {
    'torso': _k([
      [0.00, 1.5, 0, 0],
      [0.25, 2.5, 0, -0.012],
      [0.50, 1.5, 0, 0],
      [0.75, 2.5, 0, -0.012],
      [1.00, 1.5, 0, 0],
    ]),
    'head': _k([
      [0.00, -1.5],
      [0.25, -3],
      [0.50, -1.5],
      [0.75, -3],
      [1.00, -1.5],
    ]),
    // left leg forward at t=0, right leg forward at t=0.5
    'thigh_l': _k([
      [0.00, -26],
      [0.25, -6],
      [0.50, 22],
      [0.75, 4],
      [1.00, -26],
    ]),
    'shin_l': _k([
      [0.00, 8],
      [0.15, 26],
      [0.35, 6],
      [0.50, 2],
      [0.70, 42],
      [1.00, 8],
    ]),
    'foot_l': _k([
      [0.00, 8],
      [0.25, 0],
      [0.50, -12],
      [0.75, 6],
      [1.00, 8],
    ]),
    'thigh_r': _k([
      [0.00, 22],
      [0.25, 4],
      [0.50, -26],
      [0.75, -6],
      [1.00, 22],
    ]),
    'shin_r': _k([
      [0.00, 2],
      [0.20, 42],
      [0.50, 8],
      [0.65, 26],
      [0.85, 6],
      [1.00, 2],
    ]),
    'foot_r': _k([
      [0.00, -12],
      [0.25, 6],
      [0.50, 8],
      [0.75, 0],
      [1.00, -12],
    ]),
    // arms swing opposite the legs
    'upper_arm_l': _k([
      [0.00, 24],
      [0.50, -24],
      [1.00, 24],
    ]),
    'forearm_l': _k([
      [0.00, 14],
      [0.50, 22],
      [1.00, 14],
    ]),
    'upper_arm_r': _k([
      [0.00, -24],
      [0.50, 24],
      [1.00, -24],
    ]),
    'forearm_r': _k([
      [0.00, -22],
      [0.50, -14],
      [1.00, -22],
    ]),
  },
);

// ---------------------------------------------------------------------------
// RUN — faster, bigger amplitude, forward lean, real airborne bob.
// ---------------------------------------------------------------------------
final AnimationClip kRun = AnimationClip(
  name: 'run',
  label: 'Run',
  durationSeconds: 0.62,
  loop: true,
  tracks: {
    'torso': _k([
      [0.00, 8, 0, -0.010],
      [0.15, 8, 0, 0.012],
      [0.35, 8, 0, -0.030],
      [0.50, 8, 0, -0.010],
      [0.65, 8, 0, 0.012],
      [0.85, 8, 0, -0.030],
      [1.00, 8, 0, -0.010],
    ]),
    'head': _k([
      [0.00, -8],
      [0.50, -10],
      [1.00, -8],
    ]),
    'thigh_l': _k([
      [0.00, -48],
      [0.25, -8],
      [0.50, 38],
      [0.75, 6],
      [1.00, -48],
    ]),
    'shin_l': _k([
      [0.00, 26],
      [0.18, 70],
      [0.40, 8],
      [0.55, 4],
      [0.78, 96],
      [1.00, 26],
    ]),
    'foot_l': _k([
      [0.00, 12],
      [0.50, -18],
      [1.00, 12],
    ]),
    'thigh_r': _k([
      [0.00, 38],
      [0.25, 6],
      [0.50, -48],
      [0.75, -8],
      [1.00, 38],
    ]),
    'shin_r': _k([
      [0.00, 4],
      [0.28, 96],
      [0.50, 26],
      [0.68, 70],
      [0.90, 8],
      [1.00, 4],
    ]),
    'foot_r': _k([
      [0.00, -18],
      [0.50, 12],
      [1.00, -18],
    ]),
    'upper_arm_l': _k([
      [0.00, 52],
      [0.50, -46],
      [1.00, 52],
    ]),
    'forearm_l': _k([
      [0.00, 62],
      [0.50, 78],
      [1.00, 62],
    ]),
    'upper_arm_r': _k([
      [0.00, -46],
      [0.50, 52],
      [1.00, -46],
    ]),
    'forearm_r': _k([
      [0.00, -78],
      [0.50, -62],
      [1.00, -78],
    ]),
  },
);

// ---------------------------------------------------------------------------
// WAVE — screen-right arm raised, hand oscillates, weight shift.
// ---------------------------------------------------------------------------
final AnimationClip kWave = AnimationClip(
  name: 'wave',
  label: 'Wave',
  durationSeconds: 1.8,
  loop: true,
  tracks: {
    'torso': _k([
      [0.00, 0, 0, 0],
      [0.30, -2, 0, -0.004],
      [0.80, -2, 0, -0.004],
      [1.00, 0, 0, 0],
    ]),
    'head': _k([
      [0.00, 0],
      [0.25, 5],
      [0.75, 5],
      [1.00, 0],
    ]),
    'upper_arm_r': _k([
      [0.00, 0],
      [0.18, -125],
      [0.85, -125],
      [1.00, 0],
    ]),
    'forearm_r': _k([
      [0.00, 0],
      [0.20, -35],
      [0.34, 18],
      [0.48, -35],
      [0.62, 18],
      [0.76, -35],
      [0.88, -10],
      [1.00, 0],
    ]),
    'hand_r': _k([
      [0.00, 0],
      [0.22, -18],
      [0.36, 16],
      [0.50, -18],
      [0.64, 16],
      [0.78, -18],
      [1.00, 0],
    ]),
    'upper_arm_l': _k([
      [0.00, 0],
      [0.5, 6],
      [1.00, 0],
    ]),
    'forearm_l': _k([
      [0.00, 0],
      [0.5, 8],
      [1.00, 0],
    ]),
  },
);

// ---------------------------------------------------------------------------
// TALK — head nods/tilts, small conversational hand gestures.
// ---------------------------------------------------------------------------
final AnimationClip kTalk = AnimationClip(
  name: 'talk',
  label: 'Talk',
  durationSeconds: 2.2,
  loop: true,
  tracks: {
    'torso': _k([
      [0.00, 0, 0, 0],
      [0.30, 1.5, 0, -0.004],
      [0.60, -1.5, 0, 0],
      [1.00, 0, 0, 0],
    ]),
    'head': _k([
      [0.00, 0],
      [0.12, -6],
      [0.26, 3],
      [0.40, -5],
      [0.55, 4],
      [0.70, -6],
      [0.85, 2],
      [1.00, 0],
    ]),
    // negative on the left / positive on the right folds the arms IN toward
    // the body, which is what conversational gestures look like head-on
    'upper_arm_l': _k([
      [0.00, -5],
      [0.25, -13],
      [0.45, -7],
      [0.70, -15],
      [1.00, -5],
    ]),
    'forearm_l': _k([
      [0.00, -22],
      [0.25, -42],
      [0.45, -28],
      [0.70, -46],
      [1.00, -22],
    ]),
    'hand_l': _k([
      [0.00, 0],
      [0.3, -14],
      [0.6, 10],
      [1.00, 0],
    ]),
    'upper_arm_r': _k([
      [0.00, 5],
      [0.30, 14],
      [0.55, 8],
      [0.80, 12],
      [1.00, 5],
    ]),
    'forearm_r': _k([
      [0.00, 20],
      [0.30, 44],
      [0.55, 26],
      [0.80, 40],
      [1.00, 20],
    ]),
    'hand_r': _k([
      [0.00, 0],
      [0.35, 14],
      [0.65, -10],
      [1.00, 0],
    ]),
  },
);

// ---------------------------------------------------------------------------
// SIT — settle down onto an invisible chair, then breathe.
// ---------------------------------------------------------------------------
final AnimationClip kSit = AnimationClip(
  name: 'sit',
  label: 'Sit',
  durationSeconds: 3.0,
  loop: true,
  tracks: {
    'torso': _k([
      [0.00, 0, 0, 0.150],
      [0.50, 1.5, 0, 0.156],
      [1.00, 0, 0, 0.150],
    ]),
    'head': _k([
      [0.00, 0],
      [0.5, -2],
      [1.00, 0],
    ]),
    'thigh_l': _k([
      [0.00, -82],
      [1.00, -82],
    ]),
    'shin_l': _k([
      [0.00, 80],
      [1.00, 80],
    ]),
    'foot_l': _k([
      [0.00, 4],
      [1.00, 4],
    ]),
    'thigh_r': _k([
      [0.00, -78],
      [1.00, -78],
    ]),
    'shin_r': _k([
      [0.00, 76],
      [1.00, 76],
    ]),
    'foot_r': _k([
      [0.00, 4],
      [1.00, 4],
    ]),
    'upper_arm_l': _k([
      [0.00, -20],
      [0.5, -23],
      [1.00, -20],
    ]),
    'forearm_l': _k([
      [0.00, -44],
      [0.5, -41],
      [1.00, -44],
    ]),
    'upper_arm_r': _k([
      [0.00, 18],
      [0.5, 21],
      [1.00, 18],
    ]),
    'forearm_r': _k([
      [0.00, 48],
      [0.5, 45],
      [1.00, 48],
    ]),
  },
);

// ---------------------------------------------------------------------------
// SLEEP — lying on the ground (whole rig rotated), slow breathing.
// ---------------------------------------------------------------------------
final AnimationClip kSleep = AnimationClip(
  name: 'sleep',
  label: 'Sleep',
  durationSeconds: 4.0,
  loop: true,
  tracks: {
    // -90 deg lays the character down; the offset drops it to the floor line.
    'torso': _k([
      [0.00, -90, -0.06, 0.300],
      [0.50, -88, -0.06, 0.306],
      [1.00, -90, -0.06, 0.300],
    ]),
    'head': _k([
      [0.00, 8],
      [0.5, 11],
      [1.00, 8],
    ]),
    'thigh_l': _k([
      [0.00, 18],
      [1.00, 18],
    ]),
    'shin_l': _k([
      [0.00, -30],
      [1.00, -30],
    ]),
    'thigh_r': _k([
      [0.00, 10],
      [1.00, 10],
    ]),
    'shin_r': _k([
      [0.00, -18],
      [1.00, -18],
    ]),
    'upper_arm_l': _k([
      [0.00, -26],
      [0.5, -23],
      [1.00, -26],
    ]),
    'forearm_l': _k([
      [0.00, -22],
      [1.00, -22],
    ]),
    'upper_arm_r': _k([
      [0.00, 24],
      [0.5, 27],
      [1.00, 24],
    ]),
    'forearm_r': _k([
      [0.00, 18],
      [1.00, 18],
    ]),
  },
);

// ---------------------------------------------------------------------------
// JUMP — premium extra, shows how gating works.
// ---------------------------------------------------------------------------
final AnimationClip kJump = AnimationClip(
  name: 'jump',
  label: 'Jump',
  durationSeconds: 1.2,
  loop: true,
  premium: true,
  tracks: {
    'torso': _k([
      [0.00, 0, 0, 0],
      [0.18, 10, 0, 0.060], // crouch
      [0.34, -6, 0, -0.150], // launch
      [0.52, 0, 0, -0.210], // apex
      [0.72, 6, 0, -0.060],
      [0.85, 12, 0, 0.070], // land
      [1.00, 0, 0, 0],
    ]),
    'thigh_l': _k([
      [0.00, 0],
      [0.18, -70],
      [0.34, -10],
      [0.52, -34],
      [0.85, -70],
      [1.00, 0],
    ]),
    'shin_l': _k([
      [0.00, 0],
      [0.18, 74],
      [0.34, 8],
      [0.52, 46],
      [0.85, 74],
      [1.00, 0],
    ]),
    'thigh_r': _k([
      [0.00, 0],
      [0.18, -66],
      [0.34, -8],
      [0.52, -28],
      [0.85, -66],
      [1.00, 0],
    ]),
    'shin_r': _k([
      [0.00, 0],
      [0.18, 70],
      [0.34, 6],
      [0.52, 40],
      [0.85, 70],
      [1.00, 0],
    ]),
    'upper_arm_l': _k([
      [0.00, 0],
      [0.18, 40],
      [0.34, -120],
      [0.52, -140],
      [0.85, 40],
      [1.00, 0],
    ]),
    'upper_arm_r': _k([
      [0.00, 0],
      [0.18, -40],
      [0.34, 120],
      [0.52, 140],
      [0.85, -40],
      [1.00, 0],
    ]),
    'head': _k([
      [0.00, 0],
      [0.34, -8],
      [0.85, 8],
      [1.00, 0],
    ]),
  },
);

/// Everything the animate screen lists, in display order.
final List<AnimationClip> kAnimationLibrary = [
  kIdle,
  kStand,
  kWalk,
  kRun,
  kWave,
  kTalk,
  kSit,
  kSleep,
  kJump,
];

AnimationClip clipByName(String name) =>
    kAnimationLibrary.firstWhere((c) => c.name == name, orElse: () => kIdle);
