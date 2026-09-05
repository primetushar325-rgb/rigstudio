#!/usr/bin/env python3
"""Reference renderer for the blank RigStudio character sheet (pure Python, no dependencies).

    python3 tools/render_template.py [--layout tools/layout.json] [--out docs/assets/blank-character-sheet.png]

The app draws the real template itself (`app/.../art/TemplateArt.kt`), but it does not decide *where*
anything goes: the layout is solved in the core module (`TemplateLayoutSolver`) as plain geometry and
proved by the core test suite to satisfy the rule that protects extraction —

    No guide ink may ever land inside a slot rectangle.

This script rasterises that same solved layout (`tools/layout.json`, produced by
`tools/dump_slots.sh`) with a 5x7 bitmap font, so documentation, code review and CI can all see the
exact sheet a user gets, without an Android toolchain. Because both renderers consume one layout,
they cannot drift.

`tools/sheet_check.py --template` then reads the PNG back and verifies, pixel by pixel, that nothing
was drawn inside a slot.
"""

from __future__ import annotations

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import png_rw  # noqa: E402
from font5x7 import ADVANCE, GLYPH_HEIGHT, glyph, text_width  # noqa: E402

# Ink colours per role, matching TemplateArt.kt (ARGB there, RGBA here).
ROLE_COLORS: dict[str, tuple[int, int, int, int]] = {
    "FRAME": (0x78, 0x8C, 0xAA, 0x50),
    "GUIDE": (0x7A, 0x8C, 0xAA, 0x6E),
    "REQUIRED": (0x3F, 0xBF, 0xAE, 0xCD),
    "PIVOT": (0x3F, 0xBF, 0xAE, 0xA0),
    "LABEL": (0x9A, 0xA6, 0xBC, 0xCD),
    "LABEL_REQUIRED": (0x3F, 0xBF, 0xAE, 0xE1),
    "GROUP": (0xE7, 0xEC, 0xF5, 0xB4),
    "TITLE": (0xE7, 0xEC, 0xF5, 0x87),
    "INSTRUCTION": (0x9A, 0xA6, 0xBC, 0xD7),
}

# The 5x7 font is 7 rows tall; the solver's ink height is 0.9 * sizePx.
FONT_INK_ROWS = 7


class Canvas:
    def __init__(self, width: int, height: int):
        self.image = png_rw.Image.blank(width, height)
        self.width = width
        self.height = height

    def blend(self, x: int, y: int, rgba: tuple[int, int, int, int]) -> None:
        """Source-over compositing, so overlapping guides read like the app's anti-aliased ink."""
        if not (0 <= x < self.width and 0 <= y < self.height):
            return
        offset = (y * self.width + x) * 4
        pixels = self.image.pixels
        alpha = rgba[3] / 255.0
        destination_alpha = pixels[offset + 3] / 255.0
        out_alpha = alpha + destination_alpha * (1 - alpha)
        if out_alpha <= 0:
            return
        for channel in range(3):
            source = rgba[channel] * alpha
            destination = pixels[offset + channel] * destination_alpha * (1 - alpha)
            pixels[offset + channel] = int(round((source + destination) / out_alpha))
        pixels[offset + 3] = int(round(out_alpha * 255))

    def fill_rect(self, x: int, y: int, w: int, h: int, rgba) -> None:
        for row in range(max(0, y), min(self.height, y + h)):
            for column in range(max(0, x), min(self.width, x + w)):
                self.blend(column, row, rgba)

    def fill_triangle(self, points: list[tuple[float, float]], rgba) -> None:
        xs = [p[0] for p in points]
        ys = [p[1] for p in points]
        top = max(0, int(min(ys)))
        bottom = min(self.height - 1, int(max(ys)) + 1)
        left = max(0, int(min(xs)))
        right = min(self.width - 1, int(max(xs)) + 1)

        def sign(a, b, c) -> float:
            return (a[0] - c[0]) * (b[1] - c[1]) - (b[0] - c[0]) * (a[1] - c[1])

        for y in range(top, bottom + 1):
            for x in range(left, right + 1):
                point = (x + 0.5, y + 0.5)
                d1 = sign(point, points[0], points[1])
                d2 = sign(point, points[1], points[2])
                d3 = sign(point, points[2], points[0])
                negative = d1 < 0 or d2 < 0 or d3 < 0
                positive = d1 > 0 or d2 > 0 or d3 > 0
                if not (negative and positive):
                    self.blend(x, y, rgba)

    def draw_text(self, x: int, y: int, string: str, rgba, scale: int) -> None:
        cursor = int(x)
        for character in string:
            for row, line in enumerate(glyph(character)):
                for column, mark in enumerate(line):
                    if mark != "#":
                        continue
                    for dy in range(scale):
                        for dx in range(scale):
                            self.blend(cursor + column * scale + dx, int(y) + row * scale + dy, rgba)
            cursor += ADVANCE * scale

    def draw_text_vertical(self, right_x: int, bottom_y: int, string: str, rgba, scale: int) -> None:
        """Rotated 90 degrees counter-clockwise, read bottom-to-top, baseline on x = right_x."""
        cursor = int(bottom_y)
        thickness = GLYPH_HEIGHT * scale
        for character in reversed(string):
            for row, line in enumerate(glyph(character)):
                for column, mark in enumerate(line):
                    if mark != "#":
                        continue
                    for dy in range(scale):
                        for dx in range(scale):
                            x = int(right_x) - thickness + (GLYPH_HEIGHT - 1 - row) * scale + scale - dy
                            y = cursor - GLYPH_HEIGHT * scale + column * scale + dx
                            self.blend(x, y, rgba)
            cursor -= ADVANCE * scale


