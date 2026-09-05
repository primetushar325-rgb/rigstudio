#!/usr/bin/env python3
"""Offline checker for RigStudio character sheets (pure Python, no dependencies).

    python3 tools/sheet_check.py sheet.png              # report what the app would find
    python3 tools/sheet_check.py blank.png --template   # prove no guide ink is inside a slot
    python3 tools/sheet_check.py sheet.png --json       # machine-readable report

It applies the same rules as the engine (`core/.../extract/SheetValidator.kt`): a sheet must be
2048x2048 with an alpha channel, required front-body slots must contain ink, and everything else
degrades to a warning. Empty-slot detection is plain alpha/bounding-box pixel analysis — no shape
recognition, no network, no accounts. It exists so a sheet can be checked before it ever reaches a
phone, and so CI can verify the bundled template.

Slot geometry comes from `tools/slots.json` (generated from the Kotlin template by
`tools/dump_slots.sh`), so this tool can never disagree with the app about where a part lives.
"""

from __future__ import annotations

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import png_rw  # noqa: E402

ALPHA_THRESHOLD = 8          # a pixel counts as ink above this alpha (same as the engine)
STRAY_SAMPLE_STEP = 8        # stray-ink scan samples every Nth pixel in each axis
MIN_INK_PIXELS = 24          # below this a slot counts as empty (dust, not artwork)
STRAY_INK_WARN_FRACTION = 0.02

REQUIRED_VIEW = "FRONT"


class SlotReport:
    def __init__(self, slot: dict):
        self.slot = slot
        self.ink = 0
        self.area = slot["w"] * slot["h"]
        self.min_x = None
        self.min_y = None
        self.max_x = None
        self.max_y = None

    @property
    def id(self) -> str:
        return self.slot["id"]

    @property
    def filled(self) -> bool:
        return self.ink >= MIN_INK_PIXELS

    @property
    def coverage(self) -> float:
        return self.ink / self.area if self.area else 0.0

    @property
    def bbox(self):
        if self.min_x is None:
            return None
        return (self.min_x, self.min_y, self.max_x + 1, self.max_y + 1)


def scan_slot(image: png_rw.Image, slot: dict) -> SlotReport:
    report = SlotReport(slot)
    x0, y0 = max(0, slot["x"]), max(0, slot["y"])
    x1 = min(image.width, slot["x"] + slot["w"])
    y1 = min(image.height, slot["y"] + slot["h"])
    stride = image.width * 4
    for y in range(y0, y1):
        row_start = y * stride + x0 * 4 + 3
        row_end = y * stride + x1 * 4
        row = image.pixels[row_start:row_end:4]
        for offset, alpha in enumerate(row):
            if alpha > ALPHA_THRESHOLD:
                x = x0 + offset
                report.ink += 1
                if report.min_x is None or x < report.min_x:
                    report.min_x = x
                if report.max_x is None or x > report.max_x:
                    report.max_x = x
                if report.min_y is None or y < report.min_y:
                    report.min_y = y
                if report.max_y is None or y > report.max_y:
                    report.max_y = y
    return report


def scan_stray_ink(image: png_rw.Image, slots: list[dict]) -> tuple[int, int]:
    """Sampled count of ink pixels that fall outside every slot rectangle."""
    stride = image.width * 4
    inside = 0
    sampled = 0
    for y in range(0, image.height, STRAY_SAMPLE_STEP):
        row = image.pixels[y * stride + 3:(y + 1) * stride:4 * STRAY_SAMPLE_STEP]
        for x_offset, alpha in enumerate(row):
            if alpha <= ALPHA_THRESHOLD:
                continue
            sampled += 1
            x = x_offset * STRAY_SAMPLE_STEP
            for slot in slots:
                if slot["x"] <= x < slot["x"] + slot["w"] and slot["y"] <= y < slot["y"] + slot["h"]:
                    inside += 1
                    break
    return sampled - inside, sampled


def scan_inside_slots(image: png_rw.Image, slots: list[dict]) -> list[tuple[str, int]]:
    """Ink pixel counts inside each slot rect - the template check (all must be zero)."""
    return [(report.id, report.ink) for report in (scan_slot(image, slot) for slot in slots) if report.ink]


