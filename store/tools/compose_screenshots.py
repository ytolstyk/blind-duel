#!/usr/bin/env python3
"""Turn curated raw emulator frames (store/raw/<device>/*.png) into Play-ready listing screenshots.

    python3 store/tools/compose_screenshots.py            # all devices
    python3 store/tools/compose_screenshots.py phone      # one device

Before framing each raw frame with a caption on the brand background it is cleaned up:
  * the (dark-on-dark, unreadable) status bar is painted out,
  * debug-only UI that doesn't exist in a release build is covered: the "Leave Practice" link on the
    duel HUD and the whole "Solo Test Options" block on the Create screen.
A cover step that finds nothing to cover RAISES rather than passing silently, because the failure mode
is debug UI leaking into the public listing.

Output goes through store_common.screenshot_problems (the same rules validate_assets.py enforces).
Canvas sizes: phone 1080x1920 (9:16), 7" tablet 1200x1920 (5:8), 10" tablet 1600x2560 (5:8).
"""
import sys
import zlib
from pathlib import Path
from typing import Callable, Dict, List, NamedTuple, Optional, Sequence, Tuple

from PIL import Image, ImageDraw, ImageFilter

import store_common as sc
from store_common import BG, EMBER, TEXT, VIOLET, font


class Device(NamedTuple):
    canvas: Tuple[int, int]
    raw_status_bar_px: int      # height of the status bar baked into the raw capture


class Shot(NamedTuple):
    slug: str
    raw: str                    # raw frame name in store/raw/<device>/
    caption: str                # *accent* words render in ember; "\n" = line break


DEVICES: Dict[str, Device] = {
    "phone": Device((1080, 1920), 110),
    "tablet-7": Device((1200, 1920), 64),
    "tablet-10": Device((1600, 2560), 50),
}

# A device skips any Shot whose raw frame is missing (only the 10" has "danger", only phone/7" have
# "win"); the skips are printed. Files are numbered in order of what remains. Play allows max 8.
SHOTS: List[Shot] = [
    Shot("two_phones", "mainmenu", "Two phones.\n*One duel.*"),
    Shot("qr_pairing", "create", "Pair in seconds\nwith a *QR code*"),
    Shot("calibrate", "countdown", "Face your rival\nand *calibrate*"),
    Shot("aim_fire", "aim", "Drag to aim.\n*Release to fire.*"),
    Shot("crates", "crate", "Shoot crates for\n*new weapons*"),
    Shot("weapons", "cone", "Five weapons.\n*Five playstyles.*"),
    Shot("dodge", "danger", "Feel it coming.\n*Dodge!*"),
    Shot("victory", "win", "Outsmart your\n*rival*"),
    Shot("settings", "settings", "Tune haptics,\ntrails & *themes*"),
]

# ---- debug-UI cleanup tuning ------------------------------------------------------------------
BG_MATCH_TOLERANCE = 12          # max per-channel diff for the status strip to count as flat BG
ROW_NONBG_TOLERANCE = 20         # per-channel diff for a pixel to count as "not background"
DIVIDER_SEARCH_Y = (0.5, 0.9)    # where to look for the line above "Solo Test Options" (fraction of h)
DIVIDER_SEARCH_X = (0.06, 0.94)
DIVIDER_MIN_ROW_FRACTION = 0.85  # a divider row is non-BG across at least this share of sampled columns
DIVIDER_MARGIN_PX = 2
SAMPLE_STRIDE = 4
VIOLET_TOLERANCE = 40            # per-channel diff for a pixel to count as the accent-violet link text
LEAVE_LINK_REGION = (0.3, 0.03, 1.0, 0.22)   # x0, y0, x1, y1 as fractions of the raw frame
BAND_GAP_PX = 6                  # rows further apart than this start a new band of violet pixels
COVER_PAD_PX = 14
OUTLIER_TRIM_FRACTION = 0.01     # drop the extreme 1% of x positions (stray violet star dots)

# ---- layout (fractions of the canvas) -----------------------------------------------------------
CAPTION_TOP = 0.042
CAPTION_LINES = 2
CAPTION_FONT_FRACTION = 0.068
CAPTION_MAX_WIDTH = 0.9
CAPTION_LINE_SPACING = 1.18
MIN_CAPTION_PX = 24
DEVICE_TOP_GAP = 0.01
DEVICE_BOTTOM_MARGIN = 0.045
BEZEL_FRACTION = 0.007
BEZEL_COLOR = (52, 56, 112)
CORNER_FRACTION = 0.06
SHADOW_BLUR = 0.012
SHADOW_OFFSET_PX = 14
SHADOW_ALPHA = 150
PNG_COMPRESS_LEVEL = 6           # optimize=True was ~65% of run time for a 5-8% size win


def channel_distance(a: Sequence[int], b: Sequence[int]) -> int:
    return max(abs(p - q) for p, q in zip(a, b))


