# pack.py: review-pack text parts per slug: description (intent card), the owner's note (verbatim, with the rulings), design card.
import os, re, shutil
R = '/home/user/minecraft-server-template'; F = R + '/mods/custom-dimensions-26/fidelity'; P = '/tmp/claude-0/w10s/review-pack'
SLUGS = 'the_violet_spire the_sunken_temple the_abyssal_shrine the_molten_flats the_forged_depths the_wailing_narrows the_gilded_pit the_bloodroot_wastes the_end the_nether'.split()
notes = open(F + '/feedback/owner-review-1.md').read()
rulings = notes.split('## Batch 1')[0].split('## Rulings', 1)[1].strip()
for s in SLUGS:
    d = f'{P}/{s}'; os.makedirs(d + '/shots', exist_ok=True)
    shutil.copy(f'{F}/intent/{s}.md', f'{d}/description.md')
    m = re.search(r'^- \*\*' + s + r'\*\* \(([^)]*)\): (.*)$', notes, re.M)
    note = f'The owner ({m.group(1)}): {m.group(2)}' if m else 'The owner left no note for this dimension.'
    open(f'{d}/owner-note.md', 'w').write(f'# {s}: the owner\'s note\n\n{note}\n\n## The owner\'s rulings for every dimension\n\n{rulings}\n')
    shutil.copy(f'{R}/handoff/evidence/w10r/cards/{s}.md', f'{d}/card.md')
    print(s, 'note' if m else 'NO NOTE')
