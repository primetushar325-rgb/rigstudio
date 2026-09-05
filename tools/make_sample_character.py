#!/usr/bin/env python3
"""Paint RigStudio's original sample character sheet (pure Python, no dependencies).

    python3 tools/make_sample_character.py [--out docs/assets/sample-character-sheet.png]

The app ships a runtime-drawn sample character (`app/.../art/SampleCharacterArt.kt`) so a fresh
install has something to animate before the user draws anything. This script paints the same idea
into a real 2048x2048 sheet PNG - every one of the 60 slots filled with original placeholder
artwork - which gives the offline tooling a complete, importable test character:

  * `tools/sheet_check.py` must see it as riggable with all four views, 5 expressions, 11 mouths;
  * `tools/render_previews.sh` runs it through extract -> rig -> all 18 clips and renders frames.

The artwork is deliberately simple (flat shapes, no outlines, no gradients): it exists to exercise
the pipeline, not to be a mascot. Shapes are defined per slot in normalised slot coordinates, so
they follow the template geometry wherever the template moves.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import png_rw  # noqa: E402

SKIN = (238, 196, 156)
SKIN_SHADE = (214, 168, 128)
HAIR = (74, 54, 46)
JACKET = (58, 132, 116)
JACKET_DARK = (42, 100, 88)
PANTS = (72, 88, 122)
PANTS_DARK = (56, 68, 96)
SHOES = (198, 122, 62)
SHOES_DARK = (160, 96, 48)
EYE_WHITE = (246, 246, 244)
INK = (44, 42, 48)
MOUTH_INK = (146, 62, 62)
TEETH = (242, 240, 236)

FULL = (0.0, 0.0, 1.0, 1.0)


class Painter:
    """Opaque shape painter in normalised slot coordinates."""

    def __init__(self, image: png_rw.Image, slot: dict):
        self.image = image
        self.x0 = slot["x"]
        self.y0 = slot["y"]
        self.w = slot["w"]
        self.h = slot["h"]

    def fill(self, predicate, color: tuple[int, int, int]) -> None:
        for row in range(self.h):
            y = (row + 0.5) / self.h
            base = ((self.y0 + row) * self.image.width + self.x0) * 4
            for column in range(self.w):
                x = (column + 0.5) / self.w
                if predicate(x, y):
                    offset = base + column * 4
                    self.image.pixels[offset] = color[0]
                    self.image.pixels[offset + 1] = color[1]
                    self.image.pixels[offset + 2] = color[2]
                    self.image.pixels[offset + 3] = 255

    # --- primitive predicates -------------------------------------------------------------
    @staticmethod
    def rect(x0: float, y0: float, x1: float, y1: float):
        return lambda x, y: x0 <= x <= x1 and y0 <= y <= y1

    @staticmethod
    def ellipse(cx: float, cy: float, rx: float, ry: float):
        def predicate(x: float, y: float) -> bool:
            dx = (x - cx) / rx
            dy = (y - cy) / ry
            return dx * dx + dy * dy <= 1.0
        return predicate

    @staticmethod
    def capsule(x0: float, y0: float, x1: float, y1: float, radius: float):
        def predicate(x: float, y: float) -> bool:
            vx, vy = x1 - x0, y1 - y0
            length2 = vx * vx + vy * vy
            if length2 <= 1e-9:
                t = 0.0
            else:
                t = max(0.0, min(1.0, ((x - x0) * vx + (y - y0) * vy) / length2))
            dx = x - (x0 + t * vx)
            dy = y - (y0 + t * vy)
            return dx * dx + dy * dy <= radius * radius
        return predicate

    @staticmethod
    def ring(cx: float, cy: float, rx: float, ry: float, thickness: float, upper: bool):
        """Half-ellipse outline: lower half (a smile) or upper half (a frown/closed eye)."""
        def predicate(x: float, y: float) -> bool:
            dx = (x - cx) / rx
            dy = (y - cy) / ry
            distance = math.sqrt(dx * dx + dy * dy)
            if abs(distance - 1.0) > thickness:
                return False
            return (y <= cy) if upper else (y >= cy)
        return predicate

    @staticmethod
    def union(*predicates):
        return lambda x, y: any(p(x, y) for p in predicates)

    @staticmethod
    def difference(positive, negative):
        return lambda x, y: positive(x, y) and not negative(x, y)


# --- per-slot artwork ---------------------------------------------------------------------

def front_head(p: Painter) -> None:
    head = p.ellipse(0.5, 0.56, 0.40, 0.42)
    p.fill(head, SKIN)
    hair = p.difference(p.ellipse(0.5, 0.46, 0.42, 0.40), p.rect(0.0, 0.34, 1.0, 1.0))
    p.fill(hair, HAIR)
    p.fill(p.union(p.rect(0.08, 0.30, 0.16, 0.52), p.rect(0.84, 0.30, 0.92, 0.52)), HAIR)


def front_torso(p: Painter) -> None:
    p.fill(p.union(p.capsule(0.26, 0.14, 0.74, 0.14, 0.13), p.rect(0.20, 0.12, 0.80, 0.90),
                   p.capsule(0.5, 0.88, 0.5, 0.90, 0.30)), JACKET)
    p.fill(p.rect(0.485, 0.10, 0.515, 0.92), JACKET_DARK)
    p.fill(p.rect(0.34, 0.04, 0.66, 0.12), JACKET_DARK)


def sleeve(p: Painter, shade: tuple[int, int, int] = JACKET) -> None:
    p.fill(p.capsule(0.5, 0.14, 0.5, 0.86, 0.34), shade)


def hand(p: Painter) -> None:
    p.fill(p.ellipse(0.5, 0.5, 0.36, 0.44), SKIN)


def leg(p: Painter, shade: tuple[int, int, int]) -> None:
    p.fill(p.capsule(0.5, 0.08, 0.5, 0.92, 0.36), shade)


def shoe(p: Painter, facing: int = 0) -> None:
    body = p.union(p.rect(0.26, 0.24, 0.74, 0.70), p.ellipse(0.5 + 0.14 * facing, 0.62, 0.34, 0.17))
    p.fill(body, SHOES)
    p.fill(p.rect(0.20, 0.72, 0.86, 0.84), SHOES_DARK)


def side_head(p: Painter, facing_left: bool) -> None:
    direction = -1 if facing_left else 1
    head = p.union(p.ellipse(0.5, 0.56, 0.38, 0.42),
                   p.ellipse(0.5 + 0.34 * direction, 0.62, 0.10, 0.08))
    p.fill(head, SKIN)
    hair = p.difference(p.ellipse(0.5 - 0.06 * direction, 0.48, 0.40, 0.40),
                        p.rect(0.0, 0.36, 1.0, 1.0))
    p.fill(hair, HAIR)
    p.fill(p.ellipse(0.5 - 0.16 * direction, 0.50, 0.16, 0.26), HAIR)


def side_torso(p: Painter) -> None:
    p.fill(p.union(p.capsule(0.5, 0.14, 0.5, 0.88, 0.30), p.rect(0.28, 0.12, 0.72, 0.90)), JACKET)
    p.fill(p.rect(0.30, 0.04, 0.70, 0.12), JACKET_DARK)


def back_head(p: Painter) -> None:
    p.fill(p.ellipse(0.5, 0.56, 0.40, 0.42), HAIR)
    p.fill(p.ellipse(0.5, 0.90, 0.20, 0.10), SKIN_SHADE)


def back_torso(p: Painter) -> None:
    p.fill(p.union(p.capsule(0.26, 0.14, 0.74, 0.14, 0.13), p.rect(0.20, 0.12, 0.80, 0.90),
                   p.capsule(0.5, 0.88, 0.5, 0.90, 0.30)), JACKET)
    p.fill(p.rect(0.49, 0.10, 0.51, 0.92), JACKET_DARK)


EYES = {
    "NEUTRAL": lambda p: (
        p.fill(p.union(p.ellipse(0.32, 0.50, 0.13, 0.17), p.ellipse(0.68, 0.50, 0.13, 0.17)), EYE_WHITE),
        p.fill(p.union(p.ellipse(0.33, 0.52, 0.06, 0.09), p.ellipse(0.67, 0.52, 0.06, 0.09)), INK),
    ),
    "CLOSED": lambda p: p.fill(
        p.union(p.ring(0.32, 0.48, 0.13, 0.14, 0.16, upper=False),
                p.ring(0.68, 0.48, 0.13, 0.14, 0.16, upper=False)), INK),
    "HAPPY": lambda p: p.fill(
        p.union(p.ring(0.32, 0.56, 0.14, 0.16, 0.16, upper=True),
                p.ring(0.68, 0.56, 0.14, 0.16, 0.16, upper=True)), INK),
    "SAD": lambda p: (
        p.fill(p.union(p.ellipse(0.32, 0.54, 0.12, 0.15), p.ellipse(0.68, 0.54, 0.12, 0.15)), EYE_WHITE),
        p.fill(p.union(p.ellipse(0.33, 0.58, 0.055, 0.08), p.ellipse(0.67, 0.58, 0.055, 0.08)), INK),
        p.fill(p.union(p.capsule(0.20, 0.34, 0.44, 0.42, 0.045), p.capsule(0.80, 0.34, 0.56, 0.42, 0.045)), INK),
    ),
    "ANGRY": lambda p: (
        p.fill(p.union(p.ellipse(0.32, 0.56, 0.12, 0.14), p.ellipse(0.68, 0.56, 0.12, 0.14)), EYE_WHITE),
        p.fill(p.union(p.ellipse(0.34, 0.58, 0.06, 0.08), p.ellipse(0.66, 0.58, 0.06, 0.08)), INK),
        p.fill(p.union(p.capsule(0.18, 0.30, 0.44, 0.42, 0.055), p.capsule(0.82, 0.30, 0.56, 0.42, 0.055)), INK),
    ),
}


def mouth_pair(p: Painter, top: bool, bottom: bool) -> None:
    if top:
        p.fill(p.rect(0.30, 0.34, 0.70, 0.44), TEETH)
    if bottom:
        p.fill(p.rect(0.32, 0.62, 0.68, 0.70), TEETH)


MOUTHS = {
    "NORMAL": lambda p: p.fill(p.capsule(0.36, 0.52, 0.64, 0.52, 0.045), MOUTH_INK),
    "CLOSED": lambda p: p.fill(p.capsule(0.40, 0.52, 0.60, 0.52, 0.035), MOUTH_INK),
    "A": lambda p: (p.fill(p.ellipse(0.5, 0.52, 0.16, 0.24), MOUTH_INK), mouth_pair(p, True, False)),
    "E": lambda p: (p.fill(p.ellipse(0.5, 0.52, 0.22, 0.14), MOUTH_INK), mouth_pair(p, True, False)),
    "I": lambda p: p.fill(p.capsule(0.32, 0.52, 0.68, 0.52, 0.05), MOUTH_INK),
    "O": lambda p: p.fill(p.ellipse(0.5, 0.52, 0.13, 0.19), MOUTH_INK),
    "U": lambda p: p.fill(p.ellipse(0.5, 0.54, 0.09, 0.12), MOUTH_INK),
    "SMILE": lambda p: p.fill(p.ring(0.5, 0.42, 0.24, 0.26, 0.14, upper=False), MOUTH_INK),
    "SAD": lambda p: p.fill(p.ring(0.5, 0.62, 0.22, 0.24, 0.14, upper=True), MOUTH_INK),
    "SURPRISED": lambda p: p.fill(p.ellipse(0.5, 0.54, 0.11, 0.15), MOUTH_INK),
    "ANGRY": lambda p: (p.fill(p.rect(0.32, 0.42, 0.68, 0.62), MOUTH_INK), mouth_pair(p, True, True)),
}


def paint_slot(painter: Painter, slot: dict) -> None:
    slot_id = slot["id"]
    kind = slot["kind"]
    if kind == "EYE":
        EYES[slot["expression"]](painter)
        return
    if kind == "MOUTH":
        MOUTHS[slot["mouthShape"]](painter)
        return

    view = slot["view"]
    part = slot_id
    for prefix in ("side_left_", "side_right_", "front_", "back_"):
        if part.startswith(prefix):
            part = part[len(prefix):]
            break
    if view == "FRONT":
        if part == "head":
            front_head(painter)
        elif part == "torso":
            front_torso(painter)
        elif part in ("upper_arm_l", "upper_arm_r", "forearm_l", "forearm_r"):
            sleeve(painter)
        elif part in ("hand_l", "hand_r"):
            hand(painter)
        elif part in ("thigh_l", "thigh_r"):
            leg(painter, PANTS)
        elif part in ("shin_l", "shin_r"):
            leg(painter, PANTS_DARK)
        else:
            shoe(painter)
    elif view == "BACK":
        if part == "head":
            back_head(painter)
        elif part == "torso":
            back_torso(painter)
        elif part in ("upper_arm_l", "upper_arm_r", "forearm_l", "forearm_r"):
            sleeve(painter, JACKET_DARK)
        elif part in ("hand_l", "hand_r"):
            hand(painter)
        elif part in ("thigh_l", "thigh_r"):
            leg(painter, PANTS)
        elif part in ("shin_l", "shin_r"):
            leg(painter, PANTS_DARK)
        else:
            shoe(painter)
    else:
        facing_left = view == "SIDE_LEFT"
        if part == "head":
            side_head(painter, facing_left)
        elif part == "torso":
            side_torso(painter)
        elif part in ("upper_arm", "forearm"):
            sleeve(painter, JACKET if part == "upper_arm" else JACKET_DARK)
        elif part == "hand":
            hand(painter)
        elif part == "thigh":
            leg(painter, PANTS)
        elif part == "shin":
            leg(painter, PANTS_DARK)
        else:
            shoe(painter, -1 if facing_left else 1)


def main(argv: list[str] | None = None) -> int:
    here = os.path.dirname(os.path.abspath(__file__))
    parser = argparse.ArgumentParser(description="Paint the sample character sheet.")
    parser.add_argument("--slots", default=os.path.join(here, "slots.json"))
    parser.add_argument("--out", default=os.path.join(here, "..", "docs", "assets", "sample-character-sheet.png"))
    args = parser.parse_args(argv)

    with open(args.slots, "r", encoding="utf-8") as handle:
        template = json.load(handle)

    image = png_rw.Image.blank(template["sheetWidth"], template["sheetHeight"])
    for slot in template["slots"]:
        paint_slot(Painter(image, slot), slot)

    out = os.path.abspath(args.out)
    parent = os.path.dirname(out)
    if parent:
        os.makedirs(parent, exist_ok=True)
    size = png_rw.write(image, out)
    print(f"sample character: {len(template['slots'])} slots painted -> {out} ({size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
