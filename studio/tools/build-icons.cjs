/* Render Open Script assets from the supplied vector, preserving its paths and colors.
 * Usage: node studio/tools/build-icons.cjs [path/to/original.svg]
 * Requires sharp. The canonical source is static/studio/logo-original.svg.
 */
const fs = require('node:fs');
const path = require('node:path');
const sharp = require('sharp');

const assets = path.resolve(__dirname, '../static/studio');
const sourceFile = path.join(assets, 'logo-original.svg');
const source = fs.readFileSync(process.argv[2] || sourceFile, 'utf8');
if (/<(?:script|foreignObject|image|use)\b|\bon\w+\s*=|\bhref\s*=|url\(/i.test(source)) {
  throw new Error('The logo source must contain only local vector shapes.');
}
const shapes = [...source.matchAll(/<(?:rect|path|circle)\b[^>]*\/>/g)].map(match => match[0]);
if (shapes.length !== 17 || !shapes[0].includes('#0F172A') || !shapes[1].includes('fill="white"')) {
  throw new Error('Unexpected logo source: recheck the wordmark and symbol before building.');
}
fs.mkdirSync(assets, { recursive: true });
if (process.argv[2]) fs.writeFileSync(sourceFile, source);

const mark = shapes.slice(2).join('\n');
const navy = '#0F172A';
const svg = (viewBox, content, width, height) => {
  const [x, y, w, h] = viewBox.split(' ').map(Number);
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="${viewBox}"><title>Open Script</title><rect x="${x}" y="${y}" width="${w}" height="${h}" fill="${navy}"/>\n${content}\n</svg>\n`;
};
const logo = svg('412 442 3149 1072', shapes.slice(1).join('\n'), 960, 327);
// Mark bounds: x 508..1389, y 498..1359. Preserve every source shape.
const icon = svg('390 370 1117 1117', mark, 512, 512);
// The complete mark fits inside the centered safe circle (80% of the width).
const maskable = svg('118.5 98.5 1660 1660', mark, 512, 512);
fs.writeFileSync(path.join(assets, 'logo.svg'), logo);
fs.writeFileSync(path.join(assets, 'app-icon.svg'), icon);
fs.writeFileSync(path.join(assets, 'favicon.svg'), icon);

const png = (vector, size) => sharp(Buffer.from(vector)).resize(size, size, { fit: 'fill' }).png().toBuffer();
const icoSizes = [16, 32, 48, 64];

(async () => {
  for (const [filename, size, vector] of [
    ['favicon-16x16.png', 16, icon],
    ['favicon-32x32.png', 32, icon],
    ['apple-touch-icon.png', 180, icon],
    ['icon-192.png', 192, icon],
    ['icon-512.png', 512, icon],
    ['icon-maskable-512.png', 512, maskable],
  ]) {
    await sharp(await png(vector, size)).toFile(path.join(assets, filename));
  }
  const images = [];
  for (const size of icoSizes) images.push(await png(icon, size));
  const header = Buffer.alloc(6 + images.length * 16);
  header.writeUInt16LE(1, 2);
  header.writeUInt16LE(images.length, 4);
  let offset = header.length;
  images.forEach((image, i) => {
    const entry = 6 + i * 16;
    header[entry] = icoSizes[i];
    header[entry + 1] = icoSizes[i];
    header.writeUInt16LE(1, entry + 4);
    header.writeUInt16LE(32, entry + 6);
    header.writeUInt32LE(image.length, entry + 8);
    header.writeUInt32LE(offset, entry + 12);
    offset += image.length;
  });
  fs.writeFileSync(path.join(assets, 'favicon.ico'), Buffer.concat([header, ...images]));

  const screenshots = path.resolve(__dirname, '../../tests/screenshots');
  fs.mkdirSync(screenshots, { recursive: true });
  await sharp(Buffer.from(source)).resize(1200).png().toFile(path.join(screenshots, 'brand-original.png'));
  const logoPreview = await sharp(Buffer.from(logo)).resize(900).png().toBuffer();
  const uri = image => `data:image/png;base64,${image.toString('base64')}`;
  let elements = `<rect width="1000" height="810" fill="#e8edf3"/><image x="50" y="30" width="900" height="306" href="${uri(logoPreview)}"/>`;
  const sample = async (vector, size, x, y, label) => {
    const image = await png(vector, size);
    elements += `<image x="${x}" y="${y}" width="${size}" height="${size}" href="${uri(image)}"/><text x="${x}" y="${y + size + 29}" fill="${navy}" font-family="sans-serif" font-size="18">${label}</text>`;
  };
  await sample(icon, 180, 50, 390, 'Apple 180');
  await sample(icon, 192, 280, 390, 'App 192');
  await sample(maskable, 192, 520, 390, 'Maskable');
  await sample(icon, 64, 760, 390, 'ICO 64');
  await sample(icon, 32, 870, 390, '32');
  await sample(icon, 16, 930, 390, '16');
  elements += '<text x="50" y="680" fill="#0F172A" font-family="sans-serif" font-size="20">Original vector paths · #F34D4D / #0F172A / white</text><text x="50" y="714" fill="#0F172A" font-family="sans-serif" font-size="18">The maskable icon keeps the complete symbol inside its 80% safe circle.</text>';
  await sharp(Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="810">${elements}</svg>`)).png().toFile(path.join(screenshots, 'brand-assets.png'));
  for (const name of ['favicon-16x16.png', 'favicon-32x32.png', 'apple-touch-icon.png', 'icon-192.png', 'icon-512.png', 'icon-maskable-512.png']) {
    const metadata = await sharp(path.join(assets, name)).metadata();
    process.stdout.write(`${name}: ${metadata.width}x${metadata.height}\n`);
  }
  process.stdout.write(`Preview: ${path.join(screenshots, 'brand-assets.png')}\n`);
})().catch(error => { console.error(error.message); process.exitCode = 1; });
