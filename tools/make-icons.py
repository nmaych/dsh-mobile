"""Generate Android launcher icons for DSH Mobile from the DSH artwork."""
import os
from PIL import Image, ImageDraw

SRC = r"E:\deepseekworkspace\dsh-icon\shell_resolved_icon.png"
RES = r"E:\deepseekworkspace\dsh-android\app-project\app\src\main\res"

# The source is a preview sheet: the large rounded-square mark on the left plus
# two small variants. Crop the large mark.
img = Image.open(SRC).convert("RGBA")
print("source size:", img.size)

# Locate the main mark by trimming near-white margin on the left half.
crop = img.crop((0, 0, int(img.width * 0.82), img.height))
print("crop size:", crop.size)

# Trim uniform light background around the mark.
def trim(im, tol=12):
    bg = Image.new("RGBA", im.size, (255, 255, 255, 255))
    from PIL import ImageChops
    diff = ImageChops.difference(im.convert("RGB"), bg.convert("RGB")).convert("L")
    mask = diff.point(lambda p: 255 if p > tol else 0)
    box = mask.getbbox()
    return im.crop(box) if box else im

mark = trim(crop)
print("trimmed mark:", mark.size)

# Make it square (pad with transparent) then resize.
side = max(mark.size)
square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
square.paste(mark, ((side - mark.width) // 2, (side - mark.height) // 2), mark)

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

# Adaptive icon foreground is 108dp with the safe zone in the middle ~66%.
ADAPTIVE = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}


def rounded(im, radius_ratio=0.22):
    im = im.convert("RGBA")
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, im.width - 1, im.height - 1], radius=int(im.width * radius_ratio), fill=255
    )
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    return out


def circle(im):
    im = im.convert("RGBA")
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).ellipse([0, 0, im.width - 1, im.height - 1], fill=255)
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    return out


for name, size in DENSITIES.items():
    d = os.path.join(RES, f"mipmap-{name}")
    os.makedirs(d, exist_ok=True)

    base = square.resize((size, size), Image.LANCZOS)
    rounded(base).save(os.path.join(d, "ic_launcher.png"))
    circle(base).save(os.path.join(d, "ic_launcher_round.png"))
    print("wrote", d)

# Adaptive foreground: the mark inside the 66% safe zone on a transparent canvas.
for name, size in ADAPTIVE.items():
    d = os.path.join(RES, f"mipmap-{name}")
    os.makedirs(d, exist_ok=True)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    inner = int(size * 0.62)
    art = square.resize((inner, inner), Image.LANCZOS)
    off = (size - inner) // 2
    canvas.paste(art, (off, off), art)
    canvas.save(os.path.join(d, "ic_launcher_foreground.png"))
    print("wrote foreground", d)

# Adaptive icon descriptors.
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

# A preview sheet so the result can be eyeballed.
prev = Image.new("RGBA", (48 * 2 + 192 + 60, 200), (240, 240, 240, 255))
prev.paste(rounded(square.resize((48, 48), Image.LANCZOS)), (10, 20), rounded(square.resize((48, 48), Image.LANCZOS)))
prev.paste(rounded(square.resize((192, 192), Image.LANCZOS)), (80, 4), rounded(square.resize((192, 192), Image.LANCZOS)))
prev.convert("RGB").save(r"E:\deepseekworkspace\dsh-android\docs\icon-preview.png")
print("wrote preview")
