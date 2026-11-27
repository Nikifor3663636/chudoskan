import assert from 'node:assert/strict';
import test from 'node:test';
import { adminQuery, adminSections, canViewSection, commandMatches, navCount, PAGE_SIZE, shopLines, toCsv } from '../src/admin.js';

test('admin navigation follows the employee roles', () => {
  const stock = { canImport: true, shopAdmin: false, superAdmin: false };
  const shop = { canImport: false, shopAdmin: true, superAdmin: false };
  const network = { ...stock, superAdmin: true };

  assert.equal(canViewSection('stock', stock), true);
  assert.equal(canViewSection('staff', stock), false);
  assert.equal(canViewSection('staff', shop), true);
  assert.equal(canViewSection('history', shop), false);
  assert.equal(canViewSection('audit', shop), false);
  assert.equal(canViewSection('audit', network), true);
  assert.equal(canViewSection('security', shop), false);
  assert.equal(canViewSection('security', stock), false);
  assert.equal(canViewSection('security', network), true);
  assert.equal(canViewSection('deviceAccess', shop), false);
  assert.equal(canViewSection('deviceAccess', stock), false);
  assert.equal(canViewSection('deviceAccess', network), true);
});

test('admin search uses one whitelisted field, escaped text and stable pagination', () => {
  const calls = [];
  const client = {};
  for (const method of ['from', 'select', 'ilike', 'order', 'range']) {
    client[method] = (...args) => { calls.push([method, ...args]); return client; };
  }
  adminQuery(client, 'printers', '100%_test', PAGE_SIZE);
  assert.deepEqual(calls, [
    ['from', 'printers'],
    ['select', 'name,model,kind,transport'],
    ['ilike', 'model', '%100\\%\\_test%'],
    ['order', 'shop_id', { ascending: true }],
    ['order', 'id'],
    ['range', PAGE_SIZE, PAGE_SIZE * 2],
  ]);
  assert.equal(adminSections.products.columns.at(-1).get({ q: { 21: 3, 31: 7 } }), 10);
});

test('staff search covers name, phone and shop while device filters match a specific status', () => {
  const calls = [];
  const client = {};
  for (const method of ['from', 'select', 'or', 'eq', 'order', 'range']) {
    client[method] = (...args) => { calls.push([method, ...args]); return client; };
  }
  adminQuery(client, 'staff', 'Одеса, ЧО 22', 0, 'pending');
  assert.deepEqual(calls[2], ['or',
    'name.ilike."%Одеса, ЧО 22%",phone.ilike."%Одеса, ЧО 22%",shop.ilike."%Одеса, ЧО 22%"']);
  assert.deepEqual(calls[3], ['eq', 'approved', false]);
  calls.length = 0;
  adminQuery(client, 'deviceAccess', '', 0, 'blocked');
  assert.deepEqual(calls[2], ['eq', 'status', 'blocked']);
});

test('sidebar counts highlight pending staff only', () => {
  const counts = { products: 21870, shops: 46, printers: 15, pending: 2 };
  assert.deepEqual(navCount('products', counts), { text: '21 870', attention: false });
  assert.deepEqual(navCount('staff', counts), { text: '2', attention: true });
  assert.equal(navCount('staff', { pending: 0 }), null);
  assert.equal(navCount('audit', counts), null);
});

test('shop splits into number and mall', () => {
  assert.deepEqual(shopLines('ЧО 22 Одеса · ТРЦ Gagarin Plaza'), ['ЧО 22 Одеса', 'ТРЦ Gagarin Plaza']);
  assert.deepEqual(shopLines('ЧО 1 Київ'), ['ЧО 1 Київ', '']);
  assert.deepEqual(shopLines(null), ['', '']);
});

test('csv export quotes separators and keeps cyrillic', () => {
  const columns = [{ label: 'Товар', get: row => row.name }, { label: 'Кількість', get: row => row.q }];
  const csv = toCsv(columns, [{ name: 'Потяг; LEGO "DUPLO"', q: 3 }, { name: 'М’яч', q: null }]);
  assert.equal(csv, '﻿Товар;Кількість\r\n"Потяг; LEGO ""DUPLO""";3\r\nМ’яч;');
});

test('command palette filters sections by name', () => {
  const items = [{ key: 'staff', label: 'Співробітники' }, { key: 'shops', label: 'Магазини' }];
  assert.deepEqual(commandMatches(items, 'маг').map(item => item.key), ['shops']);
  assert.equal(commandMatches(items, '').length, 2);
});
