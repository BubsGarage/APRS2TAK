#!/usr/bin/env python3
"""
Builds an ATAK iconset (aprs-iconset.zip) from the aprs.fi APRS symbol sheets
(https://github.com/hessu/aprs-symbols).

Usage:  python3 make_aprs_iconset.py <folder with the three 64px sheets>
        aprs-symbols-64-0.png (primary), -1.png (alternate), -2.png (overlay characters)

Each sheet is a grid of 16 symbols per row, starting at '!' (ASCII 33).
Icons:
  Primary/PNNN.png      primary table symbol, NNN = ASCII code of the symbol char
  Alternate/ANNN.png    alternate table symbol
  Overlay/ONNNC.png     alternate symbol NNN with overlay character C (0-9, A-Z)
APRS2TAK builds the same names. iconset.xml only has version/name/uid;
ATAK finds the icons from the folders.
"""
import io
import os
import sys
import zipfile

from PIL import Image

# Must match ICONSET_UID in BridgeService.java
UID = "3a9d2f4e-7b61-4c58-9e02-5d4b8c1a6f71"
VERSION = 2

# Alternate-table symbols that get overlay versions. Must match OVERLAY_BASES in
# BridgeService.java. Others fall back to the plain alternate symbol.
OVERLAY_BASES = "!#%&()-0;=>AEW^_acknsuvyh"
OVERLAY_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

PER_ROW = 16
SYMBOLS = 94  # '!' (33) through '~' (126)


def load_tiles(path):
    """Returns {ascii_code: RGBA tile} for the non-empty cells of a sheet."""
    sheet = Image.open(path).convert("RGBA")
    size = sheet.width // PER_ROW
    rows = sheet.height // size
    print(f"{os.path.basename(path)}: {sheet.width}x{sheet.height}, {size}px symbols, {rows} rows")
    tiles = {}
    for i in range(SYMBOLS):
        col, row = i % PER_ROW, i // PER_ROW
        if row >= rows:
            break
        tile = sheet.crop((col * size, row * size, (col + 1) * size, (row + 1) * size))
        if tile.getbbox() is not None:  # skip empty cells
            tiles[33 + i] = tile
    return tiles


def png(img):
    buf = io.BytesIO()
    img.save(buf, "PNG")
    return buf.getvalue()


def main(folder):
    out = os.path.join(folder, "aprs-iconset.zip")
    primary = load_tiles(os.path.join(folder, "aprs-symbols-64-0.png"))
    alternate = load_tiles(os.path.join(folder, "aprs-symbols-64-1.png"))
    overlays = load_tiles(os.path.join(folder, "aprs-symbols-64-2.png"))

    icons = []  # (group, filename, png bytes)
    for code, tile in sorted(primary.items()):
        icons.append(("Primary", f"P{code:03d}.png", png(tile)))
    for code, tile in sorted(alternate.items()):
        icons.append(("Alternate", f"A{code:03d}.png", png(tile)))
    print(f"  Primary: {len(primary)}  Alternate: {len(alternate)}")

    missing = [c for c in OVERLAY_CHARS if ord(c) not in overlays]
    if missing:
        print(f"  WARNING: overlay sheet has no character for: {''.join(missing)}")
    n_over = 0
    for base in OVERLAY_BASES:
        if ord(base) not in alternate:
            print(f"  WARNING: no alternate symbol for '{base}', skipping its overlays")
            continue
        for ch in OVERLAY_CHARS:
            if ord(ch) not in overlays:
                continue
            combined = Image.alpha_composite(alternate[ord(base)], overlays[ord(ch)])
            icons.append(("Overlay", f"O{ord(base):03d}{ch}.png", png(combined)))
            n_over += 1
    print(f"  Overlay combinations: {n_over}")

    # Keep this minimal: ATAK parses iconset.xml strictly (simple-xml), so any
    # attribute it doesn't know makes it ignore the file and invent its own uid.
    xml = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
           f'<iconset version="{VERSION}" name="APRS" uid="{UID}"/>\n')

    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("iconset.xml", xml)
        for group, name, data in icons:
            z.writestr(f"{group}/{name}", data)

    # sample preview so you can eyeball a few overlays before importing
    sample = [("&", "L"), ("&", "I"), ("#", "1"), (">", "A")]
    prev = Image.new("RGBA", (64 * len(sample), 64), (255, 255, 255, 255))
    for k, (b, c) in enumerate(sample):
        if ord(b) in alternate and ord(c) in overlays:
            prev.alpha_composite(Image.alpha_composite(alternate[ord(b)], overlays[ord(c)]), (64 * k, 0))
    prev.save(os.path.join(folder, "overlay-preview.png"))

    print(f"Wrote {out} with {len(icons)} icons (preview: overlay-preview.png)")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ".")
