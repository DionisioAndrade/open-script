const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

(async () => {
  const output = path.resolve('output/imagegen/reference-open-galaxy.png');
  const svg = fs.readFileSync('C:/Users/dio/Downloads/Open Galaxy/Open Galaxy/Light.svg');
  fs.mkdirSync(path.dirname(output), { recursive: true });
  const browser = await chromium.launch({
    executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    headless: true,
    args: ['--disable-gpu']
  });
  const page = await browser.newPage({ viewport: { width: 1600, height: 800 }, deviceScaleFactor: 1 });
  await page.setContent(`<html><head><style>html,body{margin:0;width:100%;height:100%;background:#0F172A}body{display:flex;align-items:center;justify-content:center}img{display:block;width:1400px;height:600px;object-fit:contain}</style></head><body><img src="data:image/svg+xml;base64,${svg.toString('base64')}"></body></html>`);
  await page.locator('img').evaluate(img => img.decode());
  await page.screenshot({ path: output });
  await browser.close();
  process.stdout.write(output + '\n');
})().catch(error => { console.error(error); process.exit(1); });
