// Сторінка «Залишки мережі» для ПК: вхід stockAdmin → звіт 1С .xlsx → Firestore.
// Пише те саме й так само, як застосунок (NetworkStockRepository.upload); доступ вирішують firestore.rules.
import { initializeApp } from 'firebase/app';
import { getAuth, onAuthStateChanged, signInWithEmailAndPassword, signOut } from 'firebase/auth';
import {
  addDoc, collection, doc, documentId, getDoc, getDocs, getFirestore, limit, orderBy, query, serverTimestamp,
  setDoc, startAfter, writeBatch,
} from 'firebase/firestore';
import { match, parseReport, plan, storesToWrite, StockFileError } from './report.js';
import { adminQuery, adminSections, canViewSection, PAGE_SIZE } from './admin.js';
import { highlightReport } from './highlights.js';
import { readXlsx } from './xlsx.js';
import { loadSiteShops } from './siteShops.js';
import { loadSnapshot, saveSnapshot, useSnapshot } from './snapshot.js';
import { asError, backend, commitStock, ensureRole, supabaseFor, uploadStock } from './supabase.js';

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
const when = (ts) => (ts ? (ts.toDate ? ts.toDate() : new Date(ts)).toLocaleString('uk-UA', { dateStyle: 'medium', timeStyle: 'short' }) : '—');
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
// BACKEND = supabase: ті самі дані в Postgres; Firebase лишається лише для входу.
const sb = backend() === 'supabase' ? supabaseFor(auth) : null;

/** {data, error} від supabase-js → data або виняток. */
function must(r) { if (r.error) throw asError(r.error); return r.data; }

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

function loginView(message = '', phone = '') {
  render(`
    <form class="card" id="login">
      <div class="login-heading">
        <h1>Вхід до адмінки</h1>
        <p class="muted">Використайте номер і пароль від застосунку ЧудоСкан.</p>
      </div>
      <label>Телефон<div class="phone"><span aria-hidden="true">+380</span><input id="phone" type="tel" aria-label="Телефон після +380" inputmode="numeric" autocomplete="username" placeholder="96 123 45 67" value="${esc(phone)}" required></div></label>
      <label>Пароль<input id="password" type="password" autocomplete="current-password" required></label>
      ${message ? `<p class="error" role="alert">${esc(message)}</p>` : ''}
      <button class="primary" type="submit">Увійти</button>
    </form>`);
  $('#login').addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const digits = $('#phone').value.replace(/\D/g, '').slice(-9);
    if (digits.length !== 9) { loginView('Введіть 9 цифр після +380.', digits); $('#phone').focus(); return; }
    const btn = ev.submitter; btn.disabled = true; btn.textContent = 'Входжу…';
    try {
      // +380961234567 → 380961234567@tel.chudoostriv.app (як AuthRepository.loginFor)
      await signInWithEmailAndPassword(auth, `380${digits}@tel.chudoostriv.app`, $('#password').value);
    } catch (e) { loginView(errorText(e), digits); $('#password').focus(); }
  });
}

onAuthStateChanged(auth, async (user) => {
  if (!user) { me = null; loginView(); return; }
  render('<div class="card"><p class="muted">Перевіряю права…</p></div>');
  try {
    let u;
    if (sb) {
      await timed(ensureRole(user), READ_TIMEOUT, 'підготовка входу');
      const r = must(await timed(sb.from('users').select('name, approved, admin, stock_admin, super_admin').eq('uid', user.uid).maybeSingle(), READ_TIMEOUT, 'перевірка прав')) ?? {};
      u = { name: r.name, approved: r.approved, admin: r.admin, stockAdmin: r.stock_admin, superAdmin: r.super_admin };
    } else {
      u = (await timed(getDoc(doc(db, 'users', user.uid)), READ_TIMEOUT, 'перевірка прав')).data() ?? {};
    }
    const canImport = u.stockAdmin === true || u.superAdmin === true;
    const shopAdmin = Boolean(sb) && u.admin === true;
    const allowed = u.approved === true && (canImport || shopAdmin);
    if (!allowed) {
      render(`<div class="card"><h2>Немає прав</h2><p>Цей розділ доступний адміністраторам магазину, залишків і мережі.</p><button id="out">Вийти</button></div>`);
      $('#out').onclick = () => signOut(auth);
      return;
    }
    me = { uid: user.uid, name: (u.name || 'Адмін').slice(0, 100), canImport, shopAdmin, superAdmin: u.superAdmin === true };
    if (canImport) await homeView();
    else await adminView('staff');
  } catch (e) {
    render(`<div class="card"><p class="error">${esc(errorText(e))}</p><button id="out">Вийти</button></div>`);
    $('#out').onclick = () => signOut(auth);
  }
});