# ---------------------------------------------------------------- raw-frame cover steps
def cover_status_bar(im: Image.Image, status_px: int) -> None:
    """Flat BG fill; if the screen below isn't flat (e.g. the red incoming-shot vignette) stretch the
    row just beneath instead so there's no visible band."""
    w = im.width
    below = im.crop((0, status_px + 2, w, status_px + 3))
    if channel_distance(below.resize((1, 1), Image.BOX).getpixel((0, 0)), BG) > BG_MATCH_TOLERANCE:
        im.paste(below.resize((w, status_px + 1)), (0, 0))
    else:
        ImageDraw.Draw(im).rectangle([0, 0, w, status_px], fill=BG)


def cover_solo_test_block(im: Image.Image) -> None:
    """Everything from the divider above "Solo Test Options" down is debug-only."""
    w, h = im.size
    px = im.load()
    columns = range(int(w * DIVIDER_SEARCH_X[0]), int(w * DIVIDER_SEARCH_X[1]), SAMPLE_STRIDE)
    for y in range(int(h * DIVIDER_SEARCH_Y[0]), int(h * DIVIDER_SEARCH_Y[1])):
        non_bg = sum(1 for x in columns if channel_distance(px[x, y], BG) > ROW_NONBG_TOLERANCE)
        if non_bg > DIVIDER_MIN_ROW_FRACTION * len(columns):
            ImageDraw.Draw(im).rectangle([0, y - DIVIDER_MARGIN_PX, w, h], fill=BG)
            return
    raise RuntimeError("Create screen: divider above 'Solo Test Options' not found; "
                       "debug UI would ship. Adjust the DIVIDER_* constants.")


def densest_band(points: List[Tuple[int, int]], gap: int) -> List[Tuple[int, int]]:
    """Group points (any order) into runs of rows separated by <= gap px and return the biggest run."""
    bands: List[List[Tuple[int, int]]] = []
    cur: List[Tuple[int, int]] = []
    for p in sorted(points, key=lambda q: q[1]):
        if cur and p[1] - cur[-1][1] > gap:
            bands.append(cur)
            cur = []
        cur.append(p)
    if cur:
        bands.append(cur)
    return max(bands, key=len) if bands else []


