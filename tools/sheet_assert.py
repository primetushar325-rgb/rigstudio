#!/usr/bin/env python3
"""Assert expectations on a `sheet_check.py --json` report (used by tools/verify_all.sh).

    python3 tools/sheet_assert.py report.json front|front-side|full|sample

Keeps the expected pipeline behaviour in one readable place: which views a sheet must yield,
when the mirror offer must appear, and what the bundled sample character has to contain.
"""

from __future__ import annotations

import json
import sys


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__ or "", file=sys.stderr)
        return 2
    report = json.load(open(argv[0], "r", encoding="utf-8"))
    variant = argv[1]

    problems: list[str] = []
    views = report["availableViews"]

    if not report["isRiggable"]:
        problems.append("expected the sheet to be riggable")
    if variant == "front" and views != ["FRONT"]:
        problems.append(f"front-only sheet should offer only FRONT, got {views}")
    if variant == "front-side":
        if views != ["FRONT", "SIDE_LEFT"]:
            problems.append(f"expected FRONT+SIDE_LEFT, got {views}")
        if not report["canMirrorSideView"]:
            problems.append("expected the mirror-side-view offer")
    if variant == "full":
        if views != ["FRONT", "SIDE_LEFT", "SIDE_RIGHT", "BACK"]:
            problems.append(f"expected all four views, got {views}")
        if len(report["mouthShapes"]) < 5 or len(report["expressions"]) < 4:
            problems.append("expected the full face set to be detected")
    if variant == "sample":
        if views != ["FRONT", "SIDE_LEFT", "SIDE_RIGHT", "BACK"]:
            problems.append(f"sample character must have all views, got {views}")
        if len(report["expressions"]) != 5 or len(report["mouthShapes"]) != 11:
            problems.append("sample character must carry 5 expressions and 11 mouths")
        if report["filledSlots"] != report["totalSlots"]:
            problems.append("sample character must fill every slot")
    for issue in report.get("issues", []):
        if issue["level"] == "ERROR":
            problems.append(f"unexpected ERROR issue: {issue['message']}")

    if problems:
        print(f"  sheet assertions ({variant}) failed:")
        for problem in problems:
            print("   -", problem)
        return 1

    print(f"  sheet assertions ({variant}): riggable, views={','.join(views)}, "
          f"mirror={report['canMirrorSideView']}, slots={report['filledSlots']}/{report['totalSlots']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
