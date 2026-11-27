// Сторінка «Залишки мережі» для ПК: вхід stockAdmin → звіт 1С .xlsx → Firestore.
// Пише те саме й так само, як застосунок (NetworkStockRepository.upload); доступ вирішують firestore.rules.
import { initializeApp } from 'firebase/app';
import { getAuth, onAuthStateChanged, signInWithEmailAndPassword, signOut } from 'firebase/auth';
import {
  addDoc, collection, doc, documentId, getDoc, getDocs, getFirestore, limit, orderBy, query, serverTimestamp,
  setDoc, startAfter, writeBatch,
} from 'firebase/firestore';
import { match, parseReport, plan, storesToWrite, StockFileError } from './report.js';
import { adminQuery, adminSections, canViewSection, commandMatches, navCount, PAGE_SIZE, shopLines, toCsv } from './admin.js';
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
    await loadCounts();
    if (sb && canViewSection('staff', me)) await adminView('staff');
    else await homeView();
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

const navIcons = {
  products: '<path d="M11 21.73a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73z" /> <path d="M12 22V12" /> <path d="m3.3 7 7.703 4.734a2 2 0 0 0 1.994 0L20.7 7" /> <path d="m7.5 4.27 9 5.15" />',
  shops: '<path d="m2 7 4.41-4.41A2 2 0 0 1 7.83 2h8.34a2 2 0 0 1 1.42.59L22 7" /> <path d="M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8" /> <path d="M15 22v-4a2 2 0 0 0-2-2h-2a2 2 0 0 0-2 2v4" /> <path d="M2 7h20" /> <path d="M22 7v3a2 2 0 0 1-2 2a2.7 2.7 0 0 1-1.59-.63.7.7 0 0 0-.82 0A2.7 2.7 0 0 1 16 12a2.7 2.7 0 0 1-1.59-.63.7.7 0 0 0-.82 0A2.7 2.7 0 0 1 12 12a2.7 2.7 0 0 1-1.59-.63.7.7 0 0 0-.82 0A2.7 2.7 0 0 1 8 12a2.7 2.7 0 0 1-1.59-.63.7.7 0 0 0-.82 0A2.7 2.7 0 0 1 4 12a2 2 0 0 1-2-2V7" />',
  staff: '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" /> <circle cx="9" cy="7" r="4" /> <path d="M22 21v-2a4 4 0 0 0-3-3.87" /> <path d="M16 3.13a4 4 0 0 1 0 7.75" />',
  printers: '<path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2" /> <path d="M6 9V3a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v6" /> <rect x="6" y="14" width="12" height="8" rx="1" />',
  devices: '<path d="M20 16V7a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v9m16 0H4m16 0 1.28 2.55a1 1 0 0 1-.9 1.45H3.62a1 1 0 0 1-.9-1.45L4 16" />',
  deviceAccess: '<rect width="14" height="20" x="5" y="2" rx="2" ry="2" /> <path d="M12 18h.01" />',
  security: '<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z" /> <path d="m9 12 2 2 4-4" />',
  audit: '<path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8" /> <path d="M3 3v5h5" /> <path d="M12 7v5l4 2" />',
  stock: '<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z" /> <path d="M14 2v4a2 2 0 0 0 2 2h4" /> <path d="M8 13h2" /> <path d="M14 13h2" /> <path d="M8 17h2" /> <path d="M14 17h2" />',
  new: '<path d="M11 21.73a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73z" /> <path d="M12 22V12" /> <path d="m3.3 7 7.703 4.734a2 2 0 0 0 1.994 0L20.7 7" /> <path d="m7.5 4.27 9 5.15" />',
  promo: '<path d="M3.85 8.62a4 4 0 0 1 4.78-4.77 4 4 0 0 1 6.74 0 4 4 0 0 1 4.78 4.78 4 4 0 0 1 0 6.74 4 4 0 0 1-4.77 4.78 4 4 0 0 1-6.75 0 4 4 0 0 1-4.78-4.77 4 4 0 0 1 0-6.76Z" /> <path d="m15 9-6 6" /> <path d="M9 9h.01" /> <path d="M15 15h.01" />',
  history: '<path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8" /> <path d="M3 3v5h5" /> <path d="M12 7v5l4 2" />',
  search: '<circle cx="11" cy="11" r="8" /> <path d="m21 21-4.3-4.3" />',
  out: '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" /> <polyline points="16 17 21 12 16 7" /> <line x1="21" x2="9" y1="12" y2="12" />',
  keyboard: '<path d="M10 8h.01" /> <path d="M12 12h.01" /> <path d="M14 8h.01" /> <path d="M16 12h.01" /> <path d="M18 8h.01" /> <path d="M6 8h.01" /> <path d="M7 16h10" /> <path d="M8 12h.01" /> <rect width="20" height="16" x="2" y="4" rx="2" />',
  download: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" /> <polyline points="7 10 12 15 17 10" /> <line x1="12" x2="12" y1="15" y2="3" />',
  clock: '<circle cx="12" cy="12" r="10" /> <path d="M12 6v6l4 2" />',

};
const navIcon = key => `<svg class="icon" viewBox="0 0 24 24" aria-hidden="true">${navIcons[key]}</svg>`;

