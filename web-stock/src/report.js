// Звіт 1С «Ведомость по партиям товаров на складах» → залишки мережі.
// Той самий розбір і ті самі захисти, що в застосунку (app/.../data/NetworkStock.kt, StockFileParser.kt,
// StoreMatcher.kt) — змінюючи щось тут, зміни й там.
import { readXlsx, StockFileError } from './xlsx.js';

export { StockFileError };

/** «назва, КОД, артикул» — ділимо з кінця: у назві бувають коми, артикул може бути порожнім. */
const NAME_CODE_ARTICLE = /^(.*), ([A-Z]{3}\d{6})(?:, ?(.*))?$/s;
const STORE = /^\s*Ч[ОO]\s*(\d{1,4})(?!\d)\s*(.*)$/is;
const QTY = ['остат', 'колич', 'кільк', 'залиш'];
/** Слова, з яких починається назва торгового центру, а не місто. */
const MALL = new Set(['ТЦ', 'ТРЦ', 'ТД', 'ТРК', 'ТК', 'ТРЦ.', 'ТЦ.']);

/** Ліміти полів, як у firestore.rules і NetworkStockRepository.toDoc. */
export const LIMITS = { name: 300, code: 40, article: 100 };

/** «ЧО 22 Київ (ТЦ Ocean Plaza)» → {code: "22", name, city: "Київ"}; не склад — null. */
export function store(text) {
  const m = STORE.exec(text.trim());
  if (!m) return null;
  const rest = m[2].trim();
  // «Біла Церква (ТЦ …)» → «Біла Церква»; без дужок: «Малехів ТЦ Обрій» → «Малехів», «Умань LOMO» → «Умань».
  const words = [];
  for (const w of rest.split('(')[0].trim().split(/\s+/)) {
    if (MALL.has(w.toUpperCase()) || /^[A-Za-z0-9]*$/.test(w)) break;
    words.push(w);
  }
  const city = words.join(' ').replace(/[,.\-–]+$/, '').trim();
  return { code: m[1], name: text.trim().replace(/\s+/g, ' '), city };
}

/** Число з клітинки: «1 234,5» → 1234.5; не число — null. */
export function number(s) {
  if (s == null) return null;
  const t = s.replace(/[\s ]/g, '').replaceAll(',', '.');
  if (!t || !/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(t)) return null;
  return parseFloat(t);
}

/** «4.823077601357E12» → «4823077601357» (як BigDecimal.toPlainString), інакше — як є. */
function plain(t) {
  const m = /^([+-]?)(\d*)(?:\.(\d*))?[eE]([+-]?\d+)$/.exec(t);
  if (!m) return t;
  const [, sign, int, frac = '', expS] = m;
  let digits = int + frac;
  let point = int.length + parseInt(expS, 10);
  if (point <= 0) { digits = '0'.repeat(1 - point) + digits; point = 1; }
  if (point > digits.length) digits += '0'.repeat(point - digits.length);
  let i = digits.slice(0, point).replace(/^0+(?=\d)/, '');
  const f = digits.slice(point).replace(/0+$/, '');
  return sign + i + (f ? '.' + f : '');
}

export function cleanBarcode(s) {
  const t = (s ?? '').trim();
  if (!t) return null;
  const p = /e/i.test(t) ? plain(t) : t;
  const d = p.replace(/\D/g, '');
  return d || null;
}

/**
 * Розбирає звіт. Повертає {stores: [{code, name, city}], items: Map<штрихкод, {name, code, article, q}>, noBarcode}.
 * q — {«код магазину»: кількість}, лише > 0.
 */
export async function parseReport(buf, onProgress = () => {}) {
  let nameCol = -1;
  let codeCol = null;
  let articleCol = null;
  let stores = [];
  let productsStarted = false;
  let noBarcode = 0;
  const items = new Map();

  await readXlsx(buf, (_, row) => {
    if (nameCol < 0) {
      // Шапка — рядок з «Номенклатура» і складами «ЧО NN» (у заголовку звіту теж буває «Номенклатура»).
      const i = row.findIndex((t) => t.toLowerCase().includes('номенклатур'));
      if (i < 0) return;
      const found = [];
      row.forEach((t, c) => { const s = store(t); if (s) found.push({ store: s, from: c, barcode: c, qty: c + 1 }); });
      if (!found.length) return;
      nameCol = i;
      stores = found;
      const cc = row.findIndex((t) => t.trim().toLowerCase() === 'код');
      const ac = row.findIndex((t) => t.trim().toLowerCase() === 'артикул');
      codeCol = cc >= 0 ? cc : null;
      articleCol = ac >= 0 ? ac : null;
      return;
    }
    // Підшапка: «Штрих код | Конечный остаток», «Количество» — уточнюємо колонки складів.
    if (!productsStarted && subHeader(row, stores)) return;
    const name = (row[nameCol] ?? '').trim();
    if (!name || isTotal(row, nameCol)) return;
    productsStarted = true;

    let barcode = null;
    for (const s of stores) { const b = row[s.barcode] != null ? cleanBarcode(row[s.barcode]) : null; if (b) { barcode = b; break; } }
    if (!barcode) { noBarcode++; return; }

    let title = name; let code = ''; let article = '';
    if (codeCol != null || articleCol != null) {
      code = cell(row, codeCol); article = cell(row, articleCol);
    } else {
      const m = NAME_CODE_ARTICLE.exec(name);
      if (m) { title = m[1].trim(); code = m[2]; article = (m[3] ?? '').trim(); }
    }
    const q = {};
    for (const s of stores) {
      const v = number(row[s.qty]);
      if (v == null) continue;
      if (v > 0 && Number.isFinite(v)) q[s.store.code] = (q[s.store.code] ?? 0) + v;
    }
    const prev = items.get(barcode);
    if (prev) prev.q = sum(prev.q, q);
    // Порожня кількість у всіх складах — товару немає ніде: не пишемо.
    else if (Object.keys(q).length) items.set(barcode, { name: title, code, article, q });
  }, onProgress);

  if (nameCol < 0) throw new StockFileError('Не знайшли шапку звіту: потрібна колонка «Номенклатура, Код, Артикул».');
  if (!stores.length) throw new StockFileError('У шапці звіту немає складів «ЧО NN …».');
  const seen = new Set();
  const uniq = stores.map((s) => s.store).filter((s) => !seen.has(s.code) && seen.add(s.code));
  return { stores: uniq, items, noBarcode };
}

