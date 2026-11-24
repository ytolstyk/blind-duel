# Play Store listing assets

Everything uploaded to the Play Console listing lives here so it's versioned with the app.

```
store/
  listing/en-US/        title.txt (<=30), short_description.txt (<=80), full_description.txt (<=4000)
  graphics/icon/        play_store_icon_512.png          512x512 PNG (RGBA, full-bleed)
  graphics/feature/     feature_graphic_1024x500.png     1024x500 PNG (RGB, no alpha)
  screenshots/phone/        8 x 1080x1920 PNG            (9:16)
  screenshots/tablet-7/     8 x 1200x1920 PNG            (5:8)
  screenshots/tablet-10/    8 x 1600x2560 PNG            (5:8)
  raw/<device>/         curated raw emulator frames the screenshots are composed from
  tools/                scripts + vendored Roboto (Apache 2.0, same font family as the app)
    store_common.py       paths, Play rules, shared backdrop; reads the palette from ui/theme/Color.kt
                          and the icon geometry from res/drawable/ic_launcher_foreground.xml, so an
                          app-side colour/icon change flows into the store art on the next run
    make_store_graphics.py, compose_screenshots.py, capture_screenshots.py, validate_assets.py
```

Upload order = filename order (`01_…` first). Play shows at most 8 per device type.

## Play requirements these are built to

| Asset | Requirement |
|---|---|
| Icon | 512x512, PNG, <= 1024 KB. Full square, no pre-rounded corners (Play masks it). |
| Feature graphic | 1024x500, PNG or JPEG, no alpha. |
| Screenshots | PNG/JPEG, <= 8 MB each, each side 320-3840 px, long side <= 2x short side, 2-8 per device type. |
| Text | Title 30, short description 80, full description 4000 characters. |

`python3 store/tools/validate_assets.py` checks all of the above and exits non-zero on a violation.

## Regenerating

Needs Python 3 + Pillow (`pip install pillow`).

```bash
# Icon + feature graphic (deterministic; no emulator needed)
python3 store/tools/make_store_graphics.py

# Screenshots from existing raw frames (change captions / layout in compose_screenshots.py)
python3 store/tools/compose_screenshots.py [phone|tablet-7|tablet-10]

python3 store/tools/validate_assets.py
```

### Re-shooting the raw frames after a UI change

Real duels need two phones, so the capture drives the **debug** build's Solo Test practice mode.

```bash
./gradlew installDebug                      # on the emulator, e.g. Pixel_6 / Tablet7 / Tablet_10
python3 store/tools/capture_screenshots.py --serial emulator-5554 --out /tmp/raw/phone
```

This writes the static screens (`mainmenu`, `settings`, `create`, `calibration`, `countdown`) plus a
~60 s burst of `duel_NNN.png` frames. Picking frames is a manual curation step: flip through the burst
and copy the best ones into `store/raw/<device>/` under the names `compose_screenshots.py` expects
(`aim`, `crate`, `cone`, `win`, `danger`, plus the static ones). A shot is skipped for a device if its
raw frame is missing (e.g. a 10" run that never wins has no `win`). `--skip-duel` re-shoots only the
static screens.

The composer paints out the status bar and debug-only UI (the "Leave Practice" link, the whole
"Solo Test Options" block on Create) so the images match a release build.

AVDs used: `Pixel_6` (1080x2400), `Tablet7` (1080x1920 @320dpi), `Tablet_10` (1280x2048 @240dpi).

## Known caveats

- The calibration and victory shots show the loopback opponent's name, "Practice Dummy", since they
  come from Solo Test mode. Re-shoot those two on two real phones if you'd rather show a real name.
- The Play icon is the launcher icon (`res/drawable/ic_launcher_*`) rendered at 512px, cropped to the
  adaptive-icon safe area so it matches what users see on-device. Geometry is parsed from the vector XML,
  so just re-run `make_store_graphics.py` after changing the launcher icon (the parser supports only the
  path commands the icon uses today and fails loudly on others).
- Debug-only UI (the "Leave Practice" link, the "Solo Test Options" block) is covered by pixel heuristics in
  `compose_screenshots.py`. They raise if they find nothing to cover, but a UI relayout can still need the
  tuning constants adjusted — eyeball the output after any UI change. A cleaner long-term fix is an
  app-side "store capture" launch flag that hides that UI.
- Known debt: `DEVICES`/`SHOTS`/captions live in code (no locale axis yet), and picking frames from the
  duel burst is a manual copy/rename step.
- Listing copy only claims what the app does today (no ads / accounts: the build has no ad, analytics or
  billing SDKs). Re-check it against the Data safety form before each release.
