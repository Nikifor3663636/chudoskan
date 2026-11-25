// Сторінка «Залишки мережі» для ПК: вхід stockAdmin → звіт 1С .xlsx → Firestore.
// Пише те саме й так само, як застосунок (NetworkStockRepository.upload); доступ вирішують firestore.rules.
import { initializeApp } from 'firebase/app';
import { getAuth, onAuthStateChanged, signInWithEmailAndPassword, signOut } from 'firebase/auth';
import {
  addDoc, collection, doc, documentId, getDoc, getDocs, getFirestore, limit, orderBy, query, serverTimestamp,
  setDoc, startAfter, writeBatch,
} from 'firebase/firestore';
import { match, parseReport, plan, storesToWrite, StockFileError } from './report.js';
import { loadSiteShops } from './siteShops.js';
import { loadSnapshot, saveSnapshot, useSnapshot } from './snapshot.js';

const BATCH = 450; // Firestore — до 500 операцій у пачці
const PAGE = 2000;
const FREE_WRITES = 20000;
// Firestore при вичерпаному ліміті чи без зв'язку не падає, а повторює запит без кінця — тому кожен запит обмежуємо.
const READ_TIMEOUT = 45_000;
const WRITE_TIMEOUT = 120_000;

class TimeoutError extends Error {
  constructor(what) { super(what); this.code = 'timeout'; this.what = what; }
}

/** Проміс, який не чекає вічно: за [ms] — TimeoutError з описом кроку. */
function timed(promise, ms, what) {
  let t;
  return Promise.race([
    promise,
    new Promise((_, reject) => { t = setTimeout(() => reject(new TimeoutError(what)), ms); }),
  ]).finally(() => clearTimeout(t));
}

const $ = (sel) => document.querySelector(sel);
const app = $('#app');
const fmt = (n) => new Intl.NumberFormat('uk-UA').format(n);
const when = (ts) => (ts ? ts.toDate().toLocaleString('uk-UA', { dateStyle: 'medium', timeStyle: 'short' }) : '—');
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const cfg = window.CHUDO_FIREBASE;
if (!cfg?.apiKey || !cfg?.projectId) {
  render(`<div class="card"><h2>Firebase не налаштовано</h2><p class="muted">Немає config.js із ключем проєкту. Його створює збірка сайту (pages.yml) з секрету GOOGLE_SERVICES_JSON.</p></div>`);
  throw new Error('no firebase config');
}
const fb = initializeApp({ apiKey: cfg.apiKey, projectId: cfg.projectId, authDomain: `${cfg.projectId}.firebaseapp.com` });
const auth = getAuth(fb);
const db = getFirestore(fb);
const SNAP_KEY = `stock:${cfg.projectId}`;

let me = null; // {uid, name}

function render(html) { app.innerHTML = html; }

// Лічильник секунд на екрані прогресу — видно, що сторінка працює, а не зависла.
let stepTitle = '';
let stepStart = 0;
setInterval(() => {
  const el = document.getElementById('elapsed');
  if (el) el.textContent = `${Math.round((Date.now() - stepStart) / 1000)} с`;
}, 1000);

function errorText(e) {
  if (e instanceof StockFileError) return e.message;
  if (e?.code === 'timeout') {
    return `Firestore не відповідає (${e.what}). Найчастіше — вичерпано безкоштовний добовий ліміт `
      + '(50 000 читань / 20 000 записів); він оновлюється щодня близько 10:00 за Києвом. Або пропав інтернет. '
      + 'Нічого не зламалось — спробуй пізніше.';
  }
  const code = e?.code ?? '';
  if (code.includes('invalid-credential') || code.includes('wrong-password') || code.includes('user-not-found') || code.includes('invalid-email')) return 'Невірний номер або пароль.';
  if (code.includes('too-many-requests')) return 'Забагато спроб. Спробуй за кілька хвилин.';
  if (code.includes('network-request-failed') || code.includes('unavailable')) return 'Немає зʼєднання з інтернетом.';
  if (code.includes('permission-denied')) return 'Немає прав на це (потрібен адмін залишків).';
  if (code.includes('resource-exhausted')) return 'Вичерпано добовий безкоштовний ліміт Firestore. Спробуй завтра.';
  if (e instanceof RangeError) return 'Забракло памʼяті для розбору файлу.';
  return e?.message || String(e);
}

