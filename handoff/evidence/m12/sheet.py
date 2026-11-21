"""Contact sheet of every layer's zoom-0 tile (and the explored one) for a dimension's map export."""
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw

dim = Path(sys.argv[1])
out = sys.argv[2]
zoom = int(sys.argv[3]) if len(sys.argv) > 3 else 0
info = json.loads((dim / "layers.json").read_text())
panels = []
for layer in info["layers"]:
    panels.append((layer["id"] + (" y%s" % layer["y"] if "y" in layer else ""), dim / layer["path"]))
if (dim / "explored").is_dir():
    panels.append(("explored", dim / "explored"))
n = 2 ** zoom
size = 256 * n
cols = 4
rows = (len(panels) + cols - 1) // cols
sheet = Image.new("RGB", (cols * (size + 8), rows * (size + 24)), (20, 24, 28))
d = ImageDraw.Draw(sheet)
for k, (name, base) in enumerate(panels):
    x0 = (k % cols) * (size + 8)
    y0 = (k // cols) * (size + 24)
    tile = Image.new("RGBA", (size, size), (40, 40, 48, 255))
    for ty in range(n):
        for tx in range(n):
            f = base / str(zoom) / str(tx) / ("%d.png" % ty)
            if f.exists():
                im = Image.open(f).convert("RGBA")
                tile.alpha_composite(im, (tx * 256, ty * 256))
    sheet.paste(tile.convert("RGB"), (x0, y0 + 20))
    d.text((x0 + 2, y0 + 4), name, fill=(230, 230, 230))
sheet.save(out)
print(out, sheet.size)
