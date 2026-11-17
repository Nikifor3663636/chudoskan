'use strict';

const test = require('node:test');
const assert = require('node:assert');
const { kindOf, printerList, parseSubnets } = require('./lib');

test('вид принтера за назвою', () => {
  assert.strictEqual(kindOf('Godex G500'), 'label_40x24');
  assert.strictEqual(kindOf('GoDEX G500 (копія 1)'), 'label_40x24');
  assert.strictEqual(kindOf('Brother MFC-L2700DW series'), 'a4');
  assert.strictEqual(kindOf('Microsoft Print to PDF'), null);
});

test('список принтерів без віртуальних і з принтером за замовчуванням', () => {
  const list = printerList(
    [{ name: 'Godex G500' }, { name: 'Microsoft Print to PDF' }, { name: 'Fax' }, { name: 'Brother HL-L2360D series' }, { name: 'Невідомий' }],
    'Godex G500',
  );
  assert.deepStrictEqual(list, [
    { name: 'Godex G500', isDefault: true, kind: 'label_40x24' },
    { name: 'Brother HL-L2360D series', isDefault: false, kind: 'a4' },
  ]);
});

test('підмережі з аргументу', () => {
  assert.deepStrictEqual(parseSubnets('10.0.0.0/24, 10.1.0.0/23,сміття,1.2.3.4/8'), ['10.0.0.0/24', '10.1.0.0/23']);
});
