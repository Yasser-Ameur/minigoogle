// Produces every image under assets/ from a running MiniGoogle node.
//
//   assets/hero-dark.png, hero-light.png            1280x800, viewport 1280x800 at 1x
//   assets/hero-dark@2x.png, hero-light@2x.png      2560x1600, same viewport at deviceScaleFactor 2
//   assets/scenes/1..6-*.png                        1280x800 stills of one task, dark theme
//   assets/demo.gif                                 assembled from the scenes by assets/make-gif.py
//
// Seed the node first with assets/seed.sh so the results are real pages, then:
//
//   docker run --rm -v "$PWD/assets:/work" -w /tmp \
//     --add-host=host.docker.internal:host-gateway \
//     -e MINIGOGLE_API_KEY=change-me-please-16plus \
//     mcr.microsoft.com/playwright:v1.62.1-jammy \
//     bash -c "npm i --silent --no-audit --no-fund playwright@1.62.1 && NODE_PATH=/tmp/node_modules node /work/capture.cjs http://host.docker.internal:8080 /work"
//
// On a machine with Node: npm i playwright@1.62.1 && node assets/capture.cjs http://localhost:8080 assets
const { chromium } = require('playwright');

const base = process.argv[2] || 'http://localhost:8080';
const out = process.argv[3] || 'assets';
const key = process.env.MINIGOGLE_API_KEY || 'change-me-please-16plus';
const heroQuery = 'http caching semantics';
const addedUrl = 'https://www.postgresql.org/docs/current/textsearch-intro.html';
const sceneQuery = 'postgresql tsvector match operator';
const viewport = { width: 1280, height: 800 };

(async () => {
  const browser = await chromium.launch();

  for (const colorScheme of ['dark', 'light']) {
    for (const deviceScaleFactor of [1, 2]) {
      const ctx = await browser.newContext({ viewport, deviceScaleFactor, colorScheme });
      const page = await ctx.newPage();
      await page.goto(`${base}/?q=${encodeURIComponent(heroQuery)}`);
      await page.waitForSelector('.result');
      const suffix = deviceScaleFactor === 2 ? '@2x' : '';
      await page.screenshot({ path: `${out}/hero-${colorScheme}${suffix}.png` });
      await ctx.close();
    }
  }

  // One task, six stills: add a page to the index, then find it.
  const ctx = await browser.newContext({ viewport, colorScheme: 'dark' });
  const page = await ctx.newPage();
  const scene = (name) => page.screenshot({ path: `${out}/scenes/${name}.png` });

  await page.goto(base);
  await page.waitForSelector('.index-stats');
  await scene('1-home');

  await page.fill('#add-url-input', addedUrl);
  await page.click('.add-url__row button');
  await page.waitForSelector('#add-url-key');
  await scene('2-needs-key');

  await page.fill('#add-url-key', key);
  await page.click('.add-url__key button');
  await page.waitForSelector('.add-url__status--good');
  await scene('3-added');

  await page.click('.search-input');
  await page.keyboard.type(sceneQuery.slice(0, 5), { delay: 60 });
  await page.waitForSelector('[role="listbox"] [role="option"]');
  await scene('4-suggestions');

  await page.keyboard.type(sceneQuery.slice(5), { delay: 60 });
  await page.keyboard.press('Enter');
  await page.waitForSelector('.result');
  await scene('5-results');

  await page.click('.result .result__why summary');
  await page.waitForTimeout(300);
  await scene('6-why-this-result');

  await browser.close();
})();
