"""Shared building blocks for the store tooling (imported by the other scripts, not run directly).

  * paths
  * brand tokens, read from the app's own sources so the store art can't drift from the app:
      - palette      <- ui/theme/Color.kt
      - icon geometry <- res/drawable/ic_launcher_foreground.xml
  * Google Play asset rules (one definition, used by the composer and the validator)
  * the shared starfield backdrop used by both the feature graphic and the screenshots
"""
import functools
import random
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import List, NamedTuple, Tuple

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

# ---------------------------------------------------------------- paths
TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent          # store/
REPO = ROOT.parent
FONTS = TOOLS / "fonts"
COLOR_KT = REPO / "app/src/main/java/com/tolstykh/blindduel/ui/theme/Color.kt"
LAUNCHER_FOREGROUND_XML = REPO / "app/src/main/res/drawable/ic_launcher_foreground.xml"

DEVICE_DIRS = ("phone", "tablet-7", "tablet-10")
LISTING_DIR = ROOT / "listing"
ICON_DIR = ROOT / "graphics" / "icon"
FEATURE_DIR = ROOT / "graphics" / "feature"
SCREENSHOT_DIR = ROOT / "screenshots"

RGB = Tuple[int, int, int]


# ---------------------------------------------------------------- palette (from Color.kt)
def _rgb(hex6: str) -> RGB:
    return (int(hex6[0:2], 16), int(hex6[2:4], 16), int(hex6[4:6], 16))


def _load_palette():
    src = COLOR_KT.read_text(encoding="utf-8")

    def named(name: str) -> RGB:
        m = re.search(rf"val {name}\s*=\s*Color\(0xFF([0-9A-Fa-f]{{6}})\)", src)
        if not m:
            raise RuntimeError(f"{name} not found in {COLOR_KT}; update store_common._load_palette")
        return _rgb(m.group(1))

    mint = re.search(r"TrailTheme\.MINT\s*->\s*Color\(0xFF([0-9A-Fa-f]{6})\)", src)
    if not mint:
        raise RuntimeError(f"TrailTheme.MINT colour not found in {COLOR_KT}")
    return (named("DuelBackgroundDark"), named("DuelSurfaceDark"), named("DuelAccentViolet"),
            named("DuelAccentEmber"), named("DuelParticle"), named("DuelOnBackgroundDark"),
            _rgb(mint.group(1)))


BG, SURFACE, VIOLET, EMBER, PARTICLE, TEXT, MINT = _load_palette()


@functools.lru_cache(maxsize=None)
def font(name: str, size: int) -> ImageFont.FreeTypeFont:
    """Roboto (the app's own family; Apache 2.0, vendored in tools/fonts)."""
    return ImageFont.truetype(str(FONTS / f"Roboto-{name}.ttf"), size)


# ---------------------------------------------------------------- launcher icon geometry
ICON_VIEWPORT = 108       # launcher vector viewport (dp)
ICON_VISIBLE = 72         # adaptive icons show only the central 72dp of the 108dp canvas
_ANDROID = "{http://schemas.android.com/apk/res/android}"
_PATH_TOKEN = re.compile(r"[MmLlCcZz]|-?\d*\.?\d+")


class IconSpec(NamedTuple):
    aim_from: Tuple[float, float]
    aim_to: Tuple[float, float]
    aim_width: float
    aim_color: RGB
    blob: List[Tuple[float, float]]
    blob_color: RGB
    dot_center: Tuple[float, float]
    dot_radius: float
    dot_color: RGB


def _bezier(p0, p1, p2, p3, n=40):
    out = []
    for i in range(n + 1):
        t = i / n
        u = 1 - t
        out.append((u**3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t**3 * p3[0],
                    u**3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t**3 * p3[1]))
    return out


def _flatten_path(d: str) -> List[Tuple[float, float]]:
    """Flatten the subset of Android pathData the launcher blob uses: M, L, relative c, z."""
    tokens = _PATH_TOKEN.findall(d)
    pts: List[Tuple[float, float]] = []
    cur = (0.0, 0.0)
    i, cmd = 0, ""
    while i < len(tokens):
        if tokens[i].isalpha():
            cmd = tokens[i]
            i += 1
            if cmd in "zZ":
                continue
        if cmd in "ML":
            cur = (float(tokens[i]), float(tokens[i + 1]))
            pts.append(cur)
            i += 2
        elif cmd == "c":
            v = [float(t) for t in tokens[i:i + 6]]
            p1, p2, p3 = (cur[0] + v[0], cur[1] + v[1]), (cur[0] + v[2], cur[1] + v[3]), (cur[0] + v[4], cur[1] + v[5])
            pts += _bezier(cur, p1, p2, p3)
            cur = p3
            i += 6
        else:
            raise RuntimeError(f"unsupported path command '{cmd}' in {LAUNCHER_FOREGROUND_XML.name}; "
                               "extend store_common._flatten_path")
    return pts


