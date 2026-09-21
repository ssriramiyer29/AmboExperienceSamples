#!/usr/bin/env python3
"""Prints sample directories, optionally filtered by renderer.

Exists so CI does not have to infer a sample's renderer from its build files - the mistake that
would have let a Unity sample pass through unchecked and unbuilt.

    python3 tools/list_samples.py                      # every sample
    python3 tools/list_samples.py --renderer android-tv
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
NOT_DOMAINS = {"tools", "docs"}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--renderer")
    args = parser.parse_args()

    for declaration in sorted(ROOT.glob("*/*/sample.json")):
        if declaration.parts[-3] in NOT_DOMAINS:
            continue
        try:
            data = json.loads(declaration.read_text())
        except json.JSONDecodeError as exc:
            print(f"{declaration}: invalid JSON ({exc})", file=sys.stderr)
            return 1
        if args.renderer and data.get("renderer") != args.renderer:
            continue
        print(declaration.parent.relative_to(ROOT))
    return 0


if __name__ == "__main__":
    sys.exit(main())
