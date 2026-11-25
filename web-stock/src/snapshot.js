// Знімок останнього завантаження в браузері (IndexedDB) — як last-stock.json.gz на телефоні:
// повторне завантаження порівнюємо з ним, а не читаємо ~22 000 документів Firestore (ліміт — 50 000 читань на добу).
// [mark] — meta/stock.updatedAt (мс) після нашого запису: інший збіг — хтось завантажував з іншого місця.

const DB = 'chudoskan-stock';
const STORE = 'snapshot';

function open() {
  return new Promise((resolve, reject) => {
    const r = indexedDB.open(DB, 1);
    r.onupgradeneeded = () => r.result.createObjectStore(STORE);
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}

function tx(mode, fn) {
  return open().then((db) => new Promise((resolve, reject) => {
    const t = db.transaction(STORE, mode);
    const req = fn(t.objectStore(STORE));
    t.oncomplete = () => { db.close(); resolve(req?.result); };
    t.onerror = () => { db.close(); reject(t.error); };
    t.onabort = () => { db.close(); reject(t.error); };
  }));
}

/** {mark, items: Map} або null (немає / зіпсований / браузер не дає IndexedDB). */
export async function loadSnapshot(key) {
  try {
    const v = await tx('readonly', (s) => s.get(key));
    if (!v || typeof v.mark !== 'number' || !Array.isArray(v.items)) return null;
    return { mark: v.mark, items: new Map(v.items) };
  } catch { return null; }
}

export async function saveSnapshot(key, mark, items) {
  try { await tx('readwrite', (s) => s.put({ mark, items: [...items] }, key)); } catch { /* без знімка — наступного разу прочитаємо Firestore */ }
}

export async function clearSnapshot(key) {
  try { await tx('readwrite', (s) => s.delete(key)); } catch { /* нічого */ }
}

/** Знімок годиться, якщо він є і записаний саме після останнього завантаження (як useSnapshot у застосунку). */
export function useSnapshot(snap, metaAt) {
  return !!snap && (metaAt == null || snap.mark === metaAt);
}
