import assert from 'node:assert/strict';
import test from 'node:test';
import { adminQuery, adminSections, canViewSection, PAGE_SIZE } from '../src/admin.js';

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