// ---------- Головна: стан і вибір файлу ----------

const importKinds = [
  { key: 'stock', label: 'Залишки', path: 'M3 7h18v13H3z M3 7l3-4h12l3 4 M9 11h6' },
  { key: 'new', label: 'Новинки', path: 'M12 2l2.5 7.5L22 12l-7.5 2.5L12 22l-2.5-7.5L2 12l7.5-2.5z' },
  { key: 'promo', label: 'Акції', path: 'M7 17L17 7 M9 8a1.5 1.5 0 1 1-3 0 1.5 1.5 0 0 1 3 0 M18 16a1.5 1.5 0 1 1-3 0 1.5 1.5 0 0 1 3 0' },
  { key: 'history', label: 'Історія залишків', path: 'M3 12a9 9 0 1 0 3-6.7L3 8m0-5v5h5m4 0v5l3 2' },
];

const icon = (path) => `<svg class="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="${path}"/></svg>`;

function importWorkspace(active, heading, content) {
  const kinds = (sb ? importKinds : importKinds.slice(0, 1)).filter(({ key }) => canViewSection(key, me));
  const tables = sb ? Object.entries(adminSections).filter(([key]) => canViewSection(key, me)) : [];
  render(`<div class="workspace">
    <nav class="import-nav" aria-label="Розділи адміністратора">
      ${kinds.length ? `<div class="nav-section">
        <span class="nav-group">Імпорт з 1С</span>
        ${kinds.map(({ key, label, path }) =>
          `<button type="button" data-kind="${key}" ${key === active ? 'aria-current="page"' : ''}>${icon(path)}${label}</button>`).join('')}
      </div>` : ''}
      ${tables.length ? `<div class="nav-section">
        <span class="nav-group">Адміністрування</span>
        ${tables.map(([key, item]) => `<button type="button" data-kind="${key}" ${key === active ? 'aria-current="page"' : ''}>${item.title}</button>`).join('')}
      </div>` : ''}
    </nav>
    <section aria-labelledby="import-title">
      <div class="page-head">
        <h1 id="import-title">${esc(heading)}</h1>
        <div class="account"><span class="muted">${esc(me.name)}</span><button class="link" id="out">Вийти</button></div>
      </div>
      ${content}
    </section>
  </div>`);
  $('#out').onclick = () => signOut(auth);
  app.querySelectorAll('[data-kind]').forEach((button) => {
    button.onclick = () => {
      const kind = button.dataset.kind;
      if (kind === active) return;
      if (kind === 'stock') homeView();
      else if (kind === 'history') historyView();
      else if (adminSections[kind]) adminView(kind);
      else highlightsView(kind);
    };
  });
}

async function homeView(note = '') {
  const account = me;
  const meta = sb
    ? (await timed(sb.from('meta_stock').select('updatedAt:updated_at, updatedBy:updated_by, items, stores').maybeSingle(), READ_TIMEOUT, 'стан залишків').catch(() => null))?.data
    : (await timed(getDoc(doc(db, 'meta', 'stock')), READ_TIMEOUT, 'стан залишків').catch(() => null))?.data();
  if (!account || me !== account) return;
  importWorkspace('stock', 'Залишки мережі', `
    ${note ? `<div class="card" role="status">${note}</div>` : ''}
    <div class="card">
      <h2>Останній імпорт</h2>
      ${meta
        ? `<p><span class="dot ok"></span>Оновлено <b>${esc(when(meta.updatedAt))}</b>${meta.updatedBy ? `, ${esc(meta.updatedBy)}` : ''}</p>
           <div class="stats"><div><b>${fmt(meta.items ?? 0)}</b><span>Товари</span></div><div><b>${fmt(meta.stores ?? 0)}</b><span>Магазини</span></div></div>`
        : '<p class="muted">Залишки ще не завантажувалися.</p>'}
    </div>
    <div class="card"><h2>Завантажити звіт</h2>
      <div class="drop" id="drop">
        ${icon(importKinds[0].path)}
        <input type="file" id="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" hidden>
        <b>Перетягніть файл .xlsx сюди</b>
        <button type="button" class="primary" id="choose-file">Вибрати файл</button>
        <small>«Ведомость по партиям товаров на складах», склади в колонках, період «по» — сьогодні.</small>
      </div>
    </div>`);
  $('#choose-file').onclick = () => $('#file').click();
  const input = $('#file');
  const drop = $('#drop');
  input.onchange = () => input.files[0] && start(input.files[0]);
  drop.ondragover = (e) => { e.preventDefault(); drop.classList.add('over'); };
  drop.ondragleave = () => drop.classList.remove('over');
  drop.ondrop = (e) => { e.preventDefault(); drop.classList.remove('over'); const f = e.dataTransfer.files[0]; if (f) start(f); };
}