// Лічильники бічного меню: товари, магазини, принтери, анкети на підтвердження. Оновлюються після входу й дій.
let counts = {};

async function loadCounts() {
  if (!sb || !me) return;
  const count = async (table, filter = q => q) => {
    const r = await timed(filter(sb.from(table).select('*', { count: 'exact', head: true })), READ_TIMEOUT, 'лічильники').catch(() => null);
    return r && !r.error ? r.count ?? 0 : null;
  };
  const meta = (await timed(sb.from('meta_stock').select('items, stores').maybeSingle(), READ_TIMEOUT, 'лічильники').catch(() => null))?.data;
  const staffVisible = canViewSection('staff', me);
  const [shops, printers, pending, staffAll, approved] = await Promise.all([
    canViewSection('shops', me) ? count('shops') : null,
    canViewSection('printers', me) ? count('printers') : null,
    staffVisible ? count('users', q => q.eq('approved', false).neq('shop', '')) : null,
    staffVisible ? count('users') : null,
    staffVisible ? count('users', q => q.eq('approved', true)) : null,
  ]);
  counts = { products: meta?.items ?? null, stores: meta?.stores ?? null, shops, printers, pending, staffAll, approved };
}

function navItems() {
  const sections = Object.fromEntries(Object.entries(adminSections).map(([key, item]) =>
    [key, { key, label: item.title }]));
  const imports = Object.fromEntries(importKinds.map(item => [item.key, item]));
  return [
    ['Робота', [sections.products, imports.stock, imports.new, imports.promo]],
    ['Мережа', [sections.shops, sections.staff, sections.printers, sections.devices]],
    ['Безпека', [sections.deviceAccess, sections.security, sections.audit]],
    ['Дані', [imports.history]],
  ].map(([label, items]) => [label, items.filter(item =>
    (sb || item.key === 'stock') && canViewSection(item.key, me))]);
}

function navigateTo(kind) {
  if (kind === 'stock') homeView();
  else if (kind === 'history') historyView();
  else if (adminSections[kind]) adminView(kind);
  else highlightsView(kind);
}

/** «Перейти до…» (Ctrl+K): пошук розділу за назвою, Enter — перехід. */
function openCommands() {
  if ($('.cmd-wrap')) return;
  const items = navItems().flatMap(([, list]) => list);
  const wrap = document.createElement('div');
  wrap.className = 'cmd-wrap';
  wrap.innerHTML = `<div class="cmd-box" role="dialog" aria-modal="true" aria-label="Перейти до розділу">
    <label class="search-field">${navIcon('search')}<input type="search" placeholder="Перейти до…" aria-label="Перейти до розділу"></label>
    <div class="cmd-list" role="listbox"></div></div>`;
  document.body.append(wrap);
  const input = wrap.querySelector('input');
  const list = wrap.querySelector('.cmd-list');
  let selected = 0;
  const close = () => wrap.remove();
  const draw = () => {
    const found = commandMatches(items, input.value);
    selected = Math.min(selected, Math.max(0, found.length - 1));
    list.innerHTML = found.length ? found.map((item, index) => `<button type="button" role="option" data-go="${item.key}" aria-selected="${index === selected}">${navIcon(item.key)}${esc(item.label)}</button>`).join('')
      : '<p class="muted">Нічого не знайдено</p>';
    list.querySelectorAll('[data-go]').forEach(button => { button.onclick = () => { close(); navigateTo(button.dataset.go); }; });
    return found;
  };
  input.oninput = () => { selected = 0; draw(); };
  input.onkeydown = event => {
    const found = commandMatches(items, input.value);
    if (event.key === 'ArrowDown') { selected = Math.min(selected + 1, found.length - 1); draw(); event.preventDefault(); }
    if (event.key === 'ArrowUp') { selected = Math.max(selected - 1, 0); draw(); event.preventDefault(); }
    if (event.key === 'Enter' && found[selected]) { close(); navigateTo(found[selected].key); }
  };
  wrap.onclick = event => { if (event.target === wrap) close(); };
  wrap.onkeydown = event => { if (event.key === 'Escape') close(); };
  draw();
  input.focus();
}