def fit_scale(text: str, size_px: int, box_w: int, box_h: int, vertical: bool) -> int:
    """Largest integer font scale that keeps the glyphs inside the box the solver allotted.

    The solver sizes text from an average character width (0.66em) while this bitmap font advances
    6/7 em, so the reference renderer must shrink exactly like TemplateArt does with the real
    typeface - otherwise a long label could overflow its box and land inside a neighbouring slot.
    """
    run = max(1, text_width(text, 1))
    thickness_limit = box_h if vertical else box_w
    length_limit = box_w if vertical else box_h
    # Glyph ink runs along the box height when vertical, along the width when horizontal.
    ink_scale = max(1, min(FONT_INK_ROWS, thickness_limit) // FONT_INK_ROWS)
    ink_scale = max(1, min(ink_scale, int(size_px * 0.9) // FONT_INK_ROWS))
    length_scale = max(1, length_limit // run)
    return max(1, min(ink_scale, length_scale))


def render(layout: dict) -> png_rw.Image:
    canvas = Canvas(layout["sheetWidth"], layout["sheetHeight"])

    for ink in layout["ink"]:
        rgba = ROLE_COLORS.get(ink["role"], (200, 200, 200, 180))
        kind = ink["type"]
        if kind == "bar":
            canvas.fill_rect(ink["x"], ink["y"], ink["w"], ink["h"], rgba)
        elif kind == "triangle":
            canvas.fill_triangle([(p[0], p[1]) for p in ink["points"]], rgba)
        elif kind == "text":
            text = ink["text"]
            vertical = bool(ink.get("vertical"))
            scale = fit_scale(text, ink["sizePx"], ink["w"], ink["h"], vertical)
            if vertical:
                thickness = GLYPH_HEIGHT * scale
                length = text_width(text, scale)
                right_x = ink["x"] + ink["w"] - max(0, (ink["w"] - thickness) // 2)
                bottom_y = ink["y"] + ink["h"] - max(0, (ink["h"] - length) // 2)
                canvas.draw_text_vertical(right_x, bottom_y, text, rgba, scale)
            else:
                rendered_width = text_width(text, scale)
                x = ink["x"] + max(0, (ink["w"] - rendered_width) // 2)
                y = ink["y"] + max(0, (ink["h"] - GLYPH_HEIGHT * scale) // 2)
                canvas.draw_text(x, y, text, rgba, scale)
        else:
            raise SystemExit(f"unknown ink type '{kind}' in layout.json")

    return canvas.image


def main(argv: list[str] | None = None) -> int:
    here = os.path.dirname(os.path.abspath(__file__))
    parser = argparse.ArgumentParser(description="Render RigStudio's blank character sheet PNG.")
    parser.add_argument("--layout", default=os.path.join(here, "layout.json"))
    parser.add_argument(
        "--out",
        default=os.path.join(here, "..", "docs", "assets", "blank-character-sheet.png"),
    )
    args = parser.parse_args(argv)

    if not os.path.exists(args.layout):
        print(
            f"{args.layout} not found. Generate it first:\n"
            "  JAVA_HOME=... KOTLIN_HOME=... bash tools/dump_slots.sh",
            file=sys.stderr,
        )
        return 2

    with open(args.layout, "r", encoding="utf-8") as handle:
        layout = json.load(handle)

    image = render(layout)
    out_path = os.path.abspath(args.out)
    parent = os.path.dirname(out_path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    written = png_rw.write(image, out_path)

    counts: dict[str, int] = {}
    for ink in layout["ink"]:
        counts[ink["role"]] = counts.get(ink["role"], 0) + 1

    print(f"Rendered {image.width}x{image.height} RGBA PNG -> {out_path} ({written} bytes)")
    print(f"  ink primitives : {len(layout['ink'])}")
    for role in sorted(counts):
        print(f"    {role:<16} {counts[role]}")
    if layout.get("unplacedLabels"):
        print(f"  UNPLACED LABELS: {layout['unplacedLabels']}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
