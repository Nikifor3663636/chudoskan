// Supabase-шлях сторінки залишків: пачки, помилки commit, повтор commit без повторної відправки.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { backend, chunkRows, commitProblem, uploadStock } from '../src/supabase.js';

const item = (q) => ({ name: 'Товар, з комою', code: 'BOC1', article: '10954', q });

/** Підробний клієнт: записує виклики rpc, відповідає за сценарієм. */
function fakeSb(answers = {}) {
  const calls = [];
  return {
    calls,
    async rpc(fn, args) {
      calls.push([fn, args]);
      const a = answers[fn];
      return typeof a === 'function' ? a(args, calls) : (a ?? { data: null, error: null });
    },
  };
}

describe('supabase', () => {
  test('за замовчуванням — firestore', () => {
    assert.equal(backend(), 'firestore');
  });

  test('пачки по 2 000 рядків, усі поля', () => {
    const items = new Map(Array.from({ length: 4500 }, (_, i) => [String(i), item({ 22: 1 })]));
    const chunks = chunkRows(items);
    assert.deepEqual(chunks.map((c) => c.length), [2000, 2000, 500]);
    assert.deepEqual(chunks[0][0], { barcode: '0', name: 'Товар, з комою', code: 'BOC1', article: '10954', q: { 22: 1 } });
  });

  test('помилки commit: підтвердження, невідомі склади, інше', () => {
    assert.deepEqual(commitProblem({ message: 'confirm_required', details: '100,24000' }), { kind: 'confirm', file: 100, prev: 24000 });
    assert.deepEqual(commitProblem({ message: 'unknown_stores', details: '09,99' }), { kind: 'unknown', codes: ['09', '99'] });
    assert.equal(commitProblem({ message: 'permission denied' }), null);
  });

  test('begin → chunk × N → commit', async () => {
    const sb = fakeSb({
      stock_upload_begin: { data: 7, error: null },
      stock_upload_commit: { data: { changed: 3 }, error: null },
    });
    const items = new Map(Array.from({ length: 2500 }, (_, i) => [String(i), item({ 22: 1 })]));
    const progress = [];
    const res = await uploadStock(sb, { fileName: 'a.xlsx', items, shops: ['22'], onProgress: (f) => progress.push(f) });
    assert.deepEqual(res, { id: 7, report: { changed: 3 } });
    assert.deepEqual(sb.calls.map(([fn]) => fn), ['stock_upload_begin', 'stock_upload_chunk', 'stock_upload_chunk', 'stock_upload_commit']);
    assert.deepEqual(sb.calls.at(-1)[1], { p_id: 7, p_shops: ['22'], p_confirm: false });
    assert.deepEqual(progress, [0.8, 1]);
  });

  test('менше половини — сеанс лишається відкритим (без abort)', async () => {
    const sb = fakeSb({
      stock_upload_begin: { data: 8, error: null },
      stock_upload_commit: { data: null, error: { message: 'confirm_required', details: '10,100' } },
    });
    const res = await uploadStock(sb, { fileName: 'a.xlsx', items: new Map([['1', item({ 22: 1 })]]), shops: ['22'] });
    assert.deepEqual(res, { id: 8, problem: { kind: 'confirm', file: 10, prev: 100 } });
    assert.ok(!sb.calls.some(([fn]) => fn === 'stock_upload_abort'));
  });

  test('помилка пачки — abort і виняток', async () => {
    const sb = fakeSb({
      stock_upload_begin: { data: 9, error: null },
      stock_upload_chunk: { data: null, error: { message: 'stock admin only' } },
    });
    await assert.rejects(uploadStock(sb, { fileName: 'a.xlsx', items: new Map([['1', item({})]]), shops: [] }), /stock admin only/);
    assert.equal(sb.calls.at(-1)[0], 'stock_upload_abort');
  });
});
