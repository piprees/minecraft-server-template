import json, os, sys
R = '/home/user/minecraft-server-template/.claude/worktrees/agent-aba3eecf95945dad2/mods/custom-dimensions-26/'


def base(s):
    d = json.load(open(R + 'definitions/' + s + '.json'))
    src = 'def'
    for k in ('k1', 'k2', 'k3'):
        p = R + 'fidelity/drafts/%s/%s.json' % (k, s)
        if os.path.exists(p):
            dr = json.load(open(p))
            src = k
            for kk, v in dr.items():
                if kk != 'reason':
                    d[kk] = v
    return d, src


if __name__ == '__main__':
    for s in sys.argv[1:]:
        d, src = base(s)
        g = d.get('generator', {})
        t = d.get('terrain', {})
        print('##', s, src, 'gen', json.dumps(g)[:120], '| terrain',
              json.dumps({k: t[k] for k in t if k in ('base', 'scale', 'scales', 'layout', 'climateWarp')})[:220])
        print('  borders', d.get('borders'), 'liquids', d.get('liquids'))
        bl = d.get('biomeLayout')
        print('  layout', json.dumps(bl)[:500])
        for b in (d.get('biomes') or []):
            if isinstance(b, str):
                print('   ', b)
            else:
                print('   ', json.dumps(b)[:170])
