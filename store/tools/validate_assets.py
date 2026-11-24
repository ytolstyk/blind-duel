#!/usr/bin/env python3
"""Check every Play Store listing asset against Google Play's published requirements.

    python3 store/tools/validate_assets.py        # exit code 1 if anything fails

The rules themselves live in store_common.py (shared with the screenshot composer):
  icon             512x512, 32-bit PNG, <= 1024 KB
  feature graphic  1024x500, PNG/JPEG, no alpha
  screenshots      PNG/JPEG, <= 8 MB, each side 320-3840 px, long side <= 2x short side,
                   2-8 per device type (phone / 7" tablet / 10" tablet)
  text             title <= 30, short description <= 80, full description <= 4000 characters
"""
import sys
from typing import List

import store_common as sc


def validate_icon() -> List[str]:
    files = sorted(sc.ICON_DIR.glob("*.png"))
    return [f"no icon found in {sc.ICON_DIR}"] if not files else [e for p in files for e in sc.icon_problems(p)]


def validate_feature_graphic() -> List[str]:
    files = sorted(p for ext in ("*.png", "*.jpg") for p in sc.FEATURE_DIR.glob(ext))
    return [f"no feature graphic found in {sc.FEATURE_DIR}"] if not files else \
        [e for p in files for e in sc.feature_problems(p)]


def validate_screenshots() -> List[str]:
    errors: List[str] = []
    for dev in sc.DEVICE_DIRS:
        files = sorted(p for ext in ("*.png", "*.jpg") for p in (sc.SCREENSHOT_DIR / dev).glob(ext))
        if not sc.SCREENSHOTS_MIN <= len(files) <= sc.SCREENSHOTS_MAX:
            errors.append(f"screenshots/{dev}: need {sc.SCREENSHOTS_MIN}-{sc.SCREENSHOTS_MAX} files, have {len(files)}")
        for p in files:
            errors += [f"screenshots/{dev}/{e}" for e in sc.screenshot_problems(p)]
    return errors


def validate_listing_text() -> List[str]:
    errors: List[str] = []
    locales = sorted(d for d in sc.LISTING_DIR.iterdir() if d.is_dir()) if sc.LISTING_DIR.exists() else []
    if not locales:
        return [f"no locale directories in {sc.LISTING_DIR}"]
    for locale in locales:
        for name, limit in sc.TEXT_LIMITS.items():
            p = locale / name
            if not p.exists():
                errors.append(f"listing/{locale.name}/{name}: missing")
                continue
            n = len(p.read_text(encoding="utf-8").strip())
            if not 0 < n <= limit:
                errors.append(f"listing/{locale.name}/{name}: {n} chars (limit {limit})")
    return errors


def main() -> int:
    errors = validate_icon() + validate_feature_graphic() + validate_screenshots() + validate_listing_text()
    if errors:
        print("FAIL")
        print("\n".join(f"  - {e}" for e in errors))
        return 1
    print("OK: all store assets meet Play requirements")
    return 0


if __name__ == "__main__":
    sys.exit(main())
