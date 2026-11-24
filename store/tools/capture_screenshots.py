#!/usr/bin/env python3
"""Capture raw Play Store screenshots from a running emulator/device via adb.

Drives a *debug* build (it needs the debug-only "Solo Test" practice mode, since a real
duel needs two phones) through the main screens and saves raw PNGs. The raw PNGs are NOT
store-ready (status bar, debug UI, odd aspect ratio) — curate them into store/raw/<device>/ and
run compose_screenshots.py next (see store/README.md).

Usage:
    python3 store/tools/capture_screenshots.py --serial emulator-5554 --out /tmp/raw/phone

Requires: adb on PATH and a debug APK already installed (`./gradlew installDebug`). Screens are located
by on-screen text via uiautomator, so it works at any resolution/density. The emulator's SystemUI demo
mode and the app are always restored/stopped on exit, including on failure.
"""
import argparse
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import List, NamedTuple, Optional, Tuple

# Debug-build identifiers (applicationId + ".debug" suffix from app/build.gradle.kts).
PKG = "com.tolstykh.blindduel.debug"
ACTIVITY = f"{PKG}/com.tolstykh.blindduel.MainActivity"
PLAYER_NAME = "Ghost"  # generic on purpose: never ship a real name in store imagery

# On-screen labels the scenario taps/waits for. They live in Compose code, not strings.xml, so a
# rename in the app breaks capture here: MainMenuScreen ("Create Duel"), CreateScreen ("Marathon",
# "Sniper Rifle", "Solo Test (Debug)"), SettingsScreen ("Trail theme"), DodgeButton ("DODGE").
LABEL_CREATE = "Create Duel"
LABEL_SETTINGS = "Settings"
LABEL_SETTINGS_SECTION = "Trail theme"
LABEL_SOLO_TEST = "Solo Test (Debug)"
LABEL_READY = "Ready"
LABEL_DUEL_HUD = "DODGE"
LABEL_MARATHON = "Marathon"          # 5 health dots: survives long enough to catch a crate / win
LABEL_SNIPER = "Sniper Rifle"        # shows the aim reticle

ADB_TIMEOUT_S = 60
WAIT_POLL_S = 0.2
NAME_MAX_CHARS = 24
DUEL_BURST_S = 60                    # how long to keep taking duel frames
FRAME_INTERVAL_S = 0.2               # pause between frames so capture doesn't starve the emulator
SWIPE_EVERY_S = 5
SWIPE_DURATION_MS = 2400
SWIPE_FRAMES_PER_DIRECTION = 12
SWIPE_REACH = (0.22, 0.27)           # sideways reach (fraction of w) and target y (fraction of h)
DUEL_START_TIMEOUT_S = 30


class ScreenNotFound(RuntimeError):
    pass


class UiNode(NamedTuple):
    text: str
    desc: str
    cls: str
    center: Tuple[int, int]