async function adminView(key, search = '', offset = 0) {
  const section = adminSections[key];
  if (!me || !canViewSection(key, me)) return;
  importWorkspace(key, section.title, `<div class="card">
    <form id="admin-search" class="row">
      <input type="search" aria-label="Пошук" name="search" value="${esc(search)}" placeholder="Пошук" maxlength="100">
      <button type="submit">Знайти</button>
    </form>
    <div id="admin-results" aria-live="polite"><p class="muted">Завантаження…</p></div>
  </div>`);
  const result = $('#admin-results');
  $('#admin-search').onsubmit = event => {
    event.preventDefault();
    adminView(key, new FormData(event.currentTarget).get('search').trim());
  };
  try {
    const rows = must(await timed(adminQuery(sb, key, search, offset), READ_TIMEOUT, section.title));
    if (!result.isConnected) return;
    const cell = (column, row) => {
      const value = column.get(row);
      if (value == null || value === '') return '—';
      if (['at', 'paired_at', 'requested_at', 'seen_at'].includes(column.key)) return esc(when(value));
      return esc(typeof value === 'number' ? fmt(value) : value);
    };
    const page = rows.slice(0, PAGE_SIZE);
    const manageDevices = key === 'deviceAccess';
    const columns = section.columns.filter(column => !manageDevices || column.key !== 'id');
    const deviceButtons = row => {
      if (row.status === 'pending') return '<button type="button" data-decision="approve">Підтвердити</button> <button type="button" data-decision="block">Заблокувати</button>';
      if (row.status === 'approved') return '<button type="button" data-decision="block">Заблокувати</button>';
      return '';
    };
    result.innerHTML = `${page.length ? `<div class="table-wrap" tabindex="0" role="region" aria-label="${section.title}">
      <table><thead><tr>${columns.map(column => `<th scope="col">${column.label}</th>`).join('')}${manageDevices ? '<th scope="col">Дії</th>' : ''}</tr></thead>
      <tbody>${page.map((row, index) => `<tr>${columns.map(column => `<td>${cell(column, row)}</td>`).join('')}${manageDevices ? `<td data-device-row="${index}">${deviceButtons(row)}</td>` : ''}</tr>`).join('')}</tbody></table>
    </div>` : '<p class="muted">Записів немає.</p>'}
    ${offset || rows.length > PAGE_SIZE ? `<div class="row pagination">
      ${offset ? '<button type="button" id="previous-page">Назад</button>' : ''}
      <span>Сторінка ${offset / PAGE_SIZE + 1}</span>
      ${rows.length > PAGE_SIZE ? '<button type="button" id="next-page">Далі</button>' : ''}
    </div>` : ''}`;
    if (manageDevices) {
      result.querySelectorAll('[data-decision]').forEach(button => {
        button.onclick = async () => {
          const cell = button.closest('[data-device-row]');
          const row = page[Number(cell.dataset.deviceRow)];
          const action = button.dataset.decision;
          const question = action === 'approve'
            ? 'Підтвердити цей пристрій? Попередній пристрій цього типу втратить доступ.'
            : 'Заблокувати цей пристрій? Його активні сеанси завершаться.';
          if (!window.confirm(question)) return;
          cell.querySelectorAll('button').forEach(control => { control.disabled = true; });
          cell.querySelector('[role="alert"]')?.remove();
          try {
            const changed = must(await timed(sb.rpc('decide_device_access', { p_id: row.id, p_action: action }), WRITE_TIMEOUT, 'доступ пристрою'));
            if (result.isConnected) {
              if (!changed) throw new Error('Запит змінився або у вас немає доступу.');
              await adminView(key, search, offset);
            }
          } catch (error) {
            if (!result.isConnected) return;
            const message = document.createElement('p');
            message.className = 'error';
            message.setAttribute('role', 'alert');
            message.textContent = errorText(error);
            cell.append(message);
            cell.querySelectorAll('button').forEach(control => { control.disabled = false; });
          }
        };
      });
    }
    const previous = $('#previous-page');
    const next = $('#next-page');
    if (previous) previous.onclick = () => adminView(key, search, offset - PAGE_SIZE);
    if (next) next.onclick = () => adminView(key, search, offset + PAGE_SIZE);
  } catch (error) {
    if (!result.isConnected) return;
    result.innerHTML = `<p class="error" role="alert">${esc(errorText(error))}</p><button type="button" id="retry-admin">Повторити</button>`;
    $('#retry-admin').onclick = () => adminView(key, search, offset);
  }
}

