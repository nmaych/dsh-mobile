import os, sys, hashlib, glob
from PIL import Image

SRC = r"E:\deepseekworkspace\dsh-android\app-project\app\src\main\res"
APK = r"E:\deepseekworkspace\dsh-android\.tmpiconverify"

def sig(path):
    im = Image.open(path)
    im = im.convert("RGBA")
    return im.size, hashlib.sha256(im.tobytes()).hexdigest()

sources = {}
for p in glob.glob(os.path.join(SRC, "**", "ic_launcher*.png"), recursive=True):
    try:
        sources[p] = sig(p)
    except Exception as e:
        print("SRC FAIL", p, e)

extracted = {}
for p in glob.glob(os.path.join(APK, "*.png")):
    if os.path.getsize(p) < 3000:
        continue
    try:
        extracted[p] = sig(p)
    except Exception as e:
        print("APK FAIL", p, e)

rev = {}
for p, s in sources.items():
    rev.setdefault(s, []).append(os.path.relpath(p, SRC))

matched = 0
for p, s in sorted(extracted.items(), key=lambda kv: -kv[1][0][0]):
    label = rev.get(s)
    name = os.path.basename(p)
    if label:
        matched += 1
        print(f"MATCH  {s[0][0]}x{s[0][1]}  {name}  ->  {label[0]}")
    else:
        print(f"UNKWN  {s[0][0]}x{s[0][1]}  {name}  sha={s[1][:16]}")

print()
print(f"extracted={len(extracted)} sources={len(sources)} matched={matched}")

# Explicitly prove the xxxhdpi foreground (largest source) is present
big = max(sources.items(), key=lambda kv: kv[1][0][0] * kv[1][0][1])
print("largest source:", os.path.relpath(big[0], SRC), big[1][0])
print("present in APK:", big[1] in extracted.values())