function subHeader(row, stores) {
  let hit = false;
  stores.forEach((s, k) => {
    const until = stores[k + 1]?.from ?? s.from + 2;
    for (let c = s.from; c < Math.min(until, row.length); c++) {
      const t = row[c].toLowerCase();
      if (t.includes('штрих')) { s.barcode = c; hit = true; } else if (QTY.some((w) => t.includes(w))) { s.qty = c; hit = true; }
    }
  });
  return hit;
}

function cell(row, col) { return col == null ? '' : (row[col] ?? '').trim(); }

function isTotal(row, nameCol) {
  for (let i = 0; i <= nameCol; i++) if ((row[i] ?? '').trim().toLowerCase().startsWith('итог')) return true;
  return false;
}

function sum(a, b) {
  const out = { ...a };
  for (const [k, v] of Object.entries(b)) out[k] = (out[k] ?? 0) + v;
  return out;
}

/** Обрізає поля до лімітів Firestore — щоб порівнювати з тим, що справді записано. */
export function clamp(item) {
  return { name: item.name.slice(0, LIMITS.name), code: item.code.slice(0, LIMITS.code), article: item.article.slice(0, LIMITS.article), q: item.q };
}

export function sameItem(a, b) {
  if (!a || !b || a.name !== b.name || a.code !== b.code || a.article !== b.article) return false;
  const ka = Object.keys(a.q); const kb = Object.keys(b.q);
  if (ka.length !== kb.length) return false;
  return ka.every((k) => b.q[k] === a.q[k]);
}

function storesWithItems(items) {
  const n = new Map();
  for (const it of items) for (const [code, v] of Object.entries(it.q)) if (v > 0) n.set(code, (n.get(code) ?? 0) + 1);
  return n;
}

/**
 * Що писати після порівняння з тим, що вже лежить у Firestore ([old] — Map або null, якщо порожньо):
 * лише змінене; магазин, у якому у файлі 0 товарів, а раніше були, — лишаємо як був;
 * товарів менше половини від попереднього — [shrunk] (перепитати).
 */
export function plan(old, fresh) {
  const freshItems = new Map([...fresh.items].map(([bc, it]) => [bc, clamp(it)]));
  if (!old || old.size === 0) {
    return { items: freshItems, changed: [...freshItems.keys()].sort(), removed: [], keptStores: [], first: true, shrunk: false, previousItems: 0 };
  }
  const before = storesWithItems(old.values());
  const now = storesWithItems(freshItems.values());
  const kept = [...before.keys()].filter((c) => (now.get(c) ?? 0) === 0).sort();
  const items = new Map(freshItems);
  if (kept.length) {
    const keptSet = new Set(kept);
    for (const [bc, o] of old) {
      const keep = Object.fromEntries(Object.entries(o.q).filter(([k]) => keptSet.has(k)));
      if (!Object.keys(keep).length) continue;
      const cur = items.get(bc);
      items.set(bc, cur ? { ...cur, q: { ...cur.q, ...keep } } : { ...o, q: keep });
    }
  }
  const changed = [...items].filter(([bc, it]) => !sameItem(old.get(bc), it)).map(([bc]) => bc).sort();
  const removed = [...old.keys()].filter((bc) => !items.has(bc)).sort();
  return { items, changed, removed, keptStores: kept, first: false, shrunk: items.size < old.size / 2, previousItems: old.size };
}

/** Склади, яких ще немає в stores або в яких змінилися назва чи місто. */
export function storesToWrite(stores, known) {
  return stores.filter((s) => { const k = known.get(s.code); return !k || k.name !== s.name.slice(0, 200) || k.city !== s.city.slice(0, 100); });
}

// ---------- Склад 1С ↔ магазин (як StoreMatcher.kt) ----------

