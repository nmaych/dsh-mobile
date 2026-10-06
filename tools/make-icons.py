"""Generate Android launcher icons for DSH Mobile from the DeepSeek Harness artwork.

Source: ``C:\\Users\\Administrator\\.dsh\\icons\\deepseek-harness.ico``
(256x256, RGBA, with transparent rounded corners and a wordmark band at the
bottom).

Two things about that artwork drive the choices here:

1. **The corners are transparent.** An adaptive icon's foreground is composited
   over the background colour and then masked by the launcher. Left as-is, the
   transparent corners would reveal the background colour and you would see a
   rounded square floating inside a circular mask. So the artwork is flattened
   onto a sampled background colour first, making it fully opaque and full-bleed.

2. **There is a "Deepseek" wordmark across the bottom.** Launcher masks crop the
   outer part of the canvas, so the wordmark would be sliced on a circular mask.
   The mascot is therefore cropped above the band for the adaptive foreground —
   the face is what has to survive the mask.

Usage:
    python tools/make-icons.py
"""
import os
import sys

from PIL import Image, ImageDraw

SRC = r"C:\Users\Administrator\.dsh\icons\deepseek-harness.ico"
RES = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app-project", "app", "src", "main", "res",
)
PREVIEW = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "docs", "icon-preview.png",
)

# Legacy launcher icon sizes, per density.
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

# Adaptive icon foreground canvas: 108dp, at each density.
ADAPTIVE = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}


