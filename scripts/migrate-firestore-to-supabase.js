// Перенесення даних Firestore → Supabase (етап 7). Upsert — можна запускати повторно.
//
//   GOOGLE_APPLICATION_CREDENTIALS=<ключ сервісного акаунта Firebase>
//   SUPABASE_URL=… SUPABASE_SECRET_KEY=…            (обидва — лише з env, ніде не виводяться)
//   node migrate-firestore-to-supabase.js [--dry-run]
//
// --dry-run: читає Firestore, перетворює, нічого не пише; звірка «Firestore → буде записано → зараз у Supabase».
// Без --dry-run: пише й звіряє ще раз (кількість рядків по кожній таблиці).
// Не переносяться: shops/{id}/stock і shops/{id}/layoutPins (застарілі), прострочені коди devicePairing.
// Firestore не змінюється й не видаляється.
import { createClient } from '@supabase/supabase-js';
import { cert, initializeApp } from 'firebase-admin/app';
import { getFirestore, Timestamp } from 'firebase-admin/firestore';
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';

const DRY = process.argv.includes('--dry-run');
const BATCH = 1000;

// ---------- Перетворення (експортуються для тестів) ----------

const iso = (v) => (v instanceof Timestamp ? v.toDate().toISOString() : v instanceof Date ? v.toISOString() : null);
const str = (v, max) => (typeof v === 'string' ? v.slice(0, max) : v == null ? '' : String(v).slice(0, max));
const opt = (v, max) => (v == null || v === '' ? null : str(v, max));
const bool = (v) => v === true;
const int = (v) => (typeof v === 'number' && Number.isFinite(v) ? Math.trunc(v) : null);

export function q(v) {
  const out = {};
  if (v && typeof v === 'object' && !Array.isArray(v)) {
    for (const [k, n] of Object.entries(v)) if (typeof n === 'number' && Number.isFinite(n) && n >= 0) out[k] = n;
  }
  return out;
}

export const map = {
  users: (id, d) => ({
    uid: id, name: str(d.name, 200), phone: str(d.phone, 40), region: str(d.region, 100), shop: str(d.shop, 200),
    shop_id: str(d.shopId, 40), approved: bool(d.approved), admin: bool(d.admin), super_admin: bool(d.superAdmin),
    stock_admin: bool(d.stockAdmin), approved_by: opt(d.approvedBy, 128), changed_at: iso(d.changedAt),
    ...(iso(d.createdAt) ? { created_at: iso(d.createdAt) } : {}),
  }),
  shops: (id, d) => ({
    id, region: str(d.region, 100), address: str(d.address, 300), code1c: opt(d.code1C, 40),
    printer_subnets: Array.isArray(d.printerSubnets) ? d.printerSubnets.filter((s) => typeof s === 'string').slice(0, 32) : [],
  }),
  printers: (shopId, id, d) => ({
    shop_id: shopId, id: id.slice(0, 200), model: str(d.model, 120), kind: d.kind, name: opt(d.name, 40), ip: str(d.ip, 45),
    port: int(d.port) ?? 9100, transport: d.transport ?? 'tcp', agent_id: opt(d.agentId, 128), printer_name: opt(d.printerName, 200),
    last_seen: iso(d.lastSeen) ?? new Date(0).toISOString(), last_ok_by: opt(d.lastOkBy, 128), density: int(d.density), speed: int(d.speed),
  }),
  stores: (id, d) => ({
    code: id, name: str(d.name, 200), address: str(d.address, 300), city: str(d.city, 100), region: str(d.region, 100),
    shop_id: opt(d.shopId, 100), ...(iso(d.updatedAt) ? { updated_at: iso(d.updatedAt) } : {}),
  }),
  stock: (id, d) => ({
    barcode: id, name: str(d.name, 300), code: str(d.code, 40), article: str(d.article, 100), q: q(d.q),
    ...(iso(d.updatedAt) ? { updated_at: iso(d.updatedAt) } : {}),
  }),
  devices: (id, d) => ({
    uid: id, shop_id: str(d.shopId, 40), name: str(d.pcName, 100), code: opt(d.code, 10), approved_by: str(d.approvedBy, 128) || 'migration',
    ...(iso(d.approvedAt) ? { paired_at: iso(d.approvedAt) } : {}),
  }),
  device_pairing: (id, d) => ({ code: id, uid: str(d.uid, 128), pc_name: str(d.pcName, 100), expires_at: iso(d.expires) }),
  agents: (id, d) => ({
    uid: id, shop_id: str(d.shopId, 40), pc_name: str(d.pcName, 100), online: d.online !== false,
    last_seen: iso(d.lastSeen) ?? new Date(0).toISOString(), version: opt(d.version, 40), queue: int(d.queue) ?? 0,
    last_error: opt(d.lastError, 500), printers: Array.isArray(d.printers) ? d.printers : [],
  }),
  print_jobs: (id, d) => ({
    id: /^[0-9a-f-]{36}$/i.test(id) ? id : randomUUID(), shop_id: str(d.shopId, 40), agent_id: str(d.agentId, 128),
    printer: str(d.printer, 200), by_uid: str(d.by, 128), status: ['queued', 'printing', 'done', 'error'].includes(d.status) ? d.status : 'error',
    lang: str(d.lang || 'EZPL', 20), data: '\\x' + Buffer.from(d.data?.toUint8Array?.() ?? d.data ?? []).toString('hex'),
    size: int(d.size) ?? 0, error: opt(d.error, 500), attempts: int(d.attempts) ?? 0,
    created_at: iso(d.at) ?? new Date().toISOString(), started_at: iso(d.startedAt), done_at: iso(d.doneAt),
  }),
  admin_log: (_id, d) => ({
    at: iso(d.at) ?? new Date().toISOString(), by_uid: str(d.by, 128) || 'unknown', action: str(d.action, 100) || 'unknown',
    target: opt(d.target, 200), shop_id: opt(d.shopId, 40),
    details: d.details == null ? null : typeof d.details === 'string' ? { text: d.details.slice(0, 1000) } : d.details,
  }),
};

