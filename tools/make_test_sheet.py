#!/usr/bin/env python3
"""Synthesise a filled character sheet for offline pipeline checks (pure Python, no dependencies).

    python3 tools/make_test_sheet.py --out /tmp/test-sheet.png [--which front-side]

Paints opaque placeholder artwork *inside* slot rectangles of a copy of the bundled blank template,
so the result is exactly what a user's finished sheet looks like: guide ink in the margins, artwork
in the slots. `tools/sheet_check.py` then reads it back and must reach the same conclusion the app
reaches. Variants:

  front        only the ten required front-body parts          -> riggable, front only
  front-side   required parts + a complete left profile        -> riggable, mirror offer expected
  full         every slot on the sheet                         -> all four views available

The shapes are plain rounded blobs - deliberately dumb, because extraction is fixed-coordinate and
must never depend on recognising what was drawn.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import png_rw  # noqa: E402

PALETTE = [
    (214, 93, 58), (62, 145, 128), (86, 110, 173), (176, 138, 60),
    (122, 84, 148), (70, 130, 90), (160, 74, 84), (96, 122, 140),
]


def blob_mask(w: int, h: int, seed: int) -> list[bool]:
    """A rounded, slightly lopsided silhouette so bounding boxes look like artwork, not fills."""
    mask: list[bool] = []
    cx, cy = w / 2.0, h / 2.0
    phase = seed * 0.7
    for y in range(h):
        for x in range(w):
            dx = (x - cx) / max(1.0, cx)
            dy = (y - cy) / max(1.0, cy)
            angle = math.atan2(dy, dx)
            radius = 0.86 + 0.10 * math.sin(angle * 3 + phase)
            mask.append(dx * dx + dy * dy <= radius * radius)
    return mask


def paint(image: png_rw.Image, slot: dict, color: tuple[int, int, int], seed: int) -> None:
    inset = 8
    x0, y0 = slot["x"] + inset, slot["y"] + inset
    w, h = slot["w"] - 2 * inset, slot["h"] - 2 * inset
    mask = blob_mask(w, h, seed)
    for row in range(h):
        base = ((y0 + row) * image.width + x0) * 4
        for column in range(w):
            if not mask[row * w + column]:
                continue
            offset = base + column * 4
            image.pixels[offset] = color[0]
            image.pixels[offset + 1] = color[1]
            image.pixels[offset + 2] = color[2]
            image.pixels[offset + 3] = 255


def select_slots(slots: list[dict], which: str) -> list[dict]:
    if which == "full":
        return slots
    chosen = [s for s in slots if s["required"]]
    if which in ("front-side", "full"):
        chosen += [s for s in slots if s["view"] == "SIDE_LEFT"]
    if which == "front-side":
        return chosen
    return chosen


def main(argv: list[str] | None = None) -> int:
    here = os.path.dirname(os.path.abspath(__file__))
    parser = argparse.ArgumentParser(description="Paint a synthetic filled character sheet.")
    parser.add_argument("--template", default=os.path.join(here, "..", "docs", "assets", "blank-character-sheet.png"))
    parser.add_argument("--slots", default=os.path.join(here, "slots.json"))
    parser.add_argument("--which", choices=["front", "front-side", "full"], default="front-side")
    parser.add_argument("--out", required=True)
    args = parser.parse_args(argv)

    with open(args.slots, "r", encoding="utf-8") as handle:
        template = json.load(handle)
    image = png_rw.read(os.path.abspath(args.template))

    chosen = select_slots(template["slots"], args.which)
    for index, slot in enumerate(chosen):
        paint(image, slot, PALETTE[index % len(PALETTE)], index)

    out = os.path.abspath(args.out)
    parent = os.path.dirname(out)
    if parent:
        os.makedirs(parent, exist_ok=True)
    size = png_rw.write(image, out)
    print(f"painted {len(chosen)} slots ({args.which}) -> {out} ({size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
