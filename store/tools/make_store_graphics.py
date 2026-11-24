#!/usr/bin/env python3
"""Generate the Play Store app icon (512x512) and feature graphic (1024x500).

Pure Pillow and deterministic (seeded) — re-running produces identical files, so the output is
diff-friendly in git. Colours are read from ui/theme/Color.kt and the icon geometry from
res/drawable/ic_launcher_foreground.xml (see store_common.py), so an app-side change flows
through on the next run.

    python3 store/tools/make_store_graphics.py

Play requirements (checked by validate_assets.py via store_common): icon 512x512 32-bit PNG
<= 1024 KB, full-bleed square; feature graphic 1024x500 PNG/JPEG with no alpha channel.
"""
import math

from PIL import Image, ImageDraw

import store_common as sc
from store_common import BG, EMBER, PARTICLE, TEXT, VIOLET, font

SUPERSAMPLE_ICON = 4
SUPERSAMPLE_FEATURE = 2

# Feature-graphic layout, in final 1024x500 pixels.
PLAYER_CENTER = (735, 330)
PLAYER_RADIUS = 62
TARGET_CENTER = (925, 160)
TARGET_RETICLE_RADIUS = 30
TARGET_GHOST_RADIUS = 46
TEXT_LEFT = 64
TITLE_Y = 104
TAGLINE_Y = 262
BODY_Y = (330, 368)
FEATURE_GLOW_BOX = (0.62, 0.12, 1.0, 0.95)
FEATURE_GLOW_RGB = (34, 28, 84)
FEATURE_SEED = 7


def make_icon():
    spec = sc.load_icon_spec()
    ss = SUPERSAMPLE_ICON
    size = sc.ICON_SIZE[0] * ss
    lo = (sc.ICON_VIEWPORT - sc.ICON_VISIBLE) / 2  # crop to the adaptive-icon safe area so the
    k = size / sc.ICON_VISIBLE                      # Play icon matches what users see on-device

    def px(p):
        return ((p[0] - lo) * k, (p[1] - lo) * k)

    im = Image.new("RGBA", (size, size), BG + (255,))
    d = ImageDraw.Draw(im)
    d.line([px(spec.aim_from), px(spec.aim_to)], fill=spec.aim_color, width=int(spec.aim_width * k))
    d.polygon([px(p) for p in spec.blob], fill=spec.blob_color)
    cx, cy = px(spec.dot_center)
    r = spec.dot_radius * k
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=spec.dot_color)
    im = im.resize(sc.ICON_SIZE, Image.LANCZOS)
    out = sc.ICON_DIR / "play_store_icon_512.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    im.save(out, optimize=True)
    return out


def _dashed_circle(d, cx, cy, r, color, width, dashes=16, fill_ratio=0.55):
    step = 360 / dashes
    for i in range(dashes):
        d.arc([cx - r, cy - r, cx + r, cy + r], i * step, i * step + step * fill_ratio, fill=color, width=width)


def make_feature_graphic():
    ss = SUPERSAMPLE_FEATURE
    w, h = sc.FEATURE_SIZE[0] * ss, sc.FEATURE_SIZE[1] * ss
    im = sc.backdrop(w, h, seed=FEATURE_SEED, glow_box=FEATURE_GLOW_BOX, glow_rgb=FEATURE_GLOW_RGB,
                     glow_blur=90 * ss, particle_density=90 / (w * h), radius_scale=ss)
    d = ImageDraw.Draw(im, "RGBA")

    # Scene: you (ember blob) aim at an opponent the screen never shows.
    bx, by, br = PLAYER_CENTER[0] * ss, PLAYER_CENTER[1] * ss, PLAYER_RADIUS * ss
    tx, ty = TARGET_CENTER[0] * ss, TARGET_CENTER[1] * ss
    _dashed_circle(d, tx, ty, TARGET_GHOST_RADIUS * ss, EMBER + (120,), 4 * ss)  # the unseen opponent
    ang = math.atan2(ty - by, tx - bx)
    d.line([(bx + math.cos(ang) * br, by + math.sin(ang) * br),
            (tx - math.cos(ang) * 58 * ss, ty - math.sin(ang) * 58 * ss)], fill=VIOLET, width=7 * ss)
    rr = TARGET_RETICLE_RADIUS * ss
    d.ellipse([tx - rr, ty - rr, tx + rr, ty + rr], outline=VIOLET, width=5 * ss)
    for a in (0, 90, 180, 270):
        c, s = math.cos(math.radians(a)), math.sin(math.radians(a))
        d.line([(tx + c * (rr - 4 * ss), ty + s * (rr - 4 * ss)),
                (tx + c * (rr + 14 * ss), ty + s * (rr + 14 * ss))], fill=VIOLET, width=5 * ss)
    echo = br + 22 * ss
    d.ellipse([bx - echo, by - echo, bx + echo, by + echo], outline=VIOLET + (70,), width=3 * ss)

    # The blob itself: same silhouette as the launcher icon, scaled to PLAYER_RADIUS.
    spec = sc.load_icon_spec()
    xs, ys = [p[0] for p in spec.blob], [p[1] for p in spec.blob]
    cx, cy = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
    k = (2 * br) / (max(xs) - min(xs))
    d.polygon([(bx + (x - cx) * k, by + (y - cy) * k) for x, y in spec.blob], fill=spec.blob_color)

    # Text block.
    d.text((TEXT_LEFT * ss, TITLE_Y * ss), "BlindDuel", font=font("Black", 128 * ss), fill=TEXT)
    d.text(((TEXT_LEFT + 4) * ss, TAGLINE_Y * ss), "Aim blind. Trust your gut.", font=font("Medium", 40 * ss), fill=VIOLET)
    for y, line in zip(BODY_Y, ("Face-to-face duels between two phones.",
                                "No server. No account. Just you and a rival.")):
        d.text(((TEXT_LEFT + 4) * ss, y * ss), line, font=font("Regular", 28 * ss), fill=PARTICLE + (220,))

    im = im.resize(sc.FEATURE_SIZE, Image.LANCZOS).convert("RGB")  # RGB => no alpha channel
    out = sc.FEATURE_DIR / "feature_graphic_1024x500.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    im.save(out, optimize=True)
    return out


if __name__ == "__main__":
    for p in (make_icon(), make_feature_graphic()):
        with Image.open(p) as im:
            print(p.relative_to(sc.REPO), im.size, im.mode, f"{p.stat().st_size // 1024} KB")