def analyse(image: png_rw.Image, template: dict) -> dict:
    slots = template["slots"]
    reports = {slot["id"]: scan_slot(image, slot) for slot in slots}

    issues: list[dict[str, str]] = []
    if image.width != template["sheetWidth"] or image.height != template["sheetHeight"]:
        issues.append({
            "level": "ERROR",
            "message": f"Character Sheet must be {template['sheetWidth']}x{template['sheetHeight']} PNG. "
                       f"This image is {image.width}x{image.height}.",
        })
    if not image.has_alpha:
        issues.append({
            "level": "ERROR",
            "message": "The Character Sheet must be a transparent RGBA PNG. This image has no alpha channel.",
        })

    required_missing = [slot["id"] for slot in slots if slot["required"] and not reports[slot["id"]].filled]
    for slot_id in required_missing:
        slot = next(s for s in slots if s["id"] == slot_id)
        issues.append({
            "level": "ERROR",
            "message": f"Required part '{slot['label']}' ({slot_id}) has no artwork.",
        })

    def view_complete(view: str) -> bool:
        view_slots = [s for s in slots if s["view"] == view]
        return bool(view_slots) and all(reports[s["id"]].filled for s in view_slots)

    available_views = []
    front_ok = (
        image.width == template["sheetWidth"]
        and image.height == template["sheetHeight"]
        and image.has_alpha
        and not required_missing
    )
    if front_ok:
        available_views.append("FRONT")
    for view in ("SIDE_LEFT", "SIDE_RIGHT", "BACK"):
        if view_complete(view):
            available_views.append(view)

    for view in ("SIDE_LEFT", "SIDE_RIGHT", "BACK"):
        view_slots = [s for s in slots if s["view"] == view]
        drawn = [s for s in view_slots if reports[s["id"]].filled]
        if drawn and len(drawn) < len(view_slots):
            missing = ", ".join(s["id"] for s in view_slots if not reports[s["id"]].filled)
            issues.append({
                "level": "WARNING",
                "message": f"{view} is partly drawn ({len(drawn)}/{len(view_slots)}). "
                           f"The view stays disabled until these are filled: {missing}.",
            })
        elif not drawn:
            issues.append({
                "level": "INFO",
                "message": f"{view} artwork not found - that view and its animations stay disabled.",
            })

    can_mirror = view_complete("SIDE_LEFT") and not view_complete("SIDE_RIGHT")
    if can_mirror:
        issues.append({
            "level": "INFO",
            "message": "Left profile is complete and the right profile is empty: "
                       "RigStudio can mirror it to build the right-facing view.",
        })

    stray, sampled = scan_stray_ink(image, slots)
    stray_fraction = (stray / sampled) if sampled else 0.0
    if stray_fraction > STRAY_INK_WARN_FRACTION:
        issues.append({
            "level": "WARNING",
            "message": f"{stray_fraction:.1%} of sampled ink falls outside every slot and will be ignored "
                       f"({stray} of {sampled} sampled ink pixels).",
        })

    filled = [slot_id for slot_id, report in reports.items() if report.filled]
    expressions = [s["expression"] for s in slots if s.get("expression") and reports[s["id"]].filled]
    mouths = [s["mouthShape"] for s in slots if s.get("mouthShape") and reports[s["id"]].filled]

    return {
        "width": image.width,
        "height": image.height,
        "hasAlpha": image.has_alpha,
        "filledSlots": len(filled),
        "totalSlots": len(slots),
        "filledSlotIds": sorted(filled),
        "requiredMissing": sorted(required_missing),
        "availableViews": available_views,
        "canMirrorSideView": can_mirror,
        "isRiggable": not required_missing and front_ok,
        "expressions": expressions,
        "mouthShapes": mouths,
        "strayInkFraction": round(stray_fraction, 5),
        "issues": issues,
        "slots": {
            slot_id: {
                "filled": report.filled,
                "inkPixels": report.ink,
                "coverage": round(report.coverage, 4),
                "bbox": report.bbox,
            }
            for slot_id, report in reports.items()
        },
    }


def check_template(image: png_rw.Image, template: dict) -> dict:
    """The invariant: a freshly rendered blank sheet must have zero ink inside any slot."""
    offenders = scan_inside_slots(image, template["slots"])
    return {
        "template": True,
        "width": image.width,
        "height": image.height,
        "hasAlpha": image.has_alpha,
        "inkInsideSlots": [{"slotId": slot_id, "inkPixels": ink} for slot_id, ink in offenders],
        "clean": not offenders,
    }


def print_report(report: dict, path: str) -> int:
    if report.get("template"):
        print(f"{path}: {report['width']}x{report['height']} alpha={report['hasAlpha']}")
        if report["clean"]:
            print("  PASS - no guide ink inside any slot rectangle")
            return 0
        print("  FAIL - guide ink inside slot rectangles (would be extracted as artwork):")
        for entry in report["inkInsideSlots"][:20]:
            print(f"    {entry['slotId']}: {entry['inkPixels']} px")
        return 1

    print(f"{path}: {report['width']}x{report['height']} alpha={report['hasAlpha']}")
    print(f"  slots drawn : {report['filledSlots']} / {report['totalSlots']}")
    print(f"  views       : {', '.join(report['availableViews']) or 'none'}")
    print(f"  riggable    : {report['isRiggable']}")
    print(f"  mirror side : {report['canMirrorSideView']}")
    print(f"  expressions : {len(report['expressions'])}  mouths: {len(report['mouthShapes'])}")
    print(f"  stray ink   : {report['strayInkFraction']:.2%} of sampled ink")
    if report["requiredMissing"]:
        print(f"  missing required: {', '.join(report['requiredMissing'])}")
    for issue in report["issues"]:
        print(f"  [{issue['level']}] {issue['message']}")
    return 0 if report["isRiggable"] else 1


def main(argv: list[str] | None = None) -> int:
    here = os.path.dirname(os.path.abspath(__file__))
    parser = argparse.ArgumentParser(description="Check a RigStudio character sheet PNG.")
    parser.add_argument("sheet", help="path to a 2048x2048 character sheet PNG")
    parser.add_argument("--slots", default=os.path.join(here, "slots.json"))
    parser.add_argument("--template", action="store_true",
                        help="check the guide-ink invariant instead of riggability")
    parser.add_argument("--json", action="store_true", help="print a machine-readable report")
    args = parser.parse_args(argv)

    if not os.path.exists(args.slots):
        print(
            f"{args.slots} not found. Generate it first:\n"
            "  JAVA_HOME=... KOTLIN_HOME=... bash tools/dump_slots.sh",
            file=sys.stderr,
        )
        return 2
    if not os.path.exists(args.sheet):
        print(f"{args.sheet}: no such file", file=sys.stderr)
        return 2

    with open(args.slots, "r", encoding="utf-8") as handle:
        template = json.load(handle)

    try:
        image = png_rw.read(args.sheet)
    except png_rw.PngError as error:
        print(f"{args.sheet}: {error}", file=sys.stderr)
        return 2

    report = check_template(image, template) if args.template else analyse(image, template)
    if args.json:
        print(json.dumps(report, indent=2))
    return print_report(report, args.sheet) if not args.json else (
        0 if (report.get("clean") or report.get("isRiggable")) else 1
    )


if __name__ == "__main__":
    raise SystemExit(main())