// ---------- Вхід ----------

function loginView(message = '') {
  render(`
    <form class="card" id="login">
      <h2>Вхід</h2>
      <p class="muted">Той самий номер і пароль, що в застосунку. Завантажувати може лише адмін залишків.</p>
      <label>Телефон<div class="phone"><span>+380</span><input id="phone" inputmode="numeric" autocomplete="username" placeholder="96 123 45 67" required></div></label>
      <label>Пароль<input id="password" type="password" autocomplete="current-password" required></label>
      ${message ? `<p class="error">${esc(message)}</p>` : ''}
      <button class="primary" type="submit">Увійти</button>
    </form>`);
  $('#login').addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const digits = $('#phone').value.replace(/\D/g, '').slice(-9);
    if (digits.length !== 9) { loginView('Номер — 9 цифр після +380.'); return; }
    const btn = ev.submitter; btn.disabled = true; btn.textContent = 'Входжу…';
    try {
      // +380961234567 → 380961234567@tel.chudoostriv.app (як AuthRepository.loginFor)
      await signInWithEmailAndPassword(auth, `380${digits}@tel.chudoostriv.app`, $('#password').value);
    } catch (e) { loginView(errorText(e)); }
  });
}

onAuthStateChanged(auth, async (user) => {
  if (!user) { me = null; loginView(); return; }
  render('<div class="card"><p class="muted">Перевіряю права…</p></div>');
  try {
    const u = (await timed(getDoc(doc(db, 'users', user.uid)), READ_TIMEOUT, 'перевірка прав')).data() ?? {};
    const allowed = u.approved === true && (u.stockAdmin === true || u.superAdmin === true);
    if (!allowed) {
      render(`<div class="card"><h2>Немає прав</h2><p>Завантажувати залишки мережі може лише адмін залишків. Права видаються в консолі Firebase.</p><button id="out">Вийти</button></div>`);
      $('#out').onclick = () => signOut(auth);
      return;
    }
    me = { uid: user.uid, name: (u.name || 'Адмін').slice(0, 100) };
    await homeView();
  } catch (e) {
    render(`<div class="card"><p class="error">${esc(errorText(e))}</p><button id="out">Вийти</button></div>`);
    $('#out').onclick = () => signOut(auth);
  }
});

// ---------- Головна: стан і вибір файлу ----------

async function homeView(note = '') {
  const meta = (await timed(getDoc(doc(db, 'meta', 'stock')), READ_TIMEOUT, 'стан залишків').catch(() => null))?.data();
  render(`
    <div class="card">
      <div class="row between"><h2>Залишки мережі</h2><button class="link" id="out">Вийти (${esc(me.name)})</button></div>
      ${meta
        ? `<p><span class="dot ok"></span>Оновлено <b>${esc(when(meta.updatedAt))}</b>${meta.updatedBy ? `, ${esc(meta.updatedBy)}` : ''}</p>
           <p class="muted">${fmt(meta.items ?? 0)} товарів · ${fmt(meta.stores ?? 0)} магазинів</p>`
        : '<p><span class="dot warn"></span>Залишки ще не завантажувалися.</p>'}
      ${note}
    </div>
    <label class="drop" id="drop">
      <input type="file" id="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" hidden>
      <b>Перетягни сюди звіт 1С (.xlsx)</b>
      <span class="muted">або натисни, щоб вибрати файл</span>
      <small class="muted">«Ведомость по партиям товаров на складах», склади в колонках, період «по» — сьогодні.</small>
    </label>`);
  $('#out').onclick = () => signOut(auth);
  const input = $('#file');
  const drop = $('#drop');
  input.onchange = () => input.files[0] && start(input.files[0]);
  drop.ondragover = (e) => { e.preventDefault(); drop.classList.add('over'); };
  drop.ondragleave = () => drop.classList.remove('over');
  drop.ondrop = (e) => { e.preventDefault(); drop.classList.remove('over'); const f = e.dataTransfer.files[0]; if (f) start(f); };
}

function progressView(title, frac, detail = '') {
  if (title !== stepTitle) { stepTitle = title; stepStart = Date.now(); }
  const pct = Math.round((frac ?? 0) * 100);
  render(`<div class="card"><h2>${esc(title)}</h2>
    <div class="bar"><div style="width:${frac == null ? 100 : pct}%" class="${frac == null ? 'busy' : ''}"></div></div>
    <p class="muted">${frac == null ? '' : `${pct}%`} ${esc(detail)} · <span id="elapsed">${Math.round((Date.now() - stepStart) / 1000)} с</span></p></div>`);
}

