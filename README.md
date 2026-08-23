# PS Mobile

A mobile Photoshop document viewer for Android, written in Kotlin. It opens PSD and PSB files,
renders them, and registers itself as a system handler for both extensions.

The current milestone is **opening and rendering**. Editing is not implemented; "New document"
creates a real, valid canvas so that the surrounding machinery is in place for it.

---

## Install

Grab the APK from the [latest release](https://github.com/JustRin/ps-mobile/releases/latest).
Android 8.0 or newer.

Every release is built and signed by CI with the same key, so updates install over the previous
version without uninstalling.

### Staying up to date

The app checks this repository for newer releases and tells you when one appears; the button
opens the release page and the download and install go the ordinary way, through your browser
and the system installer. The check can be switched off in Settings.

It deliberately does not install the update itself. That needs the `REQUEST_INSTALL_PACKAGES`
permission, and a sideloaded app that downloads an APK and installs it is — to a heuristic —
shaped exactly like a dropper. Google Play Protect blocks it with "Harmful app blocked", which
is a false positive but one every user would have to click past. Not holding the permission
avoids the problem entirely.

For unattended updates use [Obtainium](https://github.com/ImranR98/Obtainium), which is built
for tracking GitHub releases and holds the install permission itself. Add this repository to it:

```
https://github.com/JustRin/ps-mobile
```

---

## What it does

- **Opens PSD and PSB** of any size the formats allow — verified on an 812 MB, 800 × 247 517 px
  (198 megapixel) PSB with 155 layers, which parses in **117 ms**.
- **Registers as a file handler** for `.psd`/`.psb` by MIME type, extension and `file://` path,
  so the app appears in "Open with" from any file manager or share sheet.
- **In-app file browser** that lists only Photoshop documents, previews them, and can search the
  whole device in one pass. The system picker is still available as a fallback.
- **Recent documents** with rendered thumbnails, dimensions, layer counts and sizes.
- **New document** — Photoshop's dialog pared down: presets, size, resolution, colour mode and
  background.
- **Layers panel** with the real group hierarchy, per-layer previews, blend modes, opacity and
  colour labels. Layers can be **shown and hidden**, and tapping one **takes the canvas to it**.
- **Document properties** — format, canvas, megapixels, bit depth, channels, resolution.
- **Settings** — language, canvas surround, opening zoom, smoothing, zoomed-out quality, file
  search and preview behaviour.

## Format support

| | Supported |
|---|---|
| Containers | PSD (v1), PSB (v2) |
| Bit depths | 1, 8, 16, 32 |
| Colour modes | RGB, Grayscale, CMYK, Lab, Indexed, Bitmap, Duotone, Multichannel |
| Compression | Raw, RLE (PackBits), ZIP, ZIP with prediction |
| Structure | Image resources, layer records, groups, masks, unicode names, colour labels, blend modes, adjustment and text layer detection |

Files saved without "Maximize Compatibility" have no flattened preview; those are composited
from their layers instead, with the separable blend modes implemented.

---

## How it renders a 198-megapixel canvas

A single Android `Bitmap` for that document would be 792 MB, and no GPU will accept a texture
247 517 pixels tall. Nothing in the app ever holds the image as a whole.

**1. Scanline index.** RLE-compressed image data is preceded by a table of per-scanline byte
counts. Reading that table once turns it into absolute file offsets, after which *any* row of
*any* channel is a single positioned read — no matter how deep into the file it lives. For the
test PSB the table is 2.97 MB and the resulting index 5.9 MB, built in a fraction of the 117 ms
open. This is what makes the whole design work.

**2. Tile pyramid.** The canvas is addressed as 512 px tiles across levels; level *n* samples
every 2ⁿ-th pixel, and the top level fits in one tile. The 800 × 247 517 document becomes ten
levels, from 2 × 484 tiles at full resolution down to a single tile.

**3. On-demand decoding.** The viewer works out which tiles cover the viewport at the level
matching the current zoom, and asks for those. Tiles are decoded on a small pool of worker
threads — each with its own scanline buffers and a sliding read-ahead window — into a
byte-budgeted LRU. The coarsest level is pinned, so there is always something to paint under a
pending tile rather than a hole; finer tiles overpaint coarser ones as they arrive.

Result: a 198-megapixel document and a 2-megapixel one cost the same to draw, and full-resolution
crops from the middle of the file decode in tens of milliseconds.

**4. Live compositing when visibility changes.** The flattened preview can only ever show the
state the document was saved in, so hiding or showing a layer moves rendering onto the layer
compositor — same tile interface, same pyramid, blended on demand. Undoing every change moves it
back to the fast path. The previous coarse level is kept on screen while the new one decodes, so
the canvas sharpens in place instead of blanking.

---

## Layout

```
psdcore/     Pure Kotlin/JVM. No Android dependencies, so the format work runs on the desktop.
  io/        SeekableSource — positional, thread-safe reads over a file or descriptor
  codec/     PackBits and ZIP (with the horizontal predictor)
  model/     Header, layers, channels, blend modes, image resources
  color/     Per-depth samplers and per-mode ARGB packers
  render/    Tile pyramid, composite renderer, layer compositor, blending
  PsdParser  Structure only — never materialises pixels
  PsdWriter  Builds a valid minimal document; backs "New document"
  PsdProbe   Header-and-resources read, cheap enough to run over a whole folder
  tools/     Desktop harness that renders a document to PNG

app/         Android app, Jetpack Compose, Material 3
  document/  Uri → SeekableSource, session lifetime, the tile engine
  browser/   Filesystem and SAF listing, device search, list previews
  data/      Recents, keyed by a Uri-independent document identity
  settings/  Preferences, and the language override applied to the activity context
  ui/        Home, browser, settings, loading, viewer, layers, properties, new document
```

Keeping `psdcore` free of Android types is deliberate: the parser and renderer are exercised
from a desktop harness and from JVM unit tests, where a wrong byte shows up as a wrong PNG in
seconds rather than as a bug report from a device.

---

## Building

Requires JDK 17+ and the Android SDK (compileSdk 36, minSdk 26).

```bash
./gradlew :app:assembleDebug
```

The release build is minified with R8 and signed with the debug key so it installs directly;
replace that signing config with a real keystore before distributing it.

```bash
./gradlew :app:assembleRelease
```

### Checking the decoder without a device

```bash
./gradlew :psdcore:renderPsd -Ppsd=/path/to/file.psb -Pout=./out
```

Prints the parsed structure, the layer tree and the pyramid layout, then writes full-resolution
crops from the top, middle and end of the document plus zoomed-out views — which is how the
random-access path is verified at depth.

```bash
./gradlew :psdcore:test
```

And to time both render paths against each other on a real document:

```bash
./gradlew :psdcore:benchmarkPsd -Ppsd=/path/to/file.psb
```

It reports medians after a warm-up, the allocation each path costs, and how far the layer
compositor drifts from the flattened preview — the last one matters, because a fast renderer
that disagrees with Photoshop is not an optimisation.

---

## File access

The browser needs to see the filesystem to list documents by extension, which the system picker
cannot do. It asks for **All files access** (`MANAGE_EXTERNAL_STORAGE`); grant it under
Settings → Apps → PS Mobile → Permissions the first time.

Without it the browser falls back to per-folder grants through the system folder picker, and the
plain system file picker remains available. Documents arriving from a file manager or a share
sheet work regardless.

---

## Known limits

- Editing is not implemented — the canvas is read-only. Layer visibility can be toggled for
  inspection, but nothing is written back to the file.
- Layer effects, smart-object contents and text rendering are not applied; text layers show
  their rasterised pixels as saved.
- The layer compositor covers the separable blend modes; hue, saturation, colour and luminosity
  fall back to Normal. It runs for files without a flattened preview, and whenever layer
  visibility differs from the saved state.
- Clipping masks are approximated against the accumulated backdrop rather than the specific base
  layer.
- A flattened preview compressed with ZIP is not indexed for random access; such files use the
  layer compositor.
