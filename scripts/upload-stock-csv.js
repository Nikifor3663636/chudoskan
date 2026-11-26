// Залишки мережі з вигрузки 1С: stock.csv («;», UTF-8 або Windows-1251) → Supabase (ті самі RPC, що застосунок і /stock/).
//
//   Склад;Штрихкод;Артикул;Залишок
//   ЧО 22;4823077601234;10954;3
//
//   SUPABASE_URL=… SUPABASE_SECRET_KEY=… node upload-stock-csv.js stock.csv [--confirm] [--dry-run]
//
// Склад — «ЧО 22» або «22». Порожній / нульовий залишок — товару немає. Назви й коди товарів не чіпаються
// (у CSV їх немає): змінюються лише кількості магазинів із файлу. Ключ — лише з env, ніде не виводиться.
import { createClient } from '@supabase/supabase-js';
import { readFileSync } from 'node:fs';
import { basename } from 'node:path';

const CHUNK = 2000;

/** Текст CSV → {items: Map<штрихкод, {article, q}>, shops: [коди], skipped}. Експортується для тестів. */
export function parseCsv(text) {
  const lines = text.replace(/^﻿/, '').split(/\r?\n/).filter((l) => l.trim() !== '');
  const head = (lines.shift() ?? '').split(';').map((h) => h.trim().toLowerCase());
  const col = (re) => head.findIndex((h) => re.test(h));
  const cStore = col(/склад/); const cBc = col(/штрих/); const cArt = col(/артик/); const cQty = col(/залиш|остат|кільк|колич/);
  if (cStore < 0 || cBc < 0 || cQty < 0) throw new Error('Потрібні колонки: Склад;Штрихкод;Артикул;Залишок');
  const items = new Map();
  const shops = new Set();
  let skipped = 0;
  for (const line of lines) {
    const f = line.split(';').map((x) => x.trim().replace(/^"|"$/g, ''));
    const store = (f[cStore] ?? '').match(/\d{1,4}/)?.[0];
    const bc = (f[cBc] ?? '').replace(/\D/g, '');
    if (!store || !bc || bc.length > 64) { skipped++; continue; }
    shops.add(store);
    const qty = Number((f[cQty] ?? '').replace(/\s/g, '').replace(',', '.'));
    const it = items.get(bc) ?? { article: '', q: {} };
    if (cArt >= 0 && f[cArt]) it.article = f[cArt].slice(0, 100);
    if (Number.isFinite(qty) && qty > 0) it.q[store] = (it.q[store] ?? 0) + qty;
    items.set(bc, it);
  }
  return { items, shops: [...shops].sort(), skipped };
}

function decode(buf) {
  const utf = new TextDecoder('utf-8', { fatal: false }).decode(buf);
  return utf.includes('�') ? new TextDecoder('windows-1251').decode(buf) : utf;
}

async function main() {
  const file = process.argv[2];
  if (!file) { console.error('node upload-stock-csv.js stock.csv [--confirm] [--dry-run]'); process.exit(2); }
  const { items, shops, skipped } = parseCsv(decode(readFileSync(file)));
  console.log(`товарів ${items.size} · магазинів ${shops.length} · пропущено рядків ${skipped}`);
  if (process.argv.includes('--dry-run')) return;

  const url = process.env.SUPABASE_URL; const key = process.env.SUPABASE_SECRET_KEY ?? process.env.SUPABASE_SERVICE_ROLE_KEY;
  if (!url || !key) { console.error('Потрібні SUPABASE_URL і SUPABASE_SECRET_KEY'); process.exit(2); }
  const sb = createClient(url, key, { auth: { persistSession: false } });
  const must = (r) => { if (r.error) throw Object.assign(new Error(r.error.message), r.error); return r.data; };

  // Склади, яких ще немає (назва — «ЧО NN»; прив'язку до магазину робить адмін на /stock/).
  const known = new Set(must(await sb.from('stores').select('code')).map((r) => r.code));
  const missing = shops.filter((code) => !known.has(code));
  if (missing.length) must(await sb.rpc('stores_upsert', { p_rows: missing.map((code) => ({ code, name: `ЧО ${code}` })) }));
  const id = must(await sb.rpc('stock_upload_begin', { p_file_name: basename(file), p_source: 'csv' }));
  const rows = [...items].map(([barcode, it]) => ({ barcode, article: it.article, q: it.q }));
  for (let i = 0; i < rows.length; i += CHUNK) {
    must(await sb.rpc('stock_upload_chunk', { p_id: id, p_rows: rows.slice(i, i + CHUNK) }));
    process.stdout.write(`\rнадіслано ${Math.min(i + CHUNK, rows.length)} з ${rows.length}`);
  }
  process.stdout.write('\n');
  const c = await sb.rpc('stock_upload_commit', { p_id: id, p_shops: shops, p_confirm: process.argv.includes('--confirm') });
  if (c.error) {
    await sb.rpc('stock_upload_abort', { p_id: id });
    if (c.error.message.includes('confirm_required')) { console.error(`Товарів менше половини від наявних (${c.error.details}). Повторіть з --confirm.`); process.exit(3); }
    throw new Error(c.error.message);
  }
  console.log(`готово: ${JSON.stringify(c.data)}`);
}

if (process.argv[1]?.endsWith('upload-stock-csv.js')) {
  main().catch((e) => { console.error(e.message); process.exit(1); });
}
