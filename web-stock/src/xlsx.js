// Потокове читання xlsx у браузері (і в Node для тестів): як app/.../data/Xlsx.kt.
// Файл читаємо в пам'ять стиснутим (~7 МБ), а аркуш (~100 МБ XML) розпаковуємо й розбираємо шматками.
// Частини zip порівнюються без урахування регістру: 1С пише `xl/SharedStrings.xml`.
import { Inflate } from 'fflate';

export class StockFileError extends Error {}

const SHARED_STRINGS = 'xl/sharedstrings.xml';
const SHEET_PREFIX = 'xl/worksheets/sheet';
const CHUNK = 256 * 1024;

/** Записи zip з центрального каталогу: ім'я, метод, розміри, зсув локального заголовка. */
export function zipEntries(buf) {
  const dv = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 22 - 65535); i--) {
    if (dv.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new StockFileError('Це не файл Excel (.xlsx): не знайшли каталог zip.');
  const count = dv.getUint16(eocd + 10, true);
  let p = dv.getUint32(eocd + 16, true);
  const dec = new TextDecoder();
  const out = [];
  for (let k = 0; k < count; k++) {
    if (dv.getUint32(p, true) !== 0x02014b50) throw new StockFileError('Пошкоджений файл Excel (каталог zip).');
    const method = dv.getUint16(p + 10, true);
    const compSize = dv.getUint32(p + 20, true);
    const size = dv.getUint32(p + 24, true);
    const nameLen = dv.getUint16(p + 28, true);
    const extraLen = dv.getUint16(p + 30, true);
    const commentLen = dv.getUint16(p + 32, true);
    const local = dv.getUint32(p + 42, true);
    const name = dec.decode(buf.subarray(p + 46, p + 46 + nameLen));
    out.push({ name, method, compSize, size, local });
    p += 46 + nameLen + extraLen + commentLen;
  }
  return out;
}

function entryData(buf, e) {
  const dv = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
  if (dv.getUint32(e.local, true) !== 0x04034b50) throw new StockFileError('Пошкоджений файл Excel (заголовок zip).');
  const start = e.local + 30 + dv.getUint16(e.local + 26, true) + dv.getUint16(e.local + 28, true);
  return buf.subarray(start, start + e.compSize);
}

const tick = () => new Promise((r) => setTimeout(r, 0));

/**
 * Розпаковує запис шматками й віддає текст у [onText]. [onBytes] — скільки стиснутих байтів уже оброблено.
 * Між шматками віддаємо керування, щоб сторінка не «зависала».
 */
async function streamEntry(buf, e, onText, onBytes = () => {}) {
  const data = entryData(buf, e);
  const dec = new TextDecoder('utf-8'); // мітку UTF-8 (BOM), яку пише 1С, TextDecoder прибирає сам
  if (e.method === 0) {
    for (let i = 0; i < data.length; i += CHUNK) {
      onText(dec.decode(data.subarray(i, i + CHUNK), { stream: true }));
      onBytes(Math.min(i + CHUNK, data.length));
      await tick();
    }
  } else if (e.method === 8) {
    const inf = new Inflate((chunk) => onText(dec.decode(chunk, { stream: true })));
    for (let i = 0; i < data.length; i += CHUNK) {
      const end = Math.min(i + CHUNK, data.length);
      inf.push(data.subarray(i, end), end === data.length);
      onBytes(end);
      await tick();
    }
  } else {
    throw new StockFileError(`Непідтримуване стиснення в Excel (метод ${e.method}).`);
  }
  onText(dec.decode());
}

const ENT = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };
export function unescapeXml(s) {
  if (s.indexOf('&') < 0) return s;
  return s.replace(/&(#x[0-9a-fA-F]+|#[0-9]+|[a-z]+);/g, (m, g) => {
    if (g[0] === '#') {
      const n = g[1] === 'x' ? parseInt(g.slice(2), 16) : parseInt(g.slice(1), 10);
      return Number.isFinite(n) ? String.fromCodePoint(n) : m;
    }
    return ENT[g] ?? m;
  });
}