/** Гарячі клавіші: Ctrl+K — розділи, «/» — пошук у таблиці, Esc — закрити. */
function openShortcuts() {
  if ($('.cmd-wrap')) return;
  const wrap = document.createElement('div');
  wrap.className = 'cmd-wrap';
  wrap.innerHTML = `<div class="cmd-box" role="dialog" aria-modal="true" aria-labelledby="keys-title">
    <h2 id="keys-title">Гарячі клавіші</h2>
    <dl class="keys"><dt><kbd>Ctrl</kbd> <kbd>K</kbd></dt><dd>Перейти до розділу</dd><dt><kbd>/</kbd></dt><dd>Пошук у таблиці</dd><dt><kbd>Esc</kbd></dt><dd>Закрити вікно</dd></dl>
    <button type="button" id="keys-close">Закрити</button></div>`;
  document.body.append(wrap);
  const close = () => wrap.remove();
  wrap.querySelector('#keys-close').onclick = close;
  wrap.onclick = event => { if (event.target === wrap) close(); };
  wrap.onkeydown = event => { if (event.key === 'Escape') close(); };
  wrap.querySelector('#keys-close').focus();
}

document.addEventListener('keydown', event => {
  if (!me || !$('.workspace')) return;
  const typing = /INPUT|TEXTAREA|SELECT/.test(document.activeElement?.tagName ?? '');
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); openCommands(); }
  else if (event.key === '/' && !typing) {
    const search = $('#admin-search input');
    if (search) { event.preventDefault(); search.focus(); }
  }
});