function failView(e) {
  render(`<div class="card"><h2>Не вийшло</h2><p class="error">${esc(errorText(e))}</p><button class="primary" id="back">Назад</button></div>`);
  $('#back').onclick = () => homeView();
}

// ---------- Розбір і порівняння ----------

/** Що зараз лежить у stock/: читаємо сторінками, щоб не тримати тисячі знімків одночасно. */
async function readCurrentStock(onCount) {
  const out = new Map();
  let last = null;
  for (;;) {
    const q = last
      ? query(collection(db, 'stock'), orderBy(documentId()), startAfter(last), limit(PAGE))
      : query(collection(db, 'stock'), orderBy(documentId()), limit(PAGE));
    const snap = await timed(getDocs(q), READ_TIMEOUT, `читання залишків, ${fmt(out.size)} уже прочитано`);
    for (const d of snap.docs) {
      const x = d.data();
      out.set(d.id, { name: x.name ?? '', code: x.code ?? '', article: x.article ?? '', q: x.q ?? {} });
    }
    onCount(out.size);
    if (snap.size < PAGE) break;
    last = snap.docs[snap.docs.length - 1];
  }
  return out;
}

/**
 * З чим порівнювати файл: знімок у браузері, якщо після нього ніхто не завантажував (1 читання meta/stock),
 * інакше — усе stock/ (~22 000 читань).
 */
async function baseline(onCount) {
  const metaAt = (await timed(getDoc(doc(db, 'meta', 'stock')), READ_TIMEOUT, 'стан залишків')).get('updatedAt')?.toMillis() ?? null;
  const snap = await loadSnapshot(SNAP_KEY);
  if (useSnapshot(snap, metaAt)) return snap.items;
  return readCurrentStock(onCount);
}

/** Після запису: знімок = те, що тепер у stock/, з позначкою meta/stock.updatedAt. */
async function rememberUpload(items) {
  try {
    const at = (await timed(getDoc(doc(db, 'meta', 'stock')), READ_TIMEOUT, 'стан залишків')).get('updatedAt')?.toMillis();
    if (at != null) await saveSnapshot(SNAP_KEY, at, items);
  } catch { /* не вийшло — наступного разу просто прочитаємо Firestore */ }
}

async function start(file) {
  const startedAt = Date.now();
  try {
    if (!/\.xlsx$/i.test(file.name)) throw new StockFileError('Потрібен файл Excel .xlsx (у 1С: Файл → Сохранить как → .xlsx).');
    progressView('Читаю файл…', 0, file.name);
    const buf = new Uint8Array(await file.arrayBuffer());
    const fresh = await parseReport(buf, (p) => progressView('Розбираю звіт 1С…', p, file.name));
    progressView('Порівнюю з тим, що вже завантажено…', null);
    const [old, knownSnap] = await Promise.all([
      baseline((n) => progressView('Порівнюю з тим, що вже завантажено…', null, `прочитано ${fmt(n)} товарів`)),
      timed(getDocs(collection(db, 'stores')), READ_TIMEOUT, 'список складів'),
    ]);
    const known = new Map(knownSnap.docs.map((d) => [d.id, { name: d.get('name') ?? '', city: d.get('city') ?? '', shopId: d.get('shopId') || null }]));
    const p = plan(old, fresh);
    const pending = { file: file.name, fresh, plan: p, known, startedAt, autoLinked: 0 };

    const unknown = fresh.stores.filter((s) => !known.get(s.code)?.shopId);
    if (!unknown.length) { confirmView(pending); return; }
    progressView('Звіряю склади 1С з магазинами…', 0, 'список магазинів із сайту');
    // Магазини з сайту (як у застосунку — з тими самими id) + ті, що вже є у Firestore, але зникли з сайту.
    const [site, fsShops] = await Promise.all([
      loadSiteShops((p) => progressView('Звіряю склади 1С з магазинами…', p, 'список магазинів із сайту')),
      getDocs(collection(db, 'shops')).then((s) => s.docs.map((d) => ({ id: d.id, address: d.get('address') ?? '', region: d.get('region') ?? '' }))),
    ]);
    const byId = new Map(site.map((s) => [s.id, s]));
    for (const s of fsShops) if (!byId.has(s.id) && s.address) byId.set(s.id, s);
    const shops = [...byId.values()]
      .sort((a, b) => a.region.localeCompare(b.region, 'uk') || a.address.localeCompare(b.address, 'uk'));
    const linked = new Set([...known.values()].map((k) => k.shopId).filter(Boolean));
    const matches = match(unknown, shops, linked);
    for (const m of matches.filter((x) => x.shopId)) {
      await link(m.code, m.shopId);
      const st = unknown.find((s) => s.code === m.code);
      known.set(m.code, { ...(known.get(m.code) ?? { name: st.name, city: st.city }), shopId: m.shopId });
      pending.autoLinked++;
    }
    const rest = unknown.filter((s) => !matches.find((m) => m.code === s.code)?.shopId);
    if (!rest.length) confirmView(pending);
    else linkView(pending, rest, shops, new Map(matches.map((m) => [m.code, m.candidates])));
  } catch (e) { console.error(e); failView(e); }
}

