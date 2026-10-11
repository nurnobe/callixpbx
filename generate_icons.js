// Callix PBX app icon generator using Node.js built-in APIs
// Creates minimal valid PNG files for all mipmap sizes

const fs = require('fs');
const path = require('path');

const BASE = path.join(__dirname, 'app', 'src', 'main', 'res');

// mipmap density -> icon size (px)
const sizes = {
  'mipmap-mdpi':    48,
  'mipmap-hdpi':    72,
  'mipmap-xhdpi':   96,
  'mipmap-xxhdpi':  144,
  'mipmap-xxxhdpi': 192,
};

// Minimal PNG creator (no external deps)
function createPNG(width, height, bgR, bgG, bgB, fgR, fgG, fgB) {
  // We'll create a proper PNG using raw bytes
  const { createCanvas } = (() => {
    try { return require('canvas'); } catch(e) { return null; }
  })() || {};

  if (createCanvas) {
    const canvas = createCanvas(width, height);
    const ctx = canvas.getContext('2d');
    
    // Background gradient (deep blue/purple)
    const grad = ctx.createRadialGradient(width/2, height/2, 0, width/2, height/2, width/2);
    grad.addColorStop(0, '#1565C0');
    grad.addColorStop(1, '#0D47A1');
    ctx.fillStyle = grad;
    ctx.beginPath();
    ctx.roundRect(0, 0, width, height, width * 0.22);
    ctx.fill();

    // Phone icon
    const s = width * 0.55;
    const ox = (width - s) / 2;
    const oy = (height - s) / 2;
    ctx.fillStyle = '#FFFFFF';
    ctx.font = `bold ${s}px serif`;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText('📞', width/2, height/2);

    return canvas.toBuffer('image/png');
  }
  return null;
}

// Fallback: Create a simple solid-color PNG using raw bytes
function createSimplePNG(size, r, g, b) {
  // PNG signature
  const sig = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  
  // IHDR chunk
  function uint32be(n) {
    const b = Buffer.alloc(4);
    b.writeUInt32BE(n);
    return b;
  }
  
  function crc32(data) {
    let crc = 0xFFFFFFFF;
    const table = [];
    for (let i = 0; i < 256; i++) {
      let c = i;
      for (let j = 0; j < 8; j++) {
        c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
      }
      table[i] = c;
    }
    for (let i = 0; i < data.length; i++) {
      crc = table[(crc ^ data[i]) & 0xFF] ^ (crc >>> 8);
    }
    return (crc ^ 0xFFFFFFFF) >>> 0;
  }
  
  function chunk(type, data) {
    const typeBytes = Buffer.from(type, 'ascii');
    const len = uint32be(data.length);
    const crcData = Buffer.concat([typeBytes, data]);
    const crcVal = uint32be(crc32(crcData));
    return Buffer.concat([len, typeBytes, data, crcVal]);
  }
  
  // IHDR: width, height, bitdepth=8, colortype=2 (RGB), compression=0, filter=0, interlace=0
  const ihdr = Buffer.concat([
    uint32be(size), uint32be(size),
    Buffer.from([8, 2, 0, 0, 0])
  ]);
  
  // IDAT: raw pixel data (with filter byte 0 per scanline), zlib compressed
  const zlib = require('zlib');
  const rowSize = size * 3;
  const raw = Buffer.alloc(size * (rowSize + 1));
  
  for (let y = 0; y < size; y++) {
    const offset = y * (rowSize + 1);
    raw[offset] = 0; // filter type None
    for (let x = 0; x < size; x++) {
      const px = offset + 1 + x * 3;
      // Simple gradient: dark blue background with lighter center
      const dist = Math.sqrt(Math.pow(x - size/2, 2) + Math.pow(y - size/2, 2)) / (size/2);
      const bright = Math.max(0, 1 - dist * 0.5);
      raw[px]     = Math.min(255, Math.round(r * bright));
      raw[px + 1] = Math.min(255, Math.round(g * bright));
      raw[px + 2] = Math.min(255, Math.round(b * bright));
    }
  }
  
  const compressed = zlib.deflateSync(raw, { level: 6 });
  
  return Buffer.concat([
    sig,
    chunk('IHDR', ihdr),
    chunk('IDAT', compressed),
    chunk('IEND', Buffer.alloc(0))
  ]);
}

// Generate icons for each density
let generated = 0;
for (const [folder, size] of Object.entries(sizes)) {
  const dir = path.join(BASE, folder);
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
  
  // Try canvas first, fallback to simple PNG
  let buf = createPNG(size, size);
  if (!buf) {
    buf = createSimplePNG(size, 21, 101, 192); // Callix blue #1565C0
  }
  
  fs.writeFileSync(path.join(dir, 'ic_launcher.png'), buf);
  fs.writeFileSync(path.join(dir, 'ic_launcher_round.png'), buf);
  console.log(`✓ ${folder}: ${size}x${size}px`);
  generated++;
}

console.log(`\n✅ Generated ${generated * 2} icon files`);