function importWorkspace(active, heading, content, actions = '', sub = '') {
  const groups = navItems();
  const role = me.superAdmin ? 'Адмін мережі' : me.shopAdmin ? 'Адмін магазину' : 'Адмін залишків';
  const initials = me.name.trim().split(/\s+/u).slice(0, 2).map(part => Array.from(part)[0] ?? '').join('').toUpperCase();
  const scope = me.superAdmin ? 'Уся мережа' : me.shopAdmin ? 'Мій магазин' : 'Залишки мережі';
  const scopeNote = me.superAdmin && counts.stores ? `${counts.stores} магазинів` : role;
  const crumb = sub || (me.superAdmin && counts.stores ? `${scope} · ${counts.stores} магазинів` : scope);
  render(`<div class="workspace">
    <nav class="import-nav" aria-label="Розділи адміністратора">
      <div class="sidebar-brand"><img src="../logo.svg" alt=""><strong>ЧудоСкан</strong><span class="tag">Адмін</span></div>
      <div class="sidebar-scope">${navIcon("shops")}<div><strong>${scope}</strong><small>${esc(scopeNote)}</small></div></div>
      <select class="mobile-nav" aria-label="Розділ адміністратора">
        ${groups.filter(([, items]) => items.length).map(([label, items]) => `<optgroup label="${label}">
          ${items.map(item => `<option value="${item.key}" ${item.key === active ? 'selected' : ''}>${item.label}</option>`).join('')}
        </optgroup>`).join('')}
      </select>
      <div class="sidebar-links">
        ${groups.filter(([, items]) => items.length).map(([label, items]) => `<div class="nav-section">
          <span class="nav-group">${label}</span>
          ${items.map(item => {
            const count = navCount(item.key, counts);
            return `<button type="button" data-kind="${item.key}" ${item.key === active ? 'aria-current="page"' : ''}>${navIcon(item.key)}${item.label}${count ? `<span class="cnt${count.attention ? ' attn' : ''}">${count.text}</span>` : ''}</button>`;
          }).join('')}
        </div>`).join('')}
      </div>
      <div class="account">
        <span class="initials" aria-hidden="true">${esc(initials)}</span>
        <span class="account-name">${esc(me.name)}<small>${role}</small></span>
        <button type="button" class="link icon-only" id="keys" aria-label="Гарячі клавіші" title="Гарячі клавіші">${navIcon('keyboard')}</button>
        <button type="button" class="link icon-only" id="out" aria-label="Вийти" title="Вийти">${navIcon('out')}</button>
      </div>
    </nav>
    <section aria-labelledby="import-title">
      <header class="page-head"><div><h1 id="import-title">${esc(heading)}</h1><small>${esc(crumb)}</small></div>
        <button type="button" class="cmd" id="cmd">${navIcon('search')}<span>Перейти до…</span><kbd>Ctrl</kbd><kbd>K</kbd></button>${actions}</header>
      <div class="page-body">${content}</div>
    </section>
  </div>`);
  $('#out').onclick = () => signOut(auth);
  $('#keys').onclick = openShortcuts;
  $('#cmd').onclick = openCommands;
  const navigate = kind => { if (kind !== active) navigateTo(kind); };
  $('.mobile-nav').onchange = event => navigate(event.currentTarget.value);
  app.querySelectorAll('[data-kind]').forEach(button => {
    button.onclick = () => navigate(button.dataset.kind);
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

const STAFF_FILTERS = [['', 'Усі'], ['pending', 'Очікують'], ['approved', 'Активні']];

async function adminView(key, search = '', offset = 0, status = '') {
  const section = adminSections[key];
  if (!me || !canViewSection(key, me)) return;
  const staff = key === 'staff';
  const manageDevices = key === 'deviceAccess';
  const filters = staff ? STAFF_FILTERS : manageDevices ? [...STAFF_FILTERS, ['blocked', 'Заблоковані']] : [];
  const filterCount = value => staff ? { '': counts.staffAll, pending: counts.pending, approved: counts.approved }[value] : null;
  const pendingWord = n => n % 10 === 1 && n % 100 !== 11 ? 'анкета чекає' : n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 12 || n % 100 > 14) ? 'анкети чекають' : 'анкет чекають';
  const banner = staff && counts.pending && status !== 'pending'
    ? `<div class="banner warn" role="status">${navIcon('clock')}<span><b>${counts.pending} ${pendingWord(counts.pending)} підтвердження.</b> Виберіть рядки й натисніть «Підтвердити».</span><button type="button" class="link" data-filter="pending">Показати</button></div>`
    : '';
  importWorkspace(key, section.title, `${banner}<div class="card admin-panel">
    <div class="admin-toolbar">
      <form id="admin-search" class="row">
        <label class="search-field">${navIcon('search')}
          <input type="search" aria-label="Пошук" name="search" value="${esc(search)}" placeholder="${section.placeholder ?? 'Пошук'}" maxlength="100">
          <kbd aria-hidden="true">/</kbd>
        </label>
      </form>
      ${filters.length ? `<div class="filters" role="group" aria-label="Статус">${filters.map(([value, label]) =>
        `<button type="button" data-filter="${value}" aria-pressed="${value === status}">${label}${filterCount(value) != null ? ` <span class="n">${fmt(filterCount(value))}</span>` : ''}</button>`).join('')}</div>` : ''}
    </div>
    <div id="admin-results" aria-live="polite"><p class="admin-message muted">Завантаження…</p></div>
  </div>`, `<button type="button" class="head-action" id="export" disabled>${navIcon('download')}Експорт</button>`, section.sub ?? '');
  const result = $('#admin-results');
  $('#admin-search').onsubmit = event => {
    event.preventDefault();
    adminView(key, new FormData(event.currentTarget).get('search').trim(), 0, status);
  };
  app.querySelectorAll('[data-filter]').forEach(button => {
    button.onclick = () => adminView(key, search, 0, button.dataset.filter);
  });
  try {
    const rows = must(await timed(adminQuery(sb, key, search, offset, status), READ_TIMEOUT, section.title));
    if (!result.isConnected) return;
    const cell = (column, row) => {
      const value = column.get(row);
      if (staff && column.key === 'name') {
        const name = String(row.name || '').trim() || 'Без імені';
        const initials = name.split(/\s+/u).slice(0, 2).map(part => Array.from(part)[0]).join('').toUpperCase();
        return `<div class="staff-person"><span class="initials" aria-hidden="true">${esc(initials)}</span>
          <div><strong>${esc(name)}</strong>${row.phone ? `<small>${esc(row.phone)}</small>` : ''}</div></div>`;
      }
      if (value == null || value === '') return '—';
      if (staff && column.key === 'shop') {
        const [shopName, mall] = shopLines(value);
        return `<div class="cell-2"><span>${esc(shopName)}</span>${mall ? `<small>${esc(mall)}</small>` : ''}</div>`;
      }
      if (staff && column.key === 'approved') {
        return `<span class="status-pill ${row.approved ? 'approved' : 'pending'}">${esc(value)}</span>`;
      }
      if (manageDevices && column.key === 'status') {
        const state = ['approved', 'pending', 'blocked', 'replaced'].includes(row.status) ? row.status : '';
        return `<span class="status-pill ${state}">${esc(value)}</span>`;
      }
      if (['at', 'paired_at', 'requested_at', 'seen_at'].includes(column.key)) return esc(when(value));
      if (key === 'products' && column.key === 'name') {
        return `<div class="product-cell"><span class="thumb" aria-hidden="true">${navIcon('products')}</span><span class="cell-title">${esc(value)}</span></div>`;
      }
      if (key === 'products' && column.key === 'q') return `${esc(fmt(value))} шт`;
      return esc(typeof value === 'number' ? fmt(value) : value);
    };
    const page = rows.slice(0, PAGE_SIZE);
    const columnClass = column => {
      if (column.key === 'q') return 'num';
      if (['barcode', 'code', 'code1c'].includes(column.key)) return 'mono';
      return '';
    };
    const columns = section.columns.filter(column => !manageDevices || column.key !== 'id');
    const canApproveStaff = staff && (me.superAdmin || me.shopAdmin);
    const rowButtons = row => {
      if (canApproveStaff) {
        return !row.approved && row.uid !== me.uid
          ? '<button type="button" data-decision="approveStaff">Підтвердити</button>' : '';
      }
      if (row.status === 'pending') return '<button type="button" data-decision="approve">Підтвердити</button> <button type="button" data-decision="block">Заблокувати</button>';
      if (row.status === 'approved') return '<button type="button" data-decision="block">Заблокувати</button>';
      return '';
    };
    const hasActions = manageDevices || canApproveStaff;
    const selectable = row => canApproveStaff && !row.approved && row.uid !== me.uid;
    const bulk = canApproveStaff && page.some(selectable);
    result.innerHTML = `${bulk ? `<div class="bulkbar" hidden><b>Вибрано: <span id="bulk-count">0</span></b>
      <button type="button" id="bulk-approve">Підтвердити</button><button type="button" id="bulk-clear">Скасувати вибір</button></div>` : ''}
    ${page.length ? `<div class="table-wrap" tabindex="0" role="region" aria-label="${section.title}">
      <table><thead><tr>${bulk ? '<th scope="col" class="sel-col"><input type="checkbox" id="select-all" aria-label="Вибрати всі анкети"></th>' : ''}${columns.map(column => `<th scope="col" class="${columnClass(column)}">${column.label}</th>`).join('')}${hasActions ? '<th scope="col">Дії</th>' : ''}</tr></thead>
      <tbody>${page.map((row, index) => `<tr>${bulk ? `<td class="sel-col">${selectable(row) ? `<input type="checkbox" data-select="${index}" aria-label="Вибрати ${esc(row.name || 'анкету')}">` : ''}</td>` : ''}${columns.map(column => `<td class="${columnClass(column)}">${cell(column, row)}</td>`).join('')}${hasActions ? `<td data-action-row="${index}">${rowButtons(row)}</td>` : ''}</tr>`).join('')}</tbody></table>
    </div>` : `<p class="admin-message muted">${search || status ? 'За цим фільтром нічого не знайдено.' : 'Записів немає.'}</p>`}
    ${offset || rows.length > PAGE_SIZE ? `<div class="row pagination">
      ${offset ? '<button type="button" id="previous-page">Назад</button>' : ''}
      <span>Сторінка ${offset / PAGE_SIZE + 1}</span>
      ${rows.length > PAGE_SIZE ? '<button type="button" id="next-page">Далі</button>' : ''}
    </div>` : ''}`;
    if (hasActions) {
      result.querySelectorAll('[data-decision]').forEach(button => {
        button.onclick = async () => {
          const cell = button.closest('[data-action-row]');
          const row = page[Number(cell.dataset.actionRow)];
          const action = button.dataset.decision;
          const question = {
            approveStaff: `Підтвердити доступ для ${row.name || 'співробітника'}?`,
            approve: 'Підтвердити цей пристрій? Попередній пристрій цього типу втратить доступ.',
            block: 'Заблокувати цей пристрій? Його активні сеанси завершаться.',
          }[action];
          if (!window.confirm(question)) return;
          cell.querySelectorAll('button').forEach(control => { control.disabled = true; });
          cell.querySelector('[role="alert"]')?.remove();
          try {
            const request = action === 'approveStaff'
              ? sb.from('users').update({ approved: true, approved_by: me.uid, changed_at: new Date().toISOString() })
                .eq('uid', row.uid).eq('approved', false).select('uid').maybeSingle()
              : sb.rpc('decide_device_access', { p_id: row.id, p_action: action });
            const changed = must(await timed(request, WRITE_TIMEOUT, 'підтвердження доступу'));
            if (result.isConnected) {
              if (!changed) throw new Error('Запит змінився або у вас немає доступу.');
              await loadCounts();
              await adminView(key, search, Math.max(0, page.length === 1 && status ? offset - PAGE_SIZE : offset), status);
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
    const exportButton = $('#export');
    if (exportButton && page.length) {
      exportButton.disabled = false;
      exportButton.onclick = () => {
        const blob = new Blob([toCsv(columns, page)], { type: 'text/csv;charset=utf-8' });
        const link = document.createElement('a');
        link.href = URL.createObjectURL(blob);
        link.download = `${section.title}.csv`;
        link.click();
        setTimeout(() => URL.revokeObjectURL(link.href), 1000);
      };
    }
    if (bulk) {
      const boxes = [...result.querySelectorAll('[data-select]')];
      const bar = result.querySelector('.bulkbar');
      const all = $('#select-all');
      const sync = () => {
        const chosen = boxes.filter(box => box.checked).length;
        bar.hidden = chosen === 0;
        $('#bulk-count').textContent = String(chosen);
        all.checked = chosen === boxes.length;
        all.indeterminate = chosen > 0 && chosen < boxes.length;
        boxes.forEach(box => box.closest('tr').classList.toggle('sel', box.checked));
      };
      boxes.forEach(box => { box.onchange = sync; });
      all.onchange = () => { boxes.forEach(box => { box.checked = all.checked; }); sync(); };
      $('#bulk-clear').onclick = () => { boxes.forEach(box => { box.checked = false; }); sync(); };
      $('#bulk-approve').onclick = async () => {
        const rowsToApprove = boxes.filter(box => box.checked).map(box => page[Number(box.dataset.select)]);
        if (!rowsToApprove.length || !window.confirm(`Підтвердити доступ для ${rowsToApprove.length} співробітників?`)) return;
        bar.querySelectorAll('button').forEach(control => { control.disabled = true; });
        const failed = [];
        for (const row of rowsToApprove) {
          try {
            const changed = must(await timed(sb.from('users').update({ approved: true, approved_by: me.uid, changed_at: new Date().toISOString() })
              .eq('uid', row.uid).eq('approved', false).select('uid').maybeSingle(), WRITE_TIMEOUT, 'підтвердження доступу'));
            if (!changed) failed.push(row.name || row.uid);
          } catch {
            failed.push(row.name || row.uid);
          }
        }
        await loadCounts();
        if (!result.isConnected) return;
        await adminView(key, search, offset, status);
        if (failed.length) window.alert(`Не вдалося підтвердити: ${failed.join(', ')}`);
      };
    }
    const previous = $('#previous-page');
    const next = $('#next-page');
    if (previous) previous.onclick = () => adminView(key, search, offset - PAGE_SIZE, status);
    if (next) next.onclick = () => adminView(key, search, offset + PAGE_SIZE, status);
  } catch (error) {
    if (!result.isConnected) return;
    result.innerHTML = `<p class="error" role="alert">${esc(errorText(error))}</p><button type="button" id="retry-admin">Повторити</button>`;
    $('#retry-admin').onclick = () => adminView(key, search, offset, status);
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
