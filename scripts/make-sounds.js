// Synthesises the rest-alert sounds the native notifications play while the phone is locked.
// Same patterns as the in-app beeps: 1 beep at 1:00, 2 at 1:30, 3 at 2:00, a long low tone at 3:00.
// Run: node scripts/make-sounds.js  (writes android/app/src/main/res/raw/rest_*.wav)
const fs = require('fs');
const path = require('path');

const RATE = 22050;
function render(notes, total) {
  const n = Math.round(total * RATE), out = new Float32Array(n);
  for (const { f, at, dur, wave = 'square', vol = 0.35 } of notes) {
    const s0 = Math.round(at * RATE), len = Math.round(dur * RATE);
    for (let i = 0; i < len && s0 + i < n; i++) {
      const t = i / RATE, ph = (f * t) % 1;
      const v = wave === 'square' ? (ph < 0.5 ? 1 : -1) : 2 * ph - 1;   // square or sawtooth
      const env = Math.min(1, i / (0.01 * RATE)) * Math.exp(-4 * t / dur);
      out[s0 + i] += v * env * vol;
    }
  }
  return out;
}
function wav(samples) {
  const b = Buffer.alloc(44 + samples.length * 2);
  b.write('RIFF', 0); b.writeUInt32LE(36 + samples.length * 2, 4); b.write('WAVE', 8);
  b.write('fmt ', 12); b.writeUInt32LE(16, 16); b.writeUInt16LE(1, 20); b.writeUInt16LE(1, 22);
  b.writeUInt32LE(RATE, 24); b.writeUInt32LE(RATE * 2, 28); b.writeUInt16LE(2, 32); b.writeUInt16LE(16, 34);
  b.write('data', 36); b.writeUInt32LE(samples.length * 2, 40);
  samples.forEach((v, i) => b.writeInt16LE(Math.max(-1, Math.min(1, v)) * 32767 | 0, 44 + i * 2));
  return b;
}

const sounds = {
  rest_one:   { total: 0.4, notes: [{ f: 880, at: 0, dur: 0.18 }] },
  rest_two:   { total: 0.6, notes: [{ f: 988, at: 0, dur: 0.14 }, { f: 988, at: 0.22, dur: 0.14 }] },
  rest_three: { total: 0.7, notes: [{ f: 1175, at: 0, dur: 0.12 }, { f: 1175, at: 0.18, dur: 0.12 }, { f: 1175, at: 0.36, dur: 0.12 }] },
  rest_long:  { total: 1.3, notes: [{ f: 660, at: 0, dur: 0.35, wave: 'saw' }, { f: 440, at: 0.4, dur: 0.7, wave: 'saw' }] },
};
const dir = path.join(__dirname, '..', 'android', 'app', 'src', 'main', 'res', 'raw');
fs.mkdirSync(dir, { recursive: true });
for (const [name, { notes, total }] of Object.entries(sounds)) fs.writeFileSync(path.join(dir, name + '.wav'), wav(render(notes, total)));
console.log('rest sounds written to', dir);
