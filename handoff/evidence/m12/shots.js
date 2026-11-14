// Screenshots of the map viewer at 390 and 1280 px: node shots.js <base url> <out dir> <path>...
const { chromium } = require('playwright');
(async () => {
  const [base, out, ...paths] = process.argv.slice(2);
  const b = await chromium.launch();
  for (const [w, h] of [[390, 844], [1280, 900]]) {
    for (const path of paths) {
      const p = await b.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: 1 });
      const errors = [];
      p.on('pageerror', e => errors.push(String(e)));
      p.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
      await p.goto(base + path);
      await p.waitForTimeout(1500);
      const name = (path.replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'home');
      const hasMap = await p.isVisible('#layers-toggle');
      if (!hasMap) {
        // the empty state: nothing to switch
      } else if (w === 390) {
        await p.click('#layers-toggle');
        await p.waitForTimeout(300);
        await p.screenshot({ path: `${out}/${name}-${w}-layers.png` });
        await p.click('#layers-toggle');
      } else {
        await p.click('#layers-toggle');
        const radios = await p.$$('#base-layers input');
        if (radios.length > 1) await radios[1].click();
        await p.waitForTimeout(800);
        await p.screenshot({ path: `${out}/${name}-${w}-biomes.png` });
        if (radios.length) await radios[0].click();
        await p.click('#layers-toggle');
        const box = await p.$eval('#map', e => { const r = e.getBoundingClientRect(); return [r.left + r.width / 2, r.top + r.height / 2]; });
        await p.mouse.move(box[0], box[1]);
        for (let i = 0; i < 3; i++) { await p.mouse.wheel(0, -500); await p.waitForTimeout(150); }
        await p.waitForTimeout(1200);
        await p.screenshot({ path: `${out}/${name}-${w}-zoomed.png` });
        for (let i = 0; i < 3; i++) { await p.mouse.wheel(0, 500); await p.waitForTimeout(100); }
      }
      await p.waitForTimeout(800);
      const r = await p.evaluate(() => ({
        sw: document.documentElement.scrollWidth, iw: window.innerWidth,
        cards: document.querySelectorAll('#dim-list li').length,
        tiles: document.querySelectorAll('#tiles-base img').length,
        explored: document.querySelectorAll('#tiles-explored img').length,
        layers: document.querySelectorAll('#base-layers input').length,
        status: document.getElementById('map-status').textContent,
      }));
      console.log(w, path, JSON.stringify(r), errors.length ? 'errors: ' + errors.join(' | ') : 'no errors');
      await p.screenshot({ path: `${out}/${name}-${w}.png` });
      await p.close();
    }
  }
  await b.close();
})();
