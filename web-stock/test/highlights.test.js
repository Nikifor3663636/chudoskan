import assert from 'node:assert/strict';
import test from 'node:test';
import { zipSync, strToU8 } from 'fflate';
import { highlightReport } from '../src/highlights.js';
import { readXlsx } from '../src/xlsx.js';

test('розбирає XLSX, зберігає початковий нуль, нормалізує дати і ціну', async () => {
  const report = highlightReport('promo');
  const cells = [
    ['Назва', 'Штрихкод', 'Артикул', 'Початок', 'Кінець', 'Акційна ціна'],
    ['Конструктор', '0012345678901', 'A-1', '26.11.2026', '2026-11-30', '199,50'],
  ];
  const xml = cells.map((row, r) => `<row r="${r + 1}">${row.map((value, c) =>
    `<c r="${String.fromCharCode(65 + c)}${r + 1}" t="inlineStr"><is><t>${value}</t></is></c>`).join('')}</row>`).join('');
  await readXlsx(zipSync({ 'xl/worksheets/sheet1.xml': strToU8(`<worksheet><sheetData>${xml}</sheetData></worksheet>`) }), report.row);
  assert.deepEqual(report.finish(), [{
    barcode: '0012345678901', article: 'A-1', name: 'Конструктор',
    starts_on: '2026-11-26', ends_on: '2026-11-30', promo_price: 199.5,
  }]);
});

test('помилка в даті чи дублікат блокують звіт', () => {
  const report = highlightReport('new');
  report.row(1, ['Назва', 'Артикул', 'Початок']);
  assert.throws(() => report.row(2, ['Гра', 'A', '31.02.2026']), /Рядок 2.*дата/);
  report.row(3, ['Гра', 'A', '']);
  assert.throws(() => report.row(4, ['Гра', 'A', '']), /Рядок 4.*дублюється/);
});

test('порожній звіт не можна опублікувати', () => {
  const report = highlightReport('promo');
  report.row(1, ['Назва', 'Артикул']);
  assert.throws(() => report.finish(), /немає товарів/);
});
