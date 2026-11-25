// Ті самі випадки, що app/src/test/.../NetworkStockTest.kt і StoreMatcherTest.kt: веб-сторінка
// має розбирати звіт 1С так само, як застосунок. Запуск: npm test.
// Справжній звіт: STOCK_XLSX=шлях\до\звіту.xlsx npm test (у git не потрапляє).
import { readFileSync, existsSync } from 'node:fs';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { zipSync, strToU8 } from 'fflate';
import { parseReport, plan, store, storesToWrite, match, cleanBarcode, number } from '../src/report.js';
import { StockFileError } from '../src/xlsx.js';

const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const col = (i) => { let n = i + 1; let s = ''; while (n > 0) { const m = (n - 1) % 26; s = String.fromCharCode(65 + m) + s; n = Math.floor((n - 1) / 26); } return s; };

/** xlsx як у 1С: BOM, xl/SharedStrings.xml з великої S, аркуш раніше за рядки. Число — <v>, рядок — спільний. */
function xlsx(rows) {
  const strings = new Map();
  let sheet = '﻿<?xml version="1.0" encoding="UTF-8"?><worksheet><sheetData>';
  rows.forEach((row, i) => {
    const r = i + 1;
    sheet += `<row r="${r}">`;
    row.forEach((v, c) => {
      if (v == null) return;
      const ref = col(c) + r;
      if (typeof v === 'number') sheet += `<c r="${ref}"><v>${v}</v></c>`;
      else {
        if (!strings.has(v)) strings.set(v, strings.size);
        sheet += `<c r="${ref}" t="s"><v>${strings.get(v)}</v></c>`;
      }
    });
    sheet += '</row>';
  });
  sheet += '</sheetData></worksheet>';
  let sst = '﻿<?xml version="1.0" encoding="UTF-8"?><sst>';
  for (const s of strings.keys()) sst += `<si><t xml:space="preserve">${esc(s)}</t></si>`;
  sst += '</sst>';
  return zipSync({ '[Content_Types].xml': strToU8('<Types/>'), 'xl/worksheets/sheet1.xml': strToU8(sheet), 'xl/SharedStrings.xml': strToU8(sst) });
}

function report(stores, products, total = true) {
  const rows = [
    [null, 'Ведомость по партиям товаров на складах'], [null, 'Период: 01.11.2026 - 23.11.2026'], [null, 'Показатели: Количество'],
    [null, 'Группировки строк: Номенклатура'], [null, 'Отбор: Склад В списке'], [], [],
    [null, 'Номенклатура, Код, Артикул', ...stores.flatMap((s) => [s, null])],
    [null, null, ...stores.flatMap(() => ['Штрих код', 'Конечный остаток'])],
    [null, null, ...stores.flatMap(() => [null, 'Количество'])],
  ];
  products.forEach(([b, bcs, qs], i) => rows.push([1000 + i, b, ...stores.flatMap((_, k) => [bcs[k] ?? null, qs[k] ?? null])]));
  if (total) rows.push([null, 'Итого', ...stores.flatMap(() => [null, 999])]);
  return rows;
}

const STORES = ['ЧО 09 Біла Церква (ТЦ Гермес)', 'ЧО 11 Київ (ТЦ Gulliver)', 'ЧО 22 Київ (ТЦ Ocean Plaza)'];
const PRODUCTS = [
  ['"Multi truck" вантажівка, BOC022446, 39217', [4823077601234, 4823077601234, 4823077601234], [1, null, 3]],
  ['Лялька, з аксесуарами, рожева, BUK100200, ', ['4823077600008', '4823077600008', '4823077600008'], [null, 2, null]],
  ['Пазл 1000 елементів, ODR000123, 5-0023', [null, null, '4823077600015'], [null, null, 7]],
  ['Конструктор «Місто», DPK999001, 10698', ['5702017417912', null, null], [4.0, null, 1]],
  ["М'яч, BOC000777, ", ['4.823077601357E12', null, null], [2, 2, 2]],
  ['Іграшки для малюків', [null, null, null], [10, 20, 30]],
  ['Розпродано, BOC000001, 1', ['4820000000999', null, null], [null, null, null]],
  ...Array.from({ length: 15 }, (_, k) => {
    const i = k + 1; const n = String(i).padStart(2, '0'); const bc = `48200000001${n}`;
    return [`Товар ${i}, тест, BOC1000${n}, A-${i}`, [bc, bc, bc], [i % 3, i, null]];
  }),
];

const it = (q, name = 'Товар') => ({ name, code: 'BOC000001', article: '1', q });

