# renders.py <run> <slug>: copy a run's height and biome renders to renders/<slug>-{height,biome}{,-r1024}.jpg and a 2x2 sheet.
import glob, sys
from PIL import Image
run, slug = sys.argv[1:3]
fs = []
for r, suf in (('r512', ''), ('r1024', '-r1024')):
    for k in ('height', 'biome'):
        p = glob.glob(f'/tmp/claude-0/w10c1p/runs/{run}/renders/{slug}/*-{r}-{k}.png')
        if not p: continue
        out = f'/tmp/claude-0/w10c1p/renders/{slug}-{k}{suf}.jpg'
        Image.open(p[0]).convert('RGB').save(out, quality=85); fs.append(out)
s = Image.new('RGB', (1024, 1024))
for i, f in enumerate(fs[:4]):
    s.paste(Image.open(f).resize((512, 512)), ((i % 2) * 512, (i // 2) * 512))
s.save(f'/tmp/claude-0/w10c1p/renders/{slug}-sheet.jpg', quality=80)
print(fs)