async function historyView() {
  importWorkspace('history', 'Історія залишків', '<div class="card" role="status">Завантаження…</div>');
  const pending = app.querySelector('.workspace');
  let body;
  try {
    const rows = must(await timed(sb.from('stock_uploads')
      .select('at,by_name,file_name,rows_total,changed,removed,status')
      .in('status', ['done', 'failed']).order('at', { ascending: false }).order('id', { ascending: false }).limit(30),
      READ_TIMEOUT, 'історія залишків'));
    body = rows.length ? `<div class="table-wrap" tabindex="0" role="region" aria-label="Історія імпорту">
      <table><thead><tr><th scope="col">Час</th><th scope="col">Файл і хто імпортував</th><th scope="col">Товари</th><th scope="col">Стан</th></tr></thead><tbody>
      ${rows.map(row => `<tr><td>${esc(when(row.at))}</td><td>${esc(row.file_name || '—')}<br><small>${esc(row.by_name || '—')}</small></td>
        <td>${fmt(row.rows_total)}<br><small>${fmt(row.changed)} змінено · ${fmt(row.removed)} вилучено</small></td>
        <td class="${row.status === 'done' ? 'ok' : 'error'}">${row.status === 'done' ? 'Завершено' : 'Помилка'}</td></tr>`).join('')}
      </tbody></table>
    </div>` : '<p class="muted">Завершених завантажень ще немає.</p>';
  } catch (error) {
    body = `<p class="error" role="alert">${esc(errorText(error))}</p><div class="row"><button type="button" id="retry-history">Повторити</button></div>`;
  }
  if (!me || !pending.isConnected) return;
  importWorkspace('history', 'Історія залишків', `<div class="card"><h2>Останні завантаження</h2>${body}</div>`);
  const retry = $('#retry-history');
  if (retry) retry.onclick = historyView;
}