test('зменшена копія справжнього звіту', async () => {
  const progress = [];
  const s = await parseReport(xlsx(report(STORES, PRODUCTS)), (p) => progress.push(p));
  assert.deepEqual(s.stores.map((x) => x.code), ['09', '11', '22']);
  assert.deepEqual(s.stores.map((x) => x.city), ['Біла Церква', 'Київ', 'Київ']);
  assert.equal(s.stores[2].name, 'ЧО 22 Київ (ТЦ Ocean Plaza)');
  assert.equal(s.noBarcode, 1);
  assert.equal(s.items.size, 20);
  assert.deepEqual(s.items.get('4823077601234'), { name: '"Multi truck" вантажівка', code: 'BOC022446', article: '39217', q: { '09': 1, 22: 3 } });
  assert.deepEqual(s.items.get('4823077600008'), { name: 'Лялька, з аксесуарами, рожева', code: 'BUK100200', article: '', q: { 11: 2 } });
  assert.deepEqual(s.items.get('4823077600015'), { name: 'Пазл 1000 елементів', code: 'ODR000123', article: '5-0023', q: { 22: 7 } });
  assert.deepEqual(s.items.get('5702017417912').q, { '09': 4, 22: 1 });
  assert.deepEqual(s.items.get('4823077601357'), { name: "М'яч", code: 'BOC000777', article: '', q: { '09': 2, 11: 2, 22: 2 } });
  assert.equal(s.items.get('4820000000999'), undefined);
  assert.deepEqual(s.items.get('4820000000103'), { name: 'Товар 3, тест', code: 'BOC100003', article: 'A-3', q: { 11: 3 } });
  assert.ok([...s.items.values()].every((x) => Object.values(x.q).every((v) => v > 0)));
  assert.ok([...s.items.values()].every((x) => !x.name.startsWith('Итого')));
  assert.equal(progress.at(-1), 1);
  assert.ok(progress.every((p, i) => i === 0 || p >= progress[i - 1]));
});

test('окремі колонки «Код» і «Артикул»', async () => {
  const s = await parseReport(xlsx([
    [null, 'Номенклатура', 'Код', 'Артикул', 'ЧО 22 Київ', null],
    [null, null, null, null, 'Штрих код', 'Конечный остаток'],
    [7, 'Машинка, червона', 'BOC000123', '', '4820000000017', 5],
  ]));
  assert.deepEqual(s.items.get('4820000000017'), { name: 'Машинка, червона', code: 'BOC000123', article: '', q: { 22: 5 } });
});

test('назва без артикула в кінці; той самий штрихкод у двох рядках — сума', async () => {
  let s = await parseReport(xlsx(report(['ЧО 22 Київ'], [['Кубик, BOC123456', ['4820000000024'], [1]]])));
  assert.deepEqual(s.items.get('4820000000024'), { name: 'Кубик', code: 'BOC123456', article: '', q: { 22: 1 } });
  s = await parseReport(xlsx(report(['ЧО 22 Київ'], [['Кубик, BOC123456, 1', ['4820000000024'], [1]], ['Кубик, BOC123456, 1', ['4820000000024'], [2]]])));
  assert.deepEqual(s.items.get('4820000000024').q, { 22: 3 });
});

test('не звіт мережі — зрозуміла помилка', async () => {
  await assert.rejects(parseReport(xlsx([['Артикул', 'Кількість'], ['1', '2']])), StockFileError);
  await assert.rejects(parseReport(new Uint8Array([1, 2, 3, 4])), StockFileError);
});

test('склади з шапки', () => {
  assert.deepEqual(store("ЧО 62 Одеса (ТЦ Рів'єра)"), { code: '62', name: "ЧО 62 Одеса (ТЦ Рів'єра)", city: 'Одеса' });
  assert.equal(store('чо05 Львів')?.code, '05');
  assert.equal(store('Чорноморськ'), null);
  assert.equal(store('Номенклатура'), null);
  assert.equal(store('ЧО 80 Малехів ТЦ Обрій')?.city, 'Малехів');
  assert.equal(store('ЧО 70 Умань LOMO')?.city, 'Умань');
  assert.equal(store("ЧО 33 Кам'янець-Подільський")?.city, "Кам'янець-Подільський");
  assert.equal(store('ЧО 63 Вінниця (ТРЦ S)mall)')?.city, 'Вінниця');
});

test('числа й штрихкоди з Excel', () => {
  assert.equal(cleanBarcode('4.823077601357E12'), '4823077601357');
  assert.equal(cleanBarcode(' 4823077601234 '), '4823077601234');
  assert.equal(cleanBarcode(''), null);
  assert.equal(number('1 234,5'), 1234.5);
  assert.equal(number('0x10'), null);
  assert.equal(number(''), null);
});

test('той самий файл двічі — нічого не писати', async () => {
  const s = await parseReport(xlsx(report(STORES, PRODUCTS)));
  const first = plan(null, s);
  assert.ok(first.first);
  assert.equal(first.changed.length, 20);
  const again = plan(first.items, await parseReport(xlsx(report(STORES, PRODUCTS))));
  assert.equal(again.first, false);
  assert.equal(again.changed.length + again.removed.length, 0);
});

const fresh = (entries) => ({ stores: [{ code: '09', name: 'ЧО 09', city: '' }, { code: '22', name: 'ЧО 22', city: '' }], items: new Map(entries), noBarcode: 0 });

test('лише змінене й зникле', () => {
  const old = new Map([['1', it({ 22: 1 })], ['2', it({ 22: 2 })], ['3', it({ '09': 3 })]]);
  const p = plan(old, fresh([['1', it({ 22: 1 })], ['2', it({ 22: 5 })], ['4', it({ '09': 1 })]]));
  assert.deepEqual(p.changed, ['2', '4']);
  assert.deepEqual(p.removed, ['3']);
  assert.deepEqual(p.keptStores, []);
});