/** Мінімальний потоковий розбір XML: початок/кінець тегу й текст. Простори імен відкидаємо (x:row → row). */
export function xmlStream({ start, end, text }) {
  let buf = '';
  return (chunk) => {
    buf += chunk;
    let i = 0;
    for (;;) {
      const lt = buf.indexOf('<', i);
      if (lt < 0) { if (i < buf.length) { text(unescapeXml(buf.slice(i))); } i = buf.length; break; }
      if (lt > i) text(unescapeXml(buf.slice(i, lt)));
      if (buf.startsWith('<![CDATA[', lt)) {
        const close = buf.indexOf(']]>', lt + 9);
        if (close < 0) { i = lt; break; }
        text(buf.slice(lt + 9, close));
        i = close + 3;
        continue;
      }
      if (buf.startsWith('<!--', lt)) {
        const close = buf.indexOf('-->', lt + 4);
        if (close < 0) { i = lt; break; }
        i = close + 3;
        continue;
      }
      const gt = buf.indexOf('>', lt + 1);
      if (gt < 0) { i = lt; break; }
      const body = buf.slice(lt + 1, gt);
      i = gt + 1;
      const c0 = body.charCodeAt(0);
      if (c0 === 63 /* ? */ || c0 === 33 /* ! */) continue;
      if (c0 === 47 /* / */) { end(local(body.slice(1).trim())); continue; }
      const self = body.endsWith('/');
      const inner = self ? body.slice(0, -1) : body;
      const sp = inner.search(/[\s]/);
      const name = local(sp < 0 ? inner : inner.slice(0, sp));
      start(name, sp < 0 ? '' : inner.slice(sp));
      if (self) end(name);
    }
    buf = buf.slice(i);
  };
}

function local(n) { const k = n.indexOf(':'); return k < 0 ? n : n.slice(k + 1); }

function attr(attrs, name) {
  const m = new RegExp(`(?:^|\\s)${name}\\s*=\\s*"([^"]*)"`).exec(attrs) ?? new RegExp(`(?:^|\\s)${name}\\s*=\\s*'([^']*)'`).exec(attrs);
  return m ? unescapeXml(m[1]) : null;
}

/** "AB12" → 27. */
export function columnIndex(ref) {
  let n = 0;
  for (const ch of ref) {
    const c = ch.toUpperCase().charCodeAt(0);
    if (c < 65 || c > 90) break;
    n = n * 26 + (c - 64);
  }
  return n - 1;
}

/**
 * Читає перший аркуш: [onRow] отримує номер рядка (з 1) і клітинки з порожніми рядками на місці пропусків.
 * [onProgress] — 0..1 за стиснутими байтами аркуша.
 */
export async function readXlsx(buf, onRow, onProgress = () => {}) {
  const entries = zipEntries(buf);
  let sheet = null;
  let sheetNo = Infinity;
  let shared = null;
  for (const e of entries) {
    const lower = e.name.toLowerCase();
    if (lower === SHARED_STRINGS) shared = e;
    else if (lower.startsWith(SHEET_PREFIX) && lower.endsWith('.xml')) {
      const n = parseInt(lower.slice(SHEET_PREFIX.length, -4), 10);
      const no = Number.isFinite(n) ? n : 999;
      if (sheet == null || no < sheetNo) { sheet = e; sheetNo = no; }
    }
  }
  if (!sheet) throw new StockFileError('У файлі Excel немає аркушів.');

  const strings = [];
  if (shared) {
    let sb = '';
    let inT = false;
    let inRph = false; // фонетичні підказки — не текст клітинки
    await streamEntry(buf, shared, xmlStream({
      start: (n) => { if (n === 'si') sb = ''; else if (n === 't') inT = true; else if (n === 'rPh') inRph = true; },
      end: (n) => { if (n === 'si') strings.push(sb); else if (n === 't') inT = false; else if (n === 'rPh') inRph = false; },
      text: (t) => { if (inT && !inRph) sb += t; },
    }));
  }

  let row = null;
  let rowNo = 0;
  let col = 0;
  let type = null;
  let value = '';
  let inValue = false;
  const total = sheet.compSize || 1;
  await streamEntry(buf, sheet, xmlStream({
    start: (n, a) => {
      if (n === 'row') {
        const r = parseInt(attr(a, 'r') ?? '', 10);
        rowNo = Number.isFinite(r) ? r : rowNo + 1;
        row = [];
      } else if (n === 'c') {
        const r = attr(a, 'r');
        col = r != null ? columnIndex(r) : (row?.length ?? 0);
        type = attr(a, 't');
        value = '';
      } else if (n === 'v' || n === 't') inValue = true;
    },
    end: (n) => {
      if (n === 'v' || n === 't') inValue = false;
      else if (n === 'c' && row) {
        const txt = type === 's' ? (strings[parseInt(value.trim(), 10)] ?? '') : value;
        while (row.length < col) row.push('');
        if (row.length === col) row.push(txt); else row[col] = txt;
      } else if (n === 'row') { if (row) onRow(rowNo, row); row = null; }
    },
    text: (t) => { if (inValue) value += t; },
  }), (b) => onProgress(Math.min(1, b / total)));
  onProgress(1);
}
