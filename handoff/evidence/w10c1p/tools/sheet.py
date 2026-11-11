# sheet.py <out.jpg> <png>...: a labelled contact sheet (4 across, 480x270), gate OK/X from each frame's census.
import json, sys, os
from PIL import Image, ImageDraw
out, fs = sys.argv[1], sys.argv[2:]
w, h, cols = 480, 270, 4
s = Image.new('RGB', (w * cols, h * ((len(fs) + cols - 1) // cols)))
d = ImageDraw.Draw(s)
for i, f in enumerate(fs):
    im = Image.open(f).convert('RGB').resize((w, h)); x, y = (i % cols) * w, (i // cols) * h; s.paste(im, (x, y))
    fr = f[:-4] + '.frame.json'
    g = json.load(open(fr)).get('gate', {}) if os.path.exists(fr) else {}
    d.text((x + 4, y + 4), os.path.basename(f)[4:-4] + (' OK' if g.get('passed', True) else ' X'), fill=(255, 255, 0))
s.save(out, quality=80)
print(len(fs))