export const AUTO = 0.72;
export const MARGIN = 0.12;

const TR = {
  а: 'a', б: 'b', в: 'v', г: 'h', ґ: 'g', д: 'd', е: 'e', є: 'ie', ж: 'zh', з: 'z', и: 'y', і: 'i', ї: 'i', й: 'i', к: 'k', л: 'l',
  м: 'm', н: 'n', о: 'o', п: 'p', р: 'r', с: 's', т: 't', у: 'u', ф: 'f', х: 'kh', ц: 'ts', ч: 'ch', ш: 'sh', щ: 'shch', ь: '',
  ю: 'iu', я: 'ia', ы: 'y', э: 'e', ё: 'e', ъ: '',
};

function translit(s) {
  let out = '';
  for (const c of s) out += TR[c] ?? c;
  // Латиниця з сайту й транслітерація з 1С: «gagarin» / «hahahrin», «retroville» / «retrovil».
  return out.replaceAll('g', 'h').replaceAll('w', 'v').replaceAll('ll', 'l').replaceAll('x', 'ks').replaceAll('c', 'k').replaceAll('q', 'k');
}

export function norm(s) {
  return translit(s.toLowerCase().replace(/['`’ʼ]/g, ''))
    .replace(/["'`’ʼ«»“”.,;:()\-/]/g, ' ').replace(/\s+/g, ' ').trim();
}

const STOP = new Set([
  'тц', 'трц', 'тд', 'трк', 'тк', 'ту', 'тсц', 'вул', 'вулиця', 'просп', 'проспект', 'пр', 'пл', 'площа', 'бул', 'бульвар',
  'поверх', 'черга', 'друга', 'перша', 'третя', 'м', 'с', 'смт', 'буд', 'корпус', 'секція', 'мол', 'mall', 'center', 'центр',
  'ул', 'the', 'плаза', 'plaza', 'city', 'сіті', 'сити',
].map(norm));

export function tokens(s) {
  return norm(s).split(' ').filter((t) => t.length >= 3 && !STOP.has(t) && !/^\d+$/.test(t));
}

export function mallTokens(st) {
  const inParens = /\((.*)\)/.exec(st.name)?.[1];
  let rest = inParens;
  if (rest == null) {
    rest = st.name.replace(/^\s*Ч[ОO]\s*\d+\s*/i, '');
    if (st.city.trim()) rest = rest.replace(st.city, '');
  }
  const cityTokens = new Set(tokens(st.city));
  return tokens(rest).filter((t) => !cityTokens.has(t));
}

function lev(a, b) {
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    const cur = [i];
    for (let j = 1; j <= b.length; j++) cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    prev = cur;
  }
  return prev[b.length];
}

/** Схожість слів 0..1 (Левенштейн), префікс від 4 літер — теж збіг. */
export function sim(a, b) {
  if (a === b) return 1;
  if (Math.min(a.length, b.length) >= 4 && (a.startsWith(b) || b.startsWith(a))) return 0.9;
  return 1 - lev(a, b) / Math.max(a.length, b.length);
}

/** 0..1: назва ТЦ (вага 0,8) і місто (0,2); без назви ТЦ у складі — лише місто. shop = {id, address, region}. */
export function score(st, shop) {
  const mall = mallTokens(st);
  const addr = tokens(shop.address);
  const cityKey = norm(st.city);
  const city = cityKey && (norm(shop.region) === cityKey || addr.some((t) => sim(t, cityKey) >= 0.85)) ? 1 : 0;
  if (!mall.length) return city * 0.75;
  const name = mall.map((t) => (addr.length ? Math.max(...addr.map((a) => sim(t, a))) : 0)).reduce((x, y) => x + y, 0) / mall.length;
  return name * 0.8 + city * 0.2;
}

/** Для кожного складу: shopId, якщо впевнені, і кандидати (найкращі першими). */
export function match(stores, shops, alreadyLinked = new Set()) {
  const free = shops.filter((s) => !alreadyLinked.has(s.id));
  const scored = new Map(stores.map((st) => [st.code, free.map((sh) => [sh, score(st, sh)]).sort((a, b) => b[1] - a[1])]));
  const taken = new Set();
  const chosen = new Map();
  [...scored].flatMap(([code, list]) => list.map(([sh, s]) => [code, sh, s]))
    .sort((a, b) => b[2] - a[2])
    .forEach(([code, sh, s]) => {
      if (chosen.has(code) || taken.has(sh.id) || s < AUTO) return;
      const second = scored.get(code).find(([o]) => o.id !== sh.id && !taken.has(o.id))?.[1] ?? 0;
      if (s - second < MARGIN) return;
      chosen.set(code, [sh, s]);
      taken.add(sh.id);
    });
  return stores.map((st) => {
    const c = chosen.get(st.code);
    const list = scored.get(st.code);
    return {
      code: st.code,
      shopId: c?.[0].id ?? null,
      score: c?.[1] ?? (list[0]?.[1] ?? 0),
      candidates: list.map(([sh]) => sh).filter((sh) => !taken.has(sh.id) || sh.id === c?.[0].id).slice(0, 8),
    };
  });
}
