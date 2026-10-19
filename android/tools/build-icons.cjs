/* Android launcher assets from the existing OpenScript vector. Requires sharp. */
const fs = require('node:fs');
const path = require('node:path');
const sharp = require('sharp');
const root = path.resolve(__dirname, '../..');
const resources = path.join(root, 'android/app/src/main/res');
const branding = path.join(root, 'android/branding');
const source = fs.readFileSync(path.join(root, 'studio/static/studio/app-icon.svg'), 'utf8');
const shapes = [...source.matchAll(/<(path|rect|circle)\b([^>]*)\/>/g)].map(([, tag, raw]) => {
  const attributes = Object.fromEntries([...raw.matchAll(/([\w-]+)="([^"]*)"/g)].map(([, key, value]) => [key, value]));
  return { tag, attributes };
});
if (shapes.length !== 16 || shapes[0].attributes.fill !== '#0F172A') {
  throw new Error('Unexpected canonical icon structure; review the brand source first.');
}
const number = value => Number(Number(value).toFixed(4));
function pathData({ tag, attributes: a }) {
  if (tag === 'path') return a.d;
  if (tag === 'circle') {
    const x = number(a.cx), y = number(a.cy), r = number(a.r);
    return `M${number(x-r)},${y} a${r},${r} 0,1 0 ${number(2*r)},0 a${r},${r} 0,1 0 ${number(-2*r)},0 Z`;
  }
  const x = number(a.x), y = number(a.y), w = number(a.width), h = number(a.height), r = Math.min(number(a.rx || 0), w/2, h/2);
  return `M${number(x+r)},${y} H${number(x+w-r)} Q${number(x+w)},${y} ${number(x+w)},${number(y+r)} V${number(y+h-r)} Q${number(x+w)},${number(y+h)} ${number(x+w-r)},${number(y+h)} H${number(x+r)} Q${x},${number(y+h)} ${x},${number(y+h-r)} V${number(y+r)} Q${x},${y} ${number(x+r)},${y} Z`;
}
const mark = shapes.slice(1);
// Every original point remains inside Android's centered 66dp safe circle.
const transform = 'translate(54 54) scale(0.054) translate(-948.5 -928.5)';
const androidGroup = '<group android:translateX="54" android:translateY="54" android:scaleX="0.054" android:scaleY="0.054"><group android:translateX="-948.5" android:translateY="-928.5">';
const vector = body => `<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n${androidGroup}\n${body}\n</group></group>\n</vector>\n`;
const colorPaths = mark.map(shape => `<path android:fillColor="${shape.attributes.fill === 'white' ? '#FFFFFF' : shape.attributes.fill}" android:pathData="${pathData(shape)}" />`).join('\n');
const frame = mark.slice(0, 5).map(shape => `<path android:fillColor="#FFFFFF" android:pathData="${pathData(shape)}" />`).join('\n');
// Even-odd holes keep the script readable when Android tints the entire glyph.
const cutout = mark.slice(5).map(pathData).join(' ');
const monochrome = `${frame}\n<path android:fillColor="#FFFFFF" android:fillType="evenOdd" android:pathData="${cutout}" />`;
const foregroundSvg = `<g transform="${transform}">${mark.map(shape => `<path fill="${shape.attributes.fill}" d="${pathData(shape)}"/>`).join('')}</g>`;
const monoSvg = tint => `<g transform="${transform}">${mark.slice(0, 5).map(shape => `<path fill="${tint}" d="${pathData(shape)}"/>`).join('')}<path fill="${tint}" fill-rule="evenodd" d="${cutout}"/></g>`;
const svg = (size, content) => `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="18 18 72 72">${content}</svg>`;
const colored = size => svg(size, `<rect width="108" height="108" fill="#0F172A"/>${foregroundSvg}`);
const mask = (size, round) => Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}">${round ? `<circle cx="${size/2}" cy="${size/2}" r="${size/2}" fill="white"/>` : `<rect width="${size}" height="${size}" rx="${size*.23}" fill="white"/>`}</svg>`);
async function render(size, content, round) {
  let pipeline = sharp(Buffer.from(content));
  if (round !== undefined) pipeline = pipeline.composite([{ input: mask(size, round), blend: 'dest-in' }]);
  return pipeline.png().toBuffer();
}
(async () => {
  fs.mkdirSync(branding, { recursive: true });
  fs.writeFileSync(path.join(resources, 'drawable/ic_launcher_foreground.xml'), vector(colorPaths));
  fs.writeFileSync(path.join(resources, 'drawable/ic_launcher_monochrome.xml'), vector(monochrome));
  for (const [density, size] of Object.entries({ mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 })) {
    const directory = path.join(resources, `mipmap-${density}`);
    for (const [name, round] of [['ic_launcher', undefined], ['ic_launcher_round', true]]) {
      await sharp(await render(size, colored(size), round)).webp({ lossless: true }).toFile(path.join(directory, `${name}.webp`));
    }
  }
  await sharp(Buffer.from(source)).resize(512, 512).png().toFile(path.join(branding, 'icon-512.png'));
  const previews = [
    await render(240, colored(240), true),
    await render(240, colored(240), false),
    await render(240, svg(240, `<rect width="108" height="108" fill="#C7D9F1"/>${monoSvg('#26374E')}`), true),
  ];
  const labels = ['Circular', 'Adaptável', 'Tema do Android'];
  const cards = previews.map((bytes, index) => `<image x="${55+index*330}" y="60" width="240" height="240" href="data:image/png;base64,${bytes.toString('base64')}"/><text x="${175+index*330}" y="350" text-anchor="middle" fill="#E3E9F3" font-family="sans-serif" font-size="23">${labels[index]}</text>`).join('');
  await sharp(Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="1010" height="410"><rect width="1010" height="410" fill="#152039"/>${cards}</svg>`)).png().toFile(path.join(branding, 'icon-preview.png'));
  process.stdout.write('Android: color/monochrome vectors, 10 density assets, 512px icon and preview generated.\n');
})().catch(error => { process.stderr.write(error.message + '\n'); process.exitCode = 1; });
