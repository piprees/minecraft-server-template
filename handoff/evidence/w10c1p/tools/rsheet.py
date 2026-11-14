# rsheet.py <out.jpg> <render dir>...: height and biome render pairs side by side, 256 px each, labelled.
import glob, sys, os
from PIL import Image, ImageDraw
out, dirs = sys.argv[1], sys.argv[2:]
rows = []
for d in dirs:
    h = sorted(glob.glob(f'{d}/*-height.png')); b = sorted(glob.glob(f'{d}/*-biome.png'))
    if h and b: rows.append((os.path.basename(d), h[-1], b[-1]))
cols = 2
s = Image.new('RGB', (cols * 520, 260 * ((len(rows) + cols - 1) // cols)), (40, 40, 40)); dr = ImageDraw.Draw(s)
for i, (n, h, b) in enumerate(rows):
    x, y = (i % cols) * 520, (i // cols) * 260
    s.paste(Image.open(h).convert('RGB').resize((256, 256)), (x, y)); s.paste(Image.open(b).convert('RGB').resize((256, 256)), (x + 258, y))
    dr.text((x + 4, y + 4), n, fill=(255, 255, 0))
s.save(out, quality=80); print(len(rows))