function highlightsView(kind) {
  const title = kind === 'new' ? 'Новинки' : 'Акції';
  importWorkspace(kind, title, `<form class="card" id="highlight-form">
    <h2>Завантажити звіт</h2>
    <p>Виберіть повний звіт .xlsx. Потрібні «Назва» та «Штрихкод» або «Артикул».</p>
    <p class="muted">Додаткові колонки — «Початок», «Кінець»${kind === 'promo' ? ' та «Акційна ціна»' : ''}.</p>
    <label>Файл XLSX <input id="highlight-file" type="file" accept=".xlsx" required></label>
    <div class="row"><button class="primary" type="submit">Перевірити файл</button></div>
  </form>`);
  $('#highlight-form').onsubmit = async (event) => {
    event.preventDefault();
    const file = $('#highlight-file').files[0];
    try {
      if (!/\.xlsx$/i.test(file.name) || file.size > 10 * 1024 * 1024) throw new Error('Потрібен файл .xlsx до 10 МБ.');
      progressView('Перевіряю звіт…', 0, file.name);
      const report = highlightReport(kind);
      await readXlsx(new Uint8Array(await file.arrayBuffer()), report.row, (p) => progressView('Перевіряю звіт…', p, file.name));
      const rows = report.finish();
      const preview = rows.slice(0, 10);
      importWorkspace(kind, title, `<div class="card">
        <h2>Перевірка звіту</h2><p>${esc(file.name)} · ${fmt(rows.length)} товарів</p>
        <div class="table-wrap" tabindex="0" role="region" aria-label="Попередній перегляд звіту">
          <table><thead><tr><th scope="col">Товар</th><th scope="col">Штрихкод або артикул</th><th scope="col">Період</th>${kind === 'promo' ? '<th scope="col">Ціна</th>' : ''}</tr></thead><tbody>
          ${preview.map(row => `<tr><td>${esc(row.name)}</td><td>${esc([row.barcode, row.article].filter(Boolean).join(' · '))}</td>
            <td>${esc([row.starts_on && `від ${row.starts_on}`, row.ends_on && `до ${row.ends_on}`].filter(Boolean).join(' ') || '—')}</td>
            ${kind === 'promo' ? `<td>${row.promo_price == null ? '—' : esc(new Intl.NumberFormat('uk-UA', { style: 'currency', currency: 'UAH' }).format(row.promo_price))}</td>` : ''}</tr>`).join('')}
          </tbody></table>
        </div>
        ${rows.length > preview.length ? `<p class="muted">Показано ${preview.length} з ${fmt(rows.length)} товарів.</p>` : ''}
        <p class="warn">Публікація замінить поточний список цієї категорії для всієї мережі.</p>
        <div class="row"><button class="primary" id="publish">Опублікувати</button><button id="cancel">Скасувати</button></div>
      </div>`);
      $('#cancel').onclick = () => highlightsView(kind);
      $('#publish').onclick = async () => {
        progressView('Публікую…', null, file.name);
        try {
          const count = must(await timed(sb.rpc('publish_product_highlights', { p_kind: kind, p_rows: rows }), WRITE_TIMEOUT, 'публікація'));
          await homeView(`<p>${title}: опубліковано ${fmt(count)} товарів.</p>`);
        } catch (error) { failView(error); }
      };
    } catch (error) { failView(error); }
  };
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
    if (sb) { await startSupabase(file, fresh, startedAt); return; }
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

/**
 * Supabase: порівнює сервер (stock_upload_commit), тож базового знімка не треба —
 * лише склади й «Який це магазин?», далі ті самі екрани.
 */
async function startSupabase(file, fresh, startedAt) {
  progressView('Звіряю склади 1С з магазинами…', null);
  const rows = must(await timed(sb.from('stores').select('code, name, city, shop_id'), READ_TIMEOUT, 'список складів'));
  const known = new Map(rows.map((r) => [r.code, { name: r.name ?? '', city: r.city ?? '', shopId: r.shop_id || null }]));
  const pending = { file: file.name, fresh, plan: null, known, startedAt, autoLinked: 0 };
  // Нові склади — у stores одразу, щоб їх можна було прив'язати.
  const sw = storesToWrite(fresh.stores, known);
  if (sw.length) {
    must(await sb.rpc('stores_upsert', { p_rows: sw.map((s) => ({ code: s.code, name: s.name.slice(0, 200), city: s.city.slice(0, 100) })) }));
    for (const s of sw) known.set(s.code, { ...(known.get(s.code) ?? {}), name: s.name, city: s.city, shopId: known.get(s.code)?.shopId ?? null });
  }
  const unknown = fresh.stores.filter((s) => !known.get(s.code)?.shopId);
  if (!unknown.length) { confirmView(pending); return; }
  const [site, dbShops] = await Promise.all([
    loadSiteShops((p) => progressView('Звіряю склади 1С з магазинами…', p, 'список магазинів із сайту')),
    sb.from('shops').select('id, address, region').then(must),
  ]);
  const byId = new Map(site.map((s) => [s.id, s]));
  for (const s of dbShops) if (!byId.has(s.id) && s.address) byId.set(s.id, { id: s.id, address: s.address ?? '', region: s.region ?? '' });
  const shops = [...byId.values()].sort((a, b) => a.region.localeCompare(b.region, 'uk') || a.address.localeCompare(b.address, 'uk'));
  const linked = new Set([...known.values()].map((k) => k.shopId).filter(Boolean));
  const matches = match(unknown, shops, linked);
  for (const m of matches.filter((x) => x.shopId)) {
    await link(m.code, m.shopId);
    known.set(m.code, { ...known.get(m.code), shopId: m.shopId });
    pending.autoLinked++;
  }
  const rest = unknown.filter((s) => !matches.find((m) => m.code === s.code)?.shopId);
  if (!rest.length) confirmView(pending);
  else linkView(pending, rest, shops, new Map(matches.map((m) => [m.code, m.candidates])));
}

/** «Який це магазин?»: склад 1С ↔ магазин застосунку (stores/{код}.shopId і shops/{id}.code1C). */
async function link(code, shopId) {
  if (sb) { must(await timed(sb.rpc('store_link', { p_code: code, p_shop: shopId }), WRITE_TIMEOUT, 'привʼязка складу')); return; }
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
  if (!pending.plan) { confirmSupabase(pending); return; }
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

// ---------- Supabase: begin → chunk × N → commit ----------

function confirmSupabase(pending, shrunk = null) {
  const items = pending.fresh.items;
  const storeCount = new Set([...items.values()].flatMap((it) => Object.keys(it.q))).size;
  render(`
    <div class="card">
      <h2>Завантажити залишки?</h2>
      <p class="muted">${esc(pending.file)}</p>
      <div class="stats">
        <div><b>${fmt(storeCount)}</b><span>магазинів</span></div>
        <div><b>${fmt(items.size)}</b><span>товарів</span></div>
      </div>
      ${pending.fresh.noBarcode ? `<p class="muted">Рядків без штрихкоду (пропущено): ${fmt(pending.fresh.noBarcode)}.</p>` : ''}
      ${shrunk ? `<p class="warn">${esc(`У файлі ${fmt(shrunk.file)} товарів — менше половини від наявних (${fmt(shrunk.prev)}). Можливо, це неповний звіт.`)}</p>` : ''}
      <p class="muted">Змінюються лише магазини з цього файлу; магазини без жодного товару у файлі лишаються як були.</p>
      <div class="row">
        <button class="primary" id="go">${shrunk ? 'Усе одно завантажити' : 'Завантажити'}</button>
        <button id="cancel">Скасувати</button>
      </div>
    </div>`);
  $('#cancel').onclick = () => { if (pending.uploadId) sb.rpc('stock_upload_abort', { p_id: pending.uploadId }); homeView(); };
  $('#go').onclick = () => uploadSupabase(pending, !!shrunk);
}

async function uploadSupabase(pending, confirm) {
  const { fresh, startedAt } = pending;
  const shops = fresh.stores.map((s) => s.code);
  try {
    let res;
    if (pending.uploadId) {
      progressView('Застосовую…', null);
      res = await timed(commitStock(sb, pending.uploadId, shops, confirm), WRITE_TIMEOUT, 'застосування залишків');
    } else {
      progressView('Завантажую…', 0, `0 з ${fmt(fresh.items.size)}`);
      res = await uploadStock(sb, {
        fileName: pending.file, items: fresh.items, shops, confirm,
        onProgress: (frac, n) => progressView(frac < 1 ? 'Завантажую…' : 'Застосовую…', frac < 1 ? frac : null, `${fmt(n)} з ${fmt(fresh.items.size)}`),
      });
    }
    pending.uploadId = res.id;
    if (res.problem?.kind === 'confirm') { confirmSupabase(pending, res.problem); return; }
    if (res.problem?.kind === 'unknown') throw new StockFileError(`Невідомі склади: ЧО ${res.problem.codes.join(', ЧО ')}. Онови сторінку й спробуй ще раз.`);
    const r = res.report;
    const seconds = Math.round((Date.now() - startedAt) / 1000);
    const kept = r.kept?.length ? `; без товарів у файлі, лишились як були: ЧО ${r.kept.join(', ЧО ')}` : '';
    await homeView(`<p class="ok"><span class="dot ok"></span>Готово за ${seconds} с: магазинів ${fmt(r.stores)}, товарів ${fmt(r.items)}, змінено ${fmt(r.changed)}, видалено ${fmt(r.removed)}${esc(kept)}${pending.autoLinked ? `, складів привʼязано автоматично: ${pending.autoLinked}` : ''}.</p>`);
  } catch (e) { console.error(e); failView(e); }
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