def load_source():
    """Return the artwork as a square RGBA image, plus its background colour."""
    img = Image.open(SRC)
    # An .ico holds several sizes; take the largest.
    img.size = max(img.info.get("sizes", {(img.width, img.height)}))
    img.load()
    img = img.convert("RGBA")
    if img.width != img.height:
        side = max(img.size)
        square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
        square.paste(img, ((side - img.width) // 2, (side - img.height) // 2), img)
        img = square
    print(f"source: {img.width}x{img.height} {img.mode}")
    return img


def sample_background(img):
    """Pick the colour to flatten transparency onto.

    Sampled from the top edge, which is the artwork's own light backdrop. The
    median is used rather than the mean so the blue hair at the edges cannot
    drag the result towards a muddy average.
    """
    w, h = img.size
    samples = []
    for x in range(4, w - 4, 4):
        for y in (6, 10, 14):
            r, g, b, a = img.getpixel((x, y))
            if a > 200:
                samples.append((r, g, b))
    if not samples:
        return (255, 255, 255)
    samples.sort(key=lambda c: c[0] + c[1] + c[2])
    return samples[len(samples) // 2]


def wordmark_top(img, dark_threshold=90):
    """Find where the dark wordmark band starts, scanning up from the bottom.

    Samples the **left margin** rather than the full width. The band's middle
    contains the word "Deepseek" in white, which lifts a full-width average back
    above the threshold and makes the scan stop early — measuring that way put
    the boundary at y=237 when the plate actually begins at y=210, leaving the
    wordmark in the adaptive foreground where the launcher mask cuts it.

    Returns the image height when there is no band, so callers can treat the
    result as a crop bound unconditionally.
    """
    w, h = img.size
    # The plate spans the full width; the text does not reach the margins.
    margin = max(4, int(w * 0.02))
    strip_end = max(margin + 8, int(w * 0.18))

    for y in range(h - 1, 0, -1):
        opaque = [
            img.getpixel((x, y))[:3]
            for x in range(margin, strip_end, 2)
            if img.getpixel((x, y))[3] > 200
        ]
        if not opaque:
            continue
        luma = sum(0.299 * r + 0.587 * g + 0.114 * b for r, g, b in opaque) / len(opaque)
        if luma >= dark_threshold:
            return y + 1
    return h


def content_box(img, fill, tolerance=18):
    """Bounding box of what differs from the flat `fill` colour.

    The artwork is flattened onto an opaque background, so its alpha channel is
    fully opaque and `getbbox()` on alpha would return the whole canvas. The
    margin is found by colour difference instead.
    """
    w, h = img.size
    px = img.load()
    left, top, right, bottom = w, h, -1, -1
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a <= 200:
                continue
            if abs(r - fill[0]) + abs(g - fill[1]) + abs(b - fill[2]) > tolerance:
                if x < left:
                    left = x
                if x > right:
                    right = x
                if y < top:
                    top = y
                if y > bottom:
                    bottom = y
    if right < 0:
        return None
    return (left, top, right + 1, bottom + 1)


def face_box(img):
    """Bounding box of the face, found by skin-tone detection.

    The mascot is not centred in its own canvas — the face sits right of and
    below centre (x 72..247, y 80..201 of a 256x209 crop), because the raised
    hand and the hair occupy the left and top. Masking around the *canvas*
    centre therefore clips the chin, so the adaptive foreground centres on the
    face instead.

    Returns (left, top, right, bottom), or None when nothing matches.
    """
    w, h = img.size
    xs, ys = [], []
    for y in range(h):
        for x in range(w):
            r, g, b, a = img.getpixel((x, y))
            if a <= 200:
                continue
            # Warm, bright, and red-dominant: skin rather than the blue hair.
            if r > 180 and g > 120 and b > 110 and r > g > b and (r - b) > 25 and (r - g) < 90:
                xs.append(x)
                ys.append(y)
    if not xs:
        return None
    return (min(xs), min(ys), max(xs), max(ys))


def centred_on_face(img, box, size, fill, safe_ratio=66 / 108, face_fraction=0.58):
    """Compose `img` for a launcher icon: full-bleed, centred on the face.

    Why this is fiddly, and what each part solves:

    * **The full square artwork is used, not the cropped mascot.** Cropping the
      wordmark off leaves a wide, short image (256x209); cover-fitting that to a
      square needs a big scale that pushes the face down, and padding it back to
      a square leaves a visible seam where the light padding meets the dark
      wordmark plate. Keeping the square original avoids both. The wordmark then
      falls under the mask's lower edge, which is where decorative text belongs.

    * **Centring on the canvas would clip the chin.** The mascot's face sits
      right of and below its own canvas centre (x 72..247, y 80..201 of 256x256),
      so the face is what gets centred instead.

    * **The face drives the scale.** Fitting the whole canvas (hair, raised
      hand) makes the face a small sticker in a coloured disc, so the face is
      sized to `face_fraction` of the visible diameter — but never below a
      cover-fit, or the background would show inside the mask.

    Args:
        img: the square artwork.
        box: the face bounding box within `img`, or None.
        size: output canvas edge length.
        fill: RGB background colour.
        safe_ratio: visible fraction of the canvas (66dp of 108dp).
        face_fraction: face size as a fraction of the visible diameter.
    """
    safe_d = size * safe_ratio
    side = max(img.size)

    if box is None:
        left, top, right, bottom = 0, 0, img.width, img.height
    else:
        left, top, right, bottom = box

    face_h = max(1, bottom - top)
    face_cx = (left + right) / 2
    face_cy = (top + bottom) / 2

    # Enough to make the face the intended size, but never less than a
    # cover-fit — otherwise the background shows inside the mask.
    scale = max((safe_d * face_fraction) / face_h, size / side)

    scaled = img.resize(
        (max(1, round(img.width * scale)), max(1, round(img.height * scale))),
        Image.LANCZOS,
    )

    # Centre the face, then clamp so no gap can open at any edge.
    offset_x = size / 2 - face_cx * scale
    offset_y = size / 2 - face_cy * scale
    offset_x = min(max(offset_x, size - scaled.width), 0.0)
    offset_y = min(max(offset_y, size - scaled.height), 0.0)

    canvas = Image.new("RGBA", (size, size), fill + (255,))
    canvas.paste(scaled, (round(offset_x), round(offset_y)), scaled)
    return canvas


def rounded(img, radius_ratio=0.22):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, img.width - 1, img.height - 1],
        radius=int(img.width * radius_ratio),
        fill=255,
    )
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def circular(img):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).ellipse([0, 0, img.width - 1, img.height - 1], fill=255)
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def main():
    art = load_source()
    bg = sample_background(art)
    print(f"background colour: #{bg[0]:02X}{bg[1]:02X}{bg[2]:02X}")

    # Flatten onto the sampled colour so the icon is opaque and full-bleed.
    flat = Image.new("RGBA", art.size, bg + (255,))
    flat.paste(art, (0, 0), art)
    print(f"flattened to opaque {flat.width}x{flat.height}")

    band = wordmark_top(art)
    print(f"wordmark band starts at y={band} of {art.height}")

    # The face is measured on the full square artwork, because that is what gets
    # composed — cropping to the mascot first would leave a short image whose
    # cover-fit pushes the face out of the mask.
    face = face_box(flat)
    if face:
        print(f"face bbox: {face}  (art is {flat.width}x{flat.height})")
    else:
        print("face not detected; falling back to canvas centring")

    # --- legacy icons -------------------------------------------------------
    # Legacy icons are also masked by the launcher, so they use the same
    # face-centred composition. `safe_ratio=1.0` because a legacy icon has no
    # 66dp safe zone — the whole bitmap is the icon.
    for name, size in DENSITIES.items():
        d = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        base = centred_on_face(flat, face, size, bg, safe_ratio=1.0, face_fraction=0.62)
        rounded(base).save(os.path.join(d, "ic_launcher.png"))
        circular(base).save(os.path.join(d, "ic_launcher_round.png"))
    print("wrote legacy icons")

    # --- adaptive foreground ------------------------------------------------
    # Android scales the 108dp foreground so that only the inner 66dp is
    # guaranteed visible; a launcher may mask away everything outside that
    # circle. The composition covers the whole canvas so no background shows
    # inside the mask, while keeping the face inside the safe circle.
    safe_ratio = 66 / 108
    for name, size in ADAPTIVE.items():
        d = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        centred_on_face(flat, face, size, bg, safe_ratio=safe_ratio).save(
            os.path.join(d, "ic_launcher_foreground.png")
        )
    print(f"wrote adaptive foregrounds (face sized to the {safe_ratio:.0%} safe circle)")

    # --- adaptive descriptors ----------------------------------------------
    anydpi = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    for fname in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(anydpi, fname), "w", encoding="utf-8") as f:
            f.write(
                '<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@color/ic_launcher_background" />\n'
                '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
                '</adaptive-icon>\n'
            )
    print("wrote adaptive descriptors")

    # --- background colour resource ----------------------------------------
    colors = os.path.join(RES, "values", "colors.xml")
    with open(colors, "w", encoding="utf-8") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<resources>\n"
            f'    <color name="ic_launcher_background">#{bg[0]:02X}{bg[1]:02X}{bg[2]:02X}</color>\n'
            "</resources>\n"
        )
    print(f"wrote {colors}")

    # --- preview: what the launcher actually shows --------------------------
    # Render through the masks that matter, at the size a launcher really draws
    # them. A crop problem should be visible here rather than on a home screen.
    cell = 200
    pad = 16
    sheet = Image.new("RGBA", (cell * 4 + pad * 5, cell + pad * 2 + 30), (245, 245, 248, 255))
    d = ImageDraw.Draw(sheet)

    def masked(im, shape):
        """Composite `im` as the launcher would: mask diameter is ~72dp of the
        108dp canvas, centred."""
        im = im.resize((cell, cell), Image.LANCZOS)
        mask = Image.new("L", (cell, cell), 0)
        dd = ImageDraw.Draw(mask)
        md = int(cell * 72 / 108)
        mo = (cell - md) // 2
        if shape == "circle":
            dd.ellipse([mo, mo, mo + md, mo + md], fill=255)
        elif shape == "squircle":
            dd.rounded_rectangle([mo, mo, mo + md, mo + md], radius=int(md * 0.30), fill=255)
        else:
            dd.rectangle([0, 0, cell - 1, cell - 1], fill=255)
        out = Image.new("RGBA", (cell, cell), (0, 0, 0, 0))
        out.paste(im, (0, 0), mask)
        return out

    legacy = Image.open(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher.png")).convert("RGBA")
    adaptive = Image.open(
        os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher_foreground.png")
    ).convert("RGBA")
    adaptive_on_bg = Image.new("RGBA", adaptive.size, bg + (255,))
    adaptive_on_bg.paste(adaptive, (0, 0), adaptive)

    variants = [
        ("legacy (rounded)", legacy, "squircle"),
        ("legacy round", legacy, "circle"),
        ("adaptive on circle", adaptive_on_bg, "circle"),
        ("adaptive on squircle", adaptive_on_bg, "squircle"),
    ]
    for i, (label, im, shape) in enumerate(variants):
        x = pad + i * (cell + pad)
        composed = masked(im, shape)
        sheet.paste(composed, (x, pad), composed)
        d.text((x + 4, pad + cell + 6), label, fill=(40, 40, 50, 255))
    sheet.convert("RGB").save(PREVIEW)
    print(f"wrote {PREVIEW}")


if __name__ == "__main__":
    sys.exit(main())