test('магазин з 0 товарів у файлі не обнуляється', () => {
  const old = new Map([['1', it({ '09': 2, 22: 1 })], ['2', it({ '09': 4 })]]);
  const p = plan(old, fresh([['1', it({ 22: 3 })]]));
  assert.deepEqual(p.keptStores, ['09']);
  assert.deepEqual(p.items.get('1').q, { '09': 2, 22: 3 });
  assert.deepEqual(p.items.get('2').q, { '09': 4 });
  assert.deepEqual(p.changed, ['1']);
  assert.deepEqual(p.removed, []);
});

test('менше половини товарів — перепитати', () => {
  const old = new Map(Array.from({ length: 10 }, (_, i) => [`${i}`, it({ 22: 1 })]));
  assert.ok(plan(old, fresh(Array.from({ length: 4 }, (_, i) => [`${i}`, it({ 22: 1 })]))).shrunk);
  assert.equal(plan(old, fresh(Array.from({ length: 5 }, (_, i) => [`${i}`, it({ 22: 1 })]))).shrunk, false);
});

test('довга назва обрізається як у Firestore — і не «змінюється» щоразу', () => {
  const long = 'Д'.repeat(400);
  const stored = new Map([['1', it({ 22: 1 }, long.slice(0, 300))]]);
  assert.equal(plan(stored, fresh([['1', it({ 22: 1 }, long)]])).changed.length, 0);
});

test('склади пишемо лише нові або змінені', () => {
  const s = [{ code: '09', name: 'ЧО 09 Біла Церква', city: 'Біла Церква' }, { code: '22', name: 'ЧО 22 Київ', city: 'Київ' }];
  const known = new Map([['09', { name: 'ЧО 09 Біла Церква', city: 'Біла Церква', shopId: 'kyiv_1' }], ['22', { name: 'ЧО 22 Київ (старий)', city: 'Київ', shopId: null }]]);
  assert.deepEqual(storesToWrite(s, known).map((x) => x.code), ['22']);
  assert.deepEqual(storesToWrite(s.slice(0, 1), known), []);
});

test('склад 1С ↔ магазин за назвою ТЦ і містом', () => {
  const shops = [
    { id: 'odesa_1', region: 'Одеса', address: 'вул. Генуезька, 5/2, ТРЦ "Gagarinn Plaza", друга черга' },
    { id: 'odesa_2', region: 'Одеса', address: 'вул. Варненська, 2, ТЦ «Рів\'єра»' },
    { id: 'kyiv_1', region: 'Київ', address: 'вул. Антоновича, 176, ТРЦ Ocean Plaza' },
  ];
  const m = match([store('ЧО 62 Одеса (ТРЦ Gagarin Plaza)'), store('ЧО 22 Київ (ТЦ Ocean Plaza)')], shops);
  assert.equal(m[0].shopId, 'odesa_1');
  assert.equal(m[1].shopId, 'kyiv_1');
  assert.equal(m[0].candidates[0].id, 'odesa_1');
});

test('id магазину як AuthRepository.shopId (місто + SHA-1 адреси)', async () => {
  const { shopId } = await import('../src/siteShops.js');
  assert.equal(await shopId('stryi', 'вул. Богдана Хмельницького, 4, ТРЦ "Cтрий City"'), 'stryi_ce6f4a877f0c');
  // Регістр і зайві пробіли не змінюють id.
  assert.equal(await shopId('stryi', '  вул.  Богдана Хмельницького, 4, ТРЦ "CТРИЙ CITY" '), 'stryi_ce6f4a877f0c');
});

test('знімок у браузері годиться лише після власного завантаження', async () => {
  const { useSnapshot } = await import('../src/snapshot.js');
  const snap = { mark: 1000, items: new Map() };
  assert.equal(useSnapshot(snap, 1000), true);
  assert.equal(useSnapshot(snap, null), true);
  // Після нашого знімка завантажували з телефона — читаємо Firestore.
  assert.equal(useSnapshot(snap, 2000), false);
  assert.equal(useSnapshot(null, 1000), false);
});

const real = process.env.STOCK_XLSX;
test('справжній звіт 1С (STOCK_XLSX)', { skip: !real || !existsSync(real) }, async () => {
  const buf = new Uint8Array(readFileSync(real));
  const t0 = Date.now();
  const s = await parseReport(buf);
  const ms = Date.now() - t0;
  const withQty = [...s.items.values()].filter((x) => Object.keys(x.q).length).length;
  const noCode = [...s.items.values()].filter((x) => !x.code).length;
  console.log(`складів ${s.stores.length} · товарів ${s.items.size} · без штрихкоду ${s.noBarcode} · без коду 1С ${noCode} · ${ms} мс`);
  console.log('склади:', s.stores.map((x) => `${x.code}=${x.city || '?'}`).join(', '));
  assert.ok(s.stores.length > 0);
  assert.equal(withQty, s.items.size);
});
