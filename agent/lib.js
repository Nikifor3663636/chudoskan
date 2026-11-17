'use strict';

/**
 * Чистые функции агента (без Firestore и Windows) — проверяются `node --test`.
 */

/** Вид принтера по имени в Windows: Godex → этикетки 40×24, Brother и прочие офисные → A4. */
function kindOf(name) {
  const n = String(name || '').toLowerCase();
  if (/godex|g500|g530|rt200|rt230|ez-?\d/.test(n)) return 'label_40x24';
  if (/brother|mfc-|hl-|dcp-|laserjet|kyocera|xerox|ricoh|canon|epson|pantum|samsung/.test(n)) return 'a4';
  return null;
}

/**
 * Список принтеров для agents/{agentId}.printers: только узнанные (kind != null),
 * виртуальные (PDF, XPS, OneNote, Fax) отбрасываем.
 */
function printerList(printers, defaultName) {
  return printers
    .map((p) => (typeof p === 'string' ? p : p.name))
    .filter((name) => name && !/pdf|xps|onenote|fax/i.test(name))
    .map((name) => ({ name, isDefault: name === defaultName, kind: kindOf(name) }))
    .filter((p) => p.kind !== null);
}

/** Подсети для shops/{shopId}.printerSubnets из аргумента «a/24,b/24». */
function parseSubnets(arg) {
  return String(arg || '')
    .split(',')
    .map((s) => s.trim())
    .filter((s) => /^\d{1,3}(\.\d{1,3}){3}\/(2[2-9]|3[0-2])$/.test(s));
}

module.exports = { kindOf, printerList, parseSubnets };