def cover_leave_practice_link(im: Image.Image) -> None:
    """"Leave Practice" is the densest band of accent-violet pixels in the HUD's upper right. Stray
    violet stars/projectile dots match the colour too, hence clustering instead of a bounding box."""
    w, h = im.size
    px = im.load()
    x0, y0, x1, y1 = (int(w * LEAVE_LINK_REGION[0]), int(h * LEAVE_LINK_REGION[1]),
                      int(w * LEAVE_LINK_REGION[2]), int(h * LEAVE_LINK_REGION[3]))
    hits = [(x, y) for y in range(y0, y1) for x in range(x0, x1)
            if channel_distance(px[x, y], VIOLET) < VIOLET_TOLERANCE]
    band = densest_band(hits, BAND_GAP_PX)
    if not band:
        raise RuntimeError("Duel frame: 'Leave Practice' link not found; debug UI would ship. "
                           "Adjust LEAVE_LINK_REGION / VIOLET_TOLERANCE.")
    xs = sorted(p[0] for p in band)
    trim = int(len(xs) * OUTLIER_TRIM_FRACTION)
    box = [xs[trim] - COVER_PAD_PX, min(p[1] for p in band) - COVER_PAD_PX,
           xs[-1 - trim] + COVER_PAD_PX, max(p[1] for p in band) + COVER_PAD_PX]
    # Fill from the pixels just left of the box so gradients don't show a dark patch.
    ImageDraw.Draw(im).rectangle(box, fill=px[max(box[0] - 8, 0), (box[1] + box[3]) // 2])


# Raw-frame name -> debug-UI cover. A raw frame not listed here has none to cover.
DEBUG_UI_COVERS: Dict[str, Callable[[Image.Image], None]] = {
    "create": cover_solo_test_block,
    "aim": cover_leave_practice_link,
    "crate": cover_leave_practice_link,
    "cone": cover_leave_practice_link,
    "danger": cover_leave_practice_link,
}


def clean_raw_frame(raw_name: str, device: str) -> Image.Image:
    with Image.open(sc.ROOT / "raw" / device / f"{raw_name}.png") as src:
        im = src.convert("RGB")
    cover_status_bar(im, DEVICES[device].raw_status_bar_px)
    cover = DEBUG_UI_COVERS.get(raw_name)
    if cover:
        cover(im)
    return im


# ---------------------------------------------------------------- composition
def parse_caption_line(line: str) -> List[Tuple[str, bool]]:
    """'a *b* c' -> [('a ', False), ('b', True), (' c', False)]"""
    out, accent = [], False
    for part in line.split("*"):
        if part:
            out.append((part, accent))
        accent = not accent
    return out


def draw_caption(im: Image.Image, caption: str, canvas_w: int, top: int) -> int:
    """Draw the caption; return the y of its bottom edge (always CAPTION_LINES tall so layouts align)."""
    d = ImageDraw.Draw(im)
    lines = caption.split("\n")

    def line_width(line: str, f) -> float:
        return sum(d.textlength(seg, font=f) for seg, _ in parse_caption_line(line))

    size = int(canvas_w * CAPTION_FONT_FRACTION)
    f = font("Bold", size)
    while max(line_width(line, f) for line in lines) > canvas_w * CAPTION_MAX_WIDTH:
        size -= 2
        if size < MIN_CAPTION_PX:
            raise ValueError(f"caption too long to fit at >= {MIN_CAPTION_PX}px: {caption!r}")
        f = font("Bold", size)
    line_h = int(size * CAPTION_LINE_SPACING)
    y = top + (CAPTION_LINES - len(lines)) * line_h // 2
    for line in lines:
        x = (canvas_w - line_width(line, f)) / 2
        for seg, accent in parse_caption_line(line):
            d.text((x, y), seg, font=f, fill=EMBER if accent else TEXT)
            x += d.textlength(seg, font=f)
        y += line_h
    return top + CAPTION_LINES * line_h


def draw_device(im: Image.Image, screen: Image.Image, x: int, y: int) -> None:
    """Shadow, bezel and rounded-corner screen."""
    w, _ = im.size
    sw, sh = screen.size
    radius = int(sw * CORNER_FRACTION)
    bezel = max(6, int(w * BEZEL_FRACTION))
    shadow = Image.new("RGBA", im.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle(
        [x - bezel, y - bezel + SHADOW_OFFSET_PX, x + sw + bezel, y + sh + bezel + SHADOW_OFFSET_PX],
        radius + bezel, fill=(0, 0, 0, SHADOW_ALPHA))
    blurred = shadow.filter(ImageFilter.GaussianBlur(w * SHADOW_BLUR))
    im.paste(blurred, (0, 0), blurred)
    ImageDraw.Draw(im).rounded_rectangle([x - bezel, y - bezel, x + sw + bezel, y + sh + bezel],
                                         radius + bezel, fill=BEZEL_COLOR)
    mask = Image.new("L", screen.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, sw - 1, sh - 1], radius, fill=255)
    im.paste(screen, (x, y), mask)


def compose(device: str, out_name: str, shot: Shot) -> Path:
    (w, h) = DEVICES[device].canvas
    raw = clean_raw_frame(shot.raw, device)
    im = sc.backdrop(w, h, seed=zlib.crc32(out_name.encode()) % 1000, glow_box=(0.1, 0.28, 0.9, 1.0),
                     glow_rgb=(30, 25, 78), glow_blur=w * 0.12, particle_density=1 / 14000,
                     radius_scale=w / 1080, gradient_strength=0.9)
    cap_bottom = draw_caption(im, shot.caption, w, top=int(h * CAPTION_TOP))

    dev_h = h - cap_bottom - int(h * DEVICE_BOTTOM_MARGIN)
    dev_w = round(dev_h * raw.width / raw.height)
    draw_device(im, raw.resize((dev_w, dev_h), Image.LANCZOS),
                x=(w - dev_w) // 2, y=cap_bottom + int(h * DEVICE_TOP_GAP))

    out = sc.SCREENSHOT_DIR / device / f"{out_name}.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    im.save(out, compress_level=PNG_COMPRESS_LEVEL)
    problems = sc.screenshot_problems(out)
    if problems:
        raise ValueError("; ".join(problems))
    return out


def main(argv: Optional[Sequence[str]] = None) -> None:
    wanted = list(argv if argv is not None else sys.argv[1:]) or list(DEVICES)
    unknown = [d for d in wanted if d not in DEVICES]
    if unknown:
        raise SystemExit(f"unknown device(s) {unknown}; choose from {list(DEVICES)}")
    for dev in wanted:
        present = [s for s in SHOTS if (sc.ROOT / "raw" / dev / f"{s.raw}.png").exists()]
        for s in SHOTS:
            if s not in present:
                print(f"  [{dev}] skipping '{s.slug}': no raw frame '{s.raw}.png'")
        if len(present) > sc.SCREENSHOTS_MAX:
            raise SystemExit(f"{dev}: {len(present)} shots, Play allows at most {sc.SCREENSHOTS_MAX}")
        for old in (sc.SCREENSHOT_DIR / dev).glob("*.png"):
            old.unlink()  # drop stale numbering from earlier runs
        for i, shot in enumerate(present, 1):
            p = compose(dev, f"{i:02d}_{shot.slug}", shot)
            with Image.open(p) as im:
                print(f"{p.relative_to(sc.REPO)}  {im.width}x{im.height}  {p.stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