/** «Який це магазин?»: склад 1С ↔ магазин застосунку (stores/{код}.shopId і shops/{id}.code1C). */
async function link(code, shopId) {
  const b = writeBatch(db);
  b.set(doc(db, 'stores', code), { shopId }, { merge: true });
  b.set(doc(db, 'shops', shopId), { code1C: code }, { merge: true });
  await timed(b.commit(), WRITE_TIMEOUT, 'привʼязка складу');
}

function linkView(pending, rest, shops, candidates) {
  const label = (s) => `${s.region ? s.region + ' — ' : ''}${s.address || s.id}`;
  const options = (code) => {
    const best = candidates.get(code) ?? [];
    const others = shops.filter((s) => !best.some((b) => b.id === s.id));
    return `<option value="">— пропустити (залишки все одно завантажаться) —</option>
      ${best.length ? `<optgroup label="Найімовірніші">${best.map((s) => `<option value="${esc(s.id)}">${esc(label(s))}</option>`).join('')}</optgroup>` : ''}
      <optgroup label="Усі магазини">${others.map((s) => `<option value="${esc(s.id)}">${esc(label(s))}</option>`).join('')}</optgroup>`;
  };
  render(`
    <form class="card" id="links">
      <h2>Який це магазин?</h2>
      <p class="muted">Ці склади 1С ще не привʼязані до магазинів застосунку${pending.autoLinked ? ` (ще ${pending.autoLinked} привʼязано автоматично)` : ''}.
        Без привʼязки продавці цього магазину не бачитимуть «У вашому магазині».</p>
      ${rest.map((s) => `<label><b>${esc(s.name)}</b><select data-code="${esc(s.code)}">${options(s.code)}</select></label>`).join('')}
      <button class="primary" type="submit">Далі</button>
    </form>`);
  $('#links').addEventListener('submit', async (ev) => {
    ev.preventDefault();
    ev.submitter.disabled = true;
    try {
      for (const sel of document.querySelectorAll('select[data-code]')) if (sel.value) await link(sel.dataset.code, sel.value);
      confirmView(pending);
    } catch (e) { failView(e); }
  });
}