def load_icon_spec() -> IconSpec:
    paths = ET.parse(LAUNCHER_FOREGROUND_XML).getroot().findall("path")
    by_kind = {}
    for p in paths:
        a = p.attrib
        kind = "line" if _ANDROID + "strokeColor" in a else None
        by_kind.setdefault(kind, []).append(a)
    line = by_kind["line"][0]
    filled = by_kind[None]  # blob, then dot
    if len(filled) != 2:
        raise RuntimeError("ic_launcher_foreground.xml layout changed (expected line, blob, dot)")
    (x0, y0, x1, y1) = (float(n) for n in re.findall(r"-?\d*\.?\d+", line[_ANDROID + "pathData"])[:4])
    dot = filled[1][_ANDROID + "pathData"]
    cx, cy = (float(n) for n in re.findall(r"-?\d*\.?\d+", dot)[:2])
    radius = float(re.search(r"a\s*(-?\d*\.?\d+)", dot).group(1))
    return IconSpec(
        aim_from=(x0, y0), aim_to=(x1, y1), aim_width=float(line[_ANDROID + "strokeWidth"]),
        aim_color=_rgb(line[_ANDROID + "strokeColor"].lstrip("#")),
        blob=_flatten_path(filled[0][_ANDROID + "pathData"]),
        blob_color=_rgb(filled[0][_ANDROID + "fillColor"].lstrip("#")),
        dot_center=(cx, cy), dot_radius=radius,
        dot_color=_rgb(filled[1][_ANDROID + "fillColor"].lstrip("#")))


# ---------------------------------------------------------------- Google Play rules
ICON_SIZE = (512, 512)
ICON_MAX_BYTES = 1024 * 1024
FEATURE_SIZE = (1024, 500)
SCREENSHOT_MIN_SIDE = 320
SCREENSHOT_MAX_SIDE = 3840
SCREENSHOT_MAX_ASPECT = 2.0
SCREENSHOT_MAX_BYTES = 8 * 1024 * 1024
SCREENSHOTS_MIN = 2
SCREENSHOTS_MAX = 8
TEXT_LIMITS = {"title.txt": 30, "short_description.txt": 80, "full_description.txt": 4000}


def _open(path: Path):
    try:
        im = Image.open(path)
        im.load()
        return im, None
    except (OSError, SyntaxError) as e:
        return None, f"{path.name}: unreadable image ({e})"


def icon_problems(path: Path) -> List[str]:
    im, err = _open(path)
    if err:
        return [err]
    out = []
    if im.format != "PNG" or im.mode != "RGBA":
        out.append(f"{path.name}: must be a 32-bit PNG (RGBA), is {im.format}/{im.mode}")
    if im.size != ICON_SIZE:
        out.append(f"{path.name}: must be {ICON_SIZE}, is {im.size}")
    if path.stat().st_size > ICON_MAX_BYTES:
        out.append(f"{path.name}: over {ICON_MAX_BYTES // 1024} KB")
    return out


def feature_problems(path: Path) -> List[str]:
    im, err = _open(path)
    if err:
        return [err]
    out = []
    if im.format not in ("PNG", "JPEG"):
        out.append(f"{path.name}: format {im.format}")
    if im.size != FEATURE_SIZE:
        out.append(f"{path.name}: must be {FEATURE_SIZE}, is {im.size}")
    if "A" in im.getbands():
        out.append(f"{path.name}: must not have an alpha channel")
    return out


def screenshot_problems(path: Path) -> List[str]:
    im, err = _open(path)
    if err:
        return [err]
    w, h = im.size
    out = []
    if im.format not in ("PNG", "JPEG"):
        out.append(f"{path.name}: format {im.format}")
    if min(w, h) < SCREENSHOT_MIN_SIDE or max(w, h) > SCREENSHOT_MAX_SIDE:
        out.append(f"{path.name}: side out of {SCREENSHOT_MIN_SIDE}-{SCREENSHOT_MAX_SIDE}px range {im.size}")
    if max(w, h) > SCREENSHOT_MAX_ASPECT * min(w, h):
        out.append(f"{path.name}: aspect ratio over 2:1 {im.size}")
    if path.stat().st_size > SCREENSHOT_MAX_BYTES:
        out.append(f"{path.name}: over 8 MB")
    if "A" in im.getbands():
        out.append(f"{path.name}: has an alpha channel (flatten to RGB)")
    return out


# ---------------------------------------------------------------- shared backdrop
def backdrop(w: int, h: int, seed: int, glow_box: Tuple[float, float, float, float], glow_rgb: RGB,
             glow_blur: float, particle_density: float, radius_scale: float,
             gradient_strength: float = 1.0) -> Image.Image:
    """Brand background: vertical BG->SURFACE gradient, a soft glow, and a seeded starfield."""
    mask = Image.linear_gradient("L").resize((w, h))
    if gradient_strength != 1.0:
        mask = mask.point(lambda v: int(v * gradient_strength))
    im = Image.composite(Image.new("RGB", (w, h), SURFACE), Image.new("RGB", (w, h), BG), mask)

    glow = Image.new("RGB", (w, h), (0, 0, 0))
    gx0, gy0, gx1, gy1 = glow_box
    ImageDraw.Draw(glow).ellipse([w * gx0, h * gy0, w * gx1, h * gy1], fill=glow_rgb)
    im = ImageChops.add(im, glow.filter(ImageFilter.GaussianBlur(glow_blur)))

    d = ImageDraw.Draw(im, "RGBA")
    rnd = random.Random(seed)
    for _ in range(int(w * h * particle_density)):
        x, y = rnd.uniform(0, w), rnd.uniform(0, h)
        r = rnd.choice([1.5, 2, 2.5, 3]) * radius_scale
        d.ellipse([x - r, y - r, x + r, y + r],
                  fill=rnd.choice([MINT, PARTICLE, PARTICLE]) + (rnd.randint(40, 130),))
    return im
