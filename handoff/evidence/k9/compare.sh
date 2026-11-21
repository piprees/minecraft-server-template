#!/usr/bin/env bash
# compare.sh — after confirm.sh: each dossier's sampled area and placeness.places beside the stand-in
# (W10c1q's placeregions.py) on the r4096/8 render over the player border, and the variant copy's patches against the aviary's.
# The comparison is the survey against placeregions.py on this render: both now cover the player border (4096 on
# both worlds), where the places are laid. Batch 1's stand-in numbers were over the old 2048-block field and are a
# reference only: sunken highlands 49.1% / jungle 48.3%, top3 89.0, major 4, specks 2.1; aviary dominantPlace 55.5,
# specks 4.9. The heart keeps half the field, so expect sunken highlands near 50% of the area (percent) again.
# The survey's land is dry land outside the sea biomes on a 32-block map (T267); the stand-in's every non-void pixel
# at 8: expect percent and dominance within a few points, land shares and specks to differ.
OUT=/tmp/claude-0/k9/c1; DEFS=$OUT/defs
PR=/tmp/claude-0/w10c1q/tools/placeregions.py
for s in the_sunken_temple the_aviary; do
  echo "== $s (survey)"
  python3 - "$OUT/full/$s/dossier.json" <<'EOF'
import json, sys
d = json.load(open(sys.argv[1]))
print('borders', d['borders'])
p = d['placeness'].get('places')
if not p: print('NO placeness.places'); sys.exit()
for k, v in p['byPlace'].items(): print('  %-28s %s' % (k, v))
print('  landRegions', {k: p['landRegions'][k] for k in ('count', 'major', 'top3Percent', 'largestPercent', 'specksPercent')})
print('  islands', p['islands'], ' (by biome: %s)' % d['placeness']['islands'].get('dominantBiomePercent'))
print('  plan patches', d['plan']['patches'], 'max |x|,|z|', max(max(abs(q['x']), abs(q['z'])) for q in d['plan']['list']))
EOF
  seed=$(python3 -c "import json;print(json.load(open('$DEFS/$s.json'))['seed'])")
  echo "== $s (stand-in on the render)"
  if [ "$s" = the_sunken_temple ]; then
    python3 "$PR" "$OUT/renders/$s" "$seed-r4096" "$DEFS/$s.json" --merge minecraft:lukewarm_ocean=highlands,minecraft:deep_lukewarm_ocean=highlands
  else
    python3 "$PR" "$OUT/renders/$s" "$seed-r4096" "$DEFS/$s.json"
  fi
done
echo "== variant copy: the_aviary_k9's patches against the_aviary's"
python3 - "$OUT/full/the_aviary/dossier.json" "$OUT/full/the_aviary_k9/dossier.json" <<'EOF'
import json, sys
a, b = (json.load(open(f)) for f in sys.argv[1:3])
strat = lambda d: [(q['x'], q['z']) for q in d['plan']['list'] if q['origin'] == 'stratified']
print('stratified patches equal:', strat(a) == strat(b), len(strat(a)))
print('seeds', a.get('seed'), b.get('seed'))
EOF