function confirmView(pending) {
  const p = pending.plan;
  const writes = p.changed.length + p.removed.length;
  const storeCount = new Set([...p.items.values()].flatMap((it) => Object.keys(it.q))).size;
  const warnings = [];
  if (p.first) warnings.push(`Перше завантаження: ~${fmt(writes)} записів.`);
  if (writes > FREE_WRITES) warnings.push(`Це більше за безкоштовний ліміт Firestore (${fmt(FREE_WRITES)} записів на добу) — частина може не записатися до завтра.`);
  if (p.shrunk) warnings.push(`У файлі ${fmt(p.items.size)} товарів — менше половини від попереднього (${fmt(p.previousItems)}). Можливо, це неповний звіт.`);
  if (p.keptStores.length) warnings.push(`Магазини без жодного товару у файлі лишаються як були: ЧО ${p.keptStores.join(', ЧО ')}.`);
  render(`
    <div class="card">
      <h2>Завантажити залишки?</h2>
      <p class="muted">${esc(pending.file)}</p>
      <div class="stats">
        <div><b>${fmt(storeCount)}</b><span>магазинів</span></div>
        <div><b>${fmt(p.items.size)}</b><span>товарів</span></div>
        <div><b>${fmt(p.changed.length)}</b><span>змінено</span></div>
        <div><b>${fmt(p.removed.length)}</b><span>видалено</span></div>
      </div>
      ${pending.fresh.noBarcode ? `<p class="muted">Рядків без штрихкоду (пропущено): ${fmt(pending.fresh.noBarcode)}.</p>` : ''}
      ${warnings.map((w) => `<p class="warn">${esc(w)}</p>`).join('')}
      ${writes === 0 ? '<p><span class="dot ok"></span>Нічого не змінилося з минулого завантаження — записувати нічого.</p>' : ''}
      <div class="row">
        <button class="primary" id="go">${writes === 0 ? 'Готово' : 'Завантажити'}</button>
        <button id="cancel">Скасувати</button>
      </div>
    </div>`);
  $('#cancel').onclick = () => homeView();
  $('#go').onclick = () => upload(pending);
}

// ---------- Запис (як NetworkStockRepository.upload) ----------

function toDoc(it) {
  return { q: it.q, name: it.name, code: it.code, article: it.article, updatedAt: serverTimestamp() };
}

async function upload(pending) {
  const { plan: p, fresh, known, startedAt } = pending;
  const total = p.changed.length + p.removed.length;
  try {
    progressView('Завантажую…', 0, `0 з ${fmt(total)}`);
    // Серверний час кінця попереднього завантаження — «змінені з минулого завантаження» для пакетного друку цінників.
    const previousEnd = total > 0
      ? (await timed(getDoc(doc(db, 'meta', 'stock')), READ_TIMEOUT, 'стан залишків').catch(() => null))?.get('updatedAt') ?? null
      : null;

    const sw = storesToWrite(fresh.stores, known);
    if (sw.length) {
      const b = writeBatch(db);
      for (const s of sw) b.set(doc(db, 'stores', s.code), { name: s.name.slice(0, 200), city: s.city.slice(0, 100) }, { merge: true });
      await timed(b.commit(), WRITE_TIMEOUT, 'запис складів');
    }

    const ops = [...p.changed.map((bc) => [bc, p.items.get(bc)]), ...p.removed.map((bc) => [bc, null])];
    let done = 0;
    for (let i = 0; i < ops.length; i += BATCH) {
      const b = writeBatch(db);
      for (const [bc, it] of ops.slice(i, i + BATCH)) {
        const ref = doc(db, 'stock', bc);
        if (it) b.set(ref, toDoc(it)); else b.delete(ref);
      }
      await timed(b.commit(), WRITE_TIMEOUT, `запис товарів, ${fmt(done)} з ${fmt(total)} уже записано`);
      done = Math.min(i + BATCH, ops.length);
      progressView('Завантажую…', done / ops.length, `${fmt(done)} з ${fmt(total)}`);
    }

    const storeCount = new Set([...p.items.values()].flatMap((it) => Object.keys(it.q))).size;
    const seconds = Math.round((Date.now() - startedAt) / 1000);
    if (total > 0) {
      const meta = { updatedAt: serverTimestamp(), updatedBy: me.name, items: p.items.size, stores: storeCount };
      if (previousEnd) meta.changedAfter = previousEnd;
      await timed(setDoc(doc(db, 'meta', 'stock'), meta), WRITE_TIMEOUT, 'підсумок завантаження');
      await timed(addDoc(collection(db, 'adminLog'), {
        by: me.uid, at: serverTimestamp(), action: 'stockImport',
        details: `магазинів ${storeCount} · товарів ${p.items.size} · змінено ${p.changed.length} · видалено ${p.removed.length} · ${seconds} с`,
      }), WRITE_TIMEOUT, 'журнал');
    }
    // Наступного разу порівнюємо з цим знімком — без ~22 000 читань.
    await rememberUpload(p.items);
    await homeView(`<p class="ok"><span class="dot ok"></span>Готово за ${seconds} с: змінено ${fmt(p.changed.length)}, видалено ${fmt(p.removed.length)}${pending.autoLinked ? `, складів привʼязано автоматично: ${pending.autoLinked}` : ''}.</p>`);
  } catch (e) { console.error(e); failView(e); }
}