/** meta/stock → одне «успішне завантаження» (meta_stock показує його, доки не буде нового). */
export function metaUpload(d, stockRows) {
  const shops = [...new Set(stockRows.flatMap((r) => Object.keys(r.q)))].sort();
  return {
    at: iso(d.updatedAt) ?? new Date().toISOString(), by_uid: 'migration', by_name: str(d.updatedBy, 100),
    source: 'migration', file_name: 'Firestore meta/stock', rows_total: int(d.items) ?? stockRows.length,
    changed: 0, removed: 0, shops, status: 'done',
  };
}

const KINDS = new Set(['label_40x24', 'a4']);
const LINKS = new Set(['tcp', 'agent', 'usb']);

// ---------- Запуск ----------

async function main() {
  const key = process.env.GOOGLE_APPLICATION_CREDENTIALS;
  const url = process.env.SUPABASE_URL;
  const secret = process.env.SUPABASE_SECRET_KEY ?? process.env.SUPABASE_SERVICE_ROLE_KEY;
  if (!key || !url || !secret) { console.error('Потрібні GOOGLE_APPLICATION_CREDENTIALS, SUPABASE_URL, SUPABASE_SECRET_KEY'); process.exit(2); }
  initializeApp({ credential: cert(JSON.parse(readFileSync(key, 'utf8'))) });
  const fs = getFirestore();
  const sb = createClient(url, secret, { auth: { persistSession: false } });

  const read = async (name) => (await fs.collection(name).get()).docs;
  const source = {};
  const rows = {};
  const skipped = {};
  const skip = (t, why) => { skipped[t] = skipped[t] ?? {}; skipped[t][why] = (skipped[t][why] ?? 0) + 1; };

  const users = await read('users'); source.users = users.length;
  rows.users = users.map((d) => map.users(d.id, d.data()));

  const shops = await read('shops'); source.shops = shops.length;
  rows.shops = shops.map((d) => map.shops(d.id, d.data()));

  rows.printers = [];
  source.printers = 0;
  for (const s of shops) {
    const ps = (await s.ref.collection('printers').get()).docs;
    source.printers += ps.length;
    for (const p of ps) {
      const r = map.printers(s.id, p.id, p.data());
      if (!KINDS.has(r.kind) || !LINKS.has(r.transport)) { skip('printers', 'невідомий kind/transport'); continue; }
      rows.printers.push(r);
    }
  }

  const stores = await read('stores'); source.stores = stores.length;
  rows.stores = stores.filter((d) => /^[0-9]{1,4}$/.test(d.id) || (skip('stores', 'код не «NN»'), false)).map((d) => map.stores(d.id, d.data()));

  const stock = await read('stock'); source.stock = stock.length;
  rows.stock = stock.filter((d) => (d.id.length <= 64) || (skip('stock', 'штрихкод > 64'), false)).map((d) => map.stock(d.id, d.data()));

  const meta = await fs.doc('meta/stock').get();
  source.stock_uploads = meta.exists ? 1 : 0;
  rows.stock_uploads = meta.exists ? [metaUpload(meta.data(), rows.stock)] : [];

  const devices = await read('devices'); source.devices = devices.length;
  rows.devices = devices.map((d) => map.devices(d.id, d.data()));

  const pairing = await read('devicePairing'); source.device_pairing = pairing.length;
  rows.device_pairing = pairing.map((d) => map.device_pairing(d.id, d.data()))
    .filter((r) => (r.expires_at && Date.parse(r.expires_at) > Date.now() && /^[1-9][0-9]{5}$/.test(r.code)) || (skip('device_pairing', 'прострочений код'), false));

  const agents = await read('agents'); source.agents = agents.length;
  rows.agents = agents.map((d) => map.agents(d.id, d.data()));

  const jobs = await read('printJobs'); source.print_jobs = jobs.length;
  rows.print_jobs = jobs.map((d) => map.print_jobs(d.id, d.data()));

  const log = await read('adminLog'); source.admin_log = log.length;
  rows.admin_log = log.map((d) => map.admin_log(d.id, d.data()));

  // Магазини, на які посилаються принтери, але без документа shops/ — порожній рядок (FK).
  const shopIds = new Set(rows.shops.map((s) => s.id));
  for (const p of rows.printers) if (!shopIds.has(p.shop_id)) { rows.shops.push(map.shops(p.shop_id, {})); shopIds.add(p.shop_id); }

  const order = ['users', 'shops', 'stores', 'stock', 'stock_uploads', 'printers', 'devices', 'device_pairing', 'agents', 'print_jobs', 'admin_log'];
  const conflict = { users: 'uid', shops: 'id', stores: 'code', stock: 'barcode', printers: 'shop_id,id', devices: 'uid', device_pairing: 'code', agents: 'uid', print_jobs: 'id' };

  const count = async (t) => {
    const { count: n, error } = await sb.from(t).select('*', { count: 'exact', head: true });
    if (error) throw new Error(`${t}: ${error.message}`);
    return n ?? 0;
  };

  const before = {};
  for (const t of order) before[t] = await count(t);

  if (!DRY) {
    for (const t of order) {
      const list = rows[t];
      for (let i = 0; i < list.length; i += BATCH) {
        const part = list.slice(i, i + BATCH);
        // Журнал і завантаження — без природного ключа: лише якщо таблиця ще порожня (повторний запуск не дублює).
        const r = conflict[t]
          ? await sb.from(t).upsert(part, { onConflict: conflict[t] })
          : before[t] === 0 ? await sb.from(t).insert(part) : { error: null };
        if (r.error) throw new Error(`${t} [${i}…]: ${r.error.message}`);
        process.stdout.write(`\r${t}: ${Math.min(i + BATCH, list.length)} з ${list.length}   `);
      }
      if (list.length) process.stdout.write('\n');
    }
  }

  const after = {};
  for (const t of order) after[t] = DRY ? before[t] : await count(t);

  console.log(`\n${DRY ? 'DRY-RUN — нічого не записано' : 'ПЕРЕНЕСЕНО'}\n`);
  console.log('таблиця           Firestore  до запису  у Supabase  збіг');
  let ok = true;
  for (const t of order) {
    const want = rows[t].length;
    const match = DRY ? '—' : after[t] >= want ? 'так' : 'НІ';
    if (!DRY && after[t] < want) ok = false;
    console.log(`${t.padEnd(17)} ${String(source[t]).padStart(9)}  ${String(want).padStart(9)}  ${String(after[t]).padStart(10)}  ${match}`);
  }
  for (const [t, why] of Object.entries(skipped)) for (const [w, n] of Object.entries(why)) console.log(`пропущено ${t}: ${n} (${w})`);
  console.log('не переносяться: shops/{id}/stock, shops/{id}/layoutPins');
  process.exitCode = ok ? 0 : 1;
}

if (process.argv[1]?.endsWith('migrate-firestore-to-supabase.js')) {
  main().catch((e) => { console.error(e.message); process.exit(1); });
}
