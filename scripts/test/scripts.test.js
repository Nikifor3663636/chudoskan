// node --test: перетворення Firestore → Supabase і розбір CSV вигрузки 1С (без мережі).
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { Timestamp } from 'firebase-admin/firestore';
import { map, metaUpload, q } from '../migrate-firestore-to-supabase.js';
import { parseCsv } from '../upload-stock-csv.js';

describe('перенесення', () => {
  test('users: ролі й поля 1:1, без зайвого', () => {
    const r = map.users('u1', { name: 'Оля', phone: '+380961234567', region: 'kyiv', shop: 'ТЦ', shopId: '22', approved: true, admin: true, superAdmin: false, stockAdmin: true, createdAt: Timestamp.fromMillis(0) });
    assert.deepEqual(r, { uid: 'u1', name: 'Оля', phone: '+380961234567', region: 'kyiv', shop: 'ТЦ', shop_id: '22', approved: true, admin: true, super_admin: false, stock_admin: true, approved_by: null, changed_at: null, created_at: '1970-01-01T00:00:00.000Z' });
  });

  test('q: лише числа ≥ 0', () => {
    assert.deepEqual(q({ 22: 3, 62: -1, 5: '7', 9: null, 1: 0.5 }), { 1: 0.5, 22: 3 });
    assert.deepEqual(q([1, 2]), {});
  });

  test('print_jobs: байти → bytea, не-uuid id → новий uuid', () => {
    const r = map.print_jobs('abc', { agentId: 'pc', printer: 'G', shopId: '22', by: 'u', status: 'queued', data: Buffer.from([1, 255]), at: Timestamp.fromMillis(1000) });
    assert.equal(r.data, '\\x01ff');
    assert.match(r.id, /^[0-9a-f-]{36}$/);
    assert.equal(r.created_at, '1970-01-01T00:00:01.000Z');
  });

  test('adminLog: рядок details → {text}', () => {
    assert.deepEqual(map.admin_log('x', { by: 'a', action: 'approve', details: 'магазинів 46' }).details, { text: 'магазинів 46' });
  });

  test('meta/stock → завантаження done з магазинами', () => {
    const m = metaUpload({ updatedBy: 'Залишки', items: 2 }, [{ q: { 22: 1 } }, { q: { 62: 2, 22: 1 } }]);
    assert.equal(m.status, 'done');
    assert.deepEqual(m.shops, ['22', '62']);
    assert.equal(m.rows_total, 2);
  });
});

describe('CSV 1С', () => {
  test('склад «ЧО 22» і «22» — один; сума; 0 — магазин у файлі без товару', () => {
    const r = parseCsv('﻿Склад;Штрихкод;Артикул;Залишок\r\nЧО 22;4823077601234;10954;3\r\n22;4823077601234;10954;2,5\r\nЧО 62;4823077601234;10954;0\r\nЧО 5;;x;1\r\n');
    assert.deepEqual([...r.items], [['4823077601234', { article: '10954', q: { 22: 5.5 } }]]);
    assert.deepEqual(r.shops, ['22', '62']);
    assert.equal(r.skipped, 1);
  });

  test('без потрібних колонок — помилка', () => {
    assert.throws(() => parseCsv('a;b\n1;2'), /Склад;Штрихкод/);
  });
});