class Device:
    def __init__(self, serial: str, out: Path):
        self.serial, self.out = serial, out
        out.mkdir(parents=True, exist_ok=True)
        self._swipes: List[subprocess.Popen] = []

    # -- adb plumbing
    def adb(self, *args: str, check: bool = True) -> bytes:
        r = subprocess.run(["adb", "-s", self.serial, *args], capture_output=True, timeout=ADB_TIMEOUT_S)
        if check and r.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} failed: {r.stderr.decode(errors='replace').strip()}")
        return r.stdout

    def sh(self, cmd: str, check: bool = True) -> str:
        return self.adb("shell", cmd, check=check).decode(errors="replace")

    def size(self) -> Tuple[int, int]:
        w, h = self.sh("wm size").split(":")[-1].strip().split("x")
        return int(w), int(h)

    def shot(self, name: str) -> Path:
        p = self.out / f"{name}.png"
        p.write_bytes(self.adb("exec-out", "screencap", "-p"))
        return p

    def swipe_async(self, x0: int, y0: int, x1: int, y1: int, duration_ms: int) -> None:
        self._swipes = [p for p in self._swipes if p.poll() is None]  # reap finished ones
        self._swipes.append(subprocess.Popen(
            ["adb", "-s", self.serial, "shell", "input", "swipe", str(x0), str(y0), str(x1), str(y1),
             str(duration_ms)]))

    def finish_swipes(self) -> None:
        for p in self._swipes:
            try:
                p.wait(timeout=ADB_TIMEOUT_S)
            except subprocess.TimeoutExpired:
                p.kill()
        self._swipes = []

    # -- UI lookup (one adb call per dump, straight to stdout: no stale /sdcard file to misread)
    def nodes(self) -> List[UiNode]:
        raw = self.adb("exec-out", "uiautomator", "dump", "/dev/tty", check=False).decode(errors="replace")
        end = raw.find("</hierarchy>")
        if end < 0:
            return []
        try:
            root = ET.fromstring(raw[:end + len("</hierarchy>")])
        except ET.ParseError:
            return []
        out = []
        for n in root.iter("node"):
            try:
                lo, hi = n.get("bounds", "").strip("[]").split("][")
                x1, y1 = map(int, lo.split(","))
                x2, y2 = map(int, hi.split(","))
            except ValueError:
                continue
            out.append(UiNode(n.get("text", ""), n.get("content-desc", ""), n.get("class", ""),
                              ((x1 + x2) // 2, (y1 + y2) // 2)))
        return out

    def find(self, label: str = "", cls: str = "") -> Optional[UiNode]:
        """First node matching every selector given (label vs text/content-desc, cls vs class suffix)."""
        for n in self.nodes():
            if label and label not in (n.text, n.desc):
                continue
            if cls and not n.cls.endswith(cls):
                continue
            return n
        return None

    def wait_for(self, label: str = "", cls: str = "", timeout: float = 20) -> UiNode:
        end = time.time() + timeout
        while time.time() < end:
            node = self.find(label, cls)
            if node:
                return node
            time.sleep(WAIT_POLL_S)  # also stops a tight spin if adb goes offline
        raise ScreenNotFound(f"timed out waiting for '{label or cls}'")

    def tap_node(self, node: UiNode, settle: float = 0.0) -> None:
        self.sh(f"input tap {node.center[0]} {node.center[1]}")
        time.sleep(settle)

    def tap(self, label: str = "", cls: str = "", settle: float = 0.0) -> UiNode:
        node = self.wait_for(label, cls)
        self.tap_node(node, settle)
        return node


# -- SystemUI demo mode: clean status bar (12:00, full battery, no notifications)
def enable_demo_mode(d: Device) -> None:
    d.sh("settings put global sysui_demo_allowed 1")
    for command in ("enter", "clock -e hhmm 1200", "battery -e level 100 -e plugged false",
                    "network -e wifi show -e level 4", "notifications -e visible false"):
        d.sh(f"am broadcast -a com.android.systemui.demo -e command {command}")


def disable_demo_mode(d: Device) -> None:
    d.sh("am broadcast -a com.android.systemui.demo -e command exit", check=False)


# -- scenario steps
def restart_app(d: Device) -> None:
    d.sh(f"am force-stop {PKG}")
    d.sh(f"am start -n {ACTIVITY}")
    d.wait_for(LABEL_CREATE)


def set_player_name(d: Device) -> None:
    d.tap(cls="EditText", settle=0.5)
    d.sh("input keyevent KEYCODE_MOVE_END " + " ".join(["KEYCODE_DEL"] * NAME_MAX_CHARS))
    d.sh(f"input text {shlex.quote(PLAYER_NAME)}")
    d.sh("input keyevent KEYCODE_BACK")
    # Relaunch so the (DataStore-persisted) name shows without a focused text field.
    restart_app(d)


def shoot_static_screens(d: Device) -> None:
    d.shot("mainmenu")

    d.tap(LABEL_SETTINGS)
    d.wait_for(LABEL_SETTINGS_SECTION)
    d.shot("settings")
    d.sh("input keyevent KEYCODE_BACK")

    d.tap(LABEL_CREATE)
    solo = d.wait_for(LABEL_SOLO_TEST)
    d.shot("create")
    d.tap(LABEL_MARATHON)
    d.tap(LABEL_SNIPER)
    d.tap_node(solo)
    ready = d.wait_for(LABEL_READY)
    d.shot("calibration")
    d.tap_node(ready, settle=1.0)
    d.shot("countdown")


def sweep_target_x(frame_index: int, center_x: int, width: int) -> int:
    """Alternate the aim swipe left/right every SWIPE_FRAMES_PER_DIRECTION frames."""
    direction = -1 if (frame_index // SWIPE_FRAMES_PER_DIRECTION) % 2 else 1
    return center_x + direction * int(width * SWIPE_REACH[0])


def capture_duel_frames(d: Device, w: int, h: int) -> int:
    """Burst-capture duel frames while aiming now and then; the best ones are picked by eye later."""
    d.wait_for(LABEL_DUEL_HUD, timeout=DUEL_START_TIMEOUT_S)
    cx, cy = w // 2, int(h * 0.52)
    deadline = time.time() + DUEL_BURST_S
    last_swipe = float("-inf")  # swipe immediately on the first frame
    frames = 0
    while time.time() < deadline:
        if time.time() - last_swipe > SWIPE_EVERY_S:
            d.swipe_async(cx, cy, sweep_target_x(frames, cx, w), int(h * SWIPE_REACH[1]), SWIPE_DURATION_MS)
            last_swipe = time.time()
        d.shot(f"duel_{frames:03d}")
        frames += 1
        time.sleep(FRAME_INTERVAL_S)
    return frames


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--skip-duel", action="store_true",
                    help="only re-shoot the static screens (menu/settings/create/calibration/countdown)")
    a = ap.parse_args()

    d = Device(a.serial, a.out)
    w, h = d.size()
    try:
        enable_demo_mode(d)
        restart_app(d)
        set_player_name(d)
        shoot_static_screens(d)
        if not a.skip_duel:
            frames = capture_duel_frames(d, w, h)
            print(f"saved {frames} duel frames to {a.out}")
        print(f"saved static screens to {a.out}")
    finally:
        # Restore the emulator first; swipe cleanup must never be able to skip it.
        disable_demo_mode(d)
        d.sh(f"am force-stop {PKG}", check=False)
        d.finish_swipes()


if __name__ == "__main__":
    try:
        main()
    except (ScreenNotFound, RuntimeError, subprocess.TimeoutExpired) as e:
        sys.exit(f"capture failed: {e}")
