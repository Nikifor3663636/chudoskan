// Тести firestore.rules на емуляторі: npm test (запускає емулятор сам).
import { readFileSync } from 'node:fs';
import { after, before, beforeEach, describe, test } from 'node:test';
import {
  assertFails, assertSucceeds, initializeTestEnvironment,
} from '@firebase/rules-unit-testing';
import {
  Bytes, Timestamp, addDoc, collection, deleteDoc, doc, getDoc, getDocs, query, serverTimestamp,
  setDoc, updateDoc, where,
} from 'firebase/firestore';

let env;

const MIN = 60 * 1000;
const inMin = (m) => Timestamp.fromMillis(Date.now() + m * MIN);

const USERS = {
  emp22: { name: 'Оля', approved: true, shopId: '22' },
  emp62: { name: 'Іра', approved: true, shopId: '62' },
  adm22: { name: 'Адмін 22', approved: true, admin: true, shopId: '22' },
  adm62: { name: 'Адмін 62', approved: true, admin: true, shopId: '62' },
  sup: { name: 'Мережа', approved: true, superAdmin: true, shopId: '1' },
  stocker: { name: 'Залишки', approved: true, stockAdmin: true, shopId: '5' },
  new22: { name: 'Новенька', approved: false, shopId: '22' },
  new62: { name: 'Новенький', approved: false, shopId: '62' },
  // superAdmin без підтвердження — не адмін мережі.
  supNotApproved: { name: 'Х', approved: false, superAdmin: true, shopId: '1' },
};

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-chudoskan',
    firestore: { rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8') },
  });
});

after(async () => { await env?.cleanup(); });

beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    for (const [uid, data] of Object.entries(USERS)) await setDoc(doc(db, 'users', uid), data);
    await setDoc(doc(db, 'devices', 'pc22'), { shopId: '22', pcName: 'КАСА-22', code: '111111', approvedBy: 'adm22', approvedAt: Timestamp.now() });
    await setDoc(doc(db, 'devices', 'pc62'), { shopId: '62', pcName: 'КАСА-62', code: '222222', approvedBy: 'adm62', approvedAt: Timestamp.now() });
    await setDoc(doc(db, 'devicePairing', '123456'), { uid: 'pcNew', pcName: 'НОВИЙ-ПК', expires: inMin(10) });
    await setDoc(doc(db, 'devicePairing', '654321'), { uid: 'pcOld', pcName: 'СТАРИЙ-ПК', expires: inMin(-1) });
    await setDoc(doc(db, 'devicePairing', '777777'), { uid: 'pc62new', pcName: 'ПК-62', expires: inMin(10) });
    await setDoc(doc(db, 'shops', '22'), { region: 'Київ', address: 'вул. Тестова, 1', rows: 1, updatedAt: Timestamp.now(), updatedBy: 'Оля' });
    await setDoc(doc(db, 'shops', '62'), { region: 'Одеса', address: 'вул. Інша, 2', rows: 1, updatedAt: Timestamp.now(), updatedBy: 'Іра' });
    await setDoc(doc(db, 'shops', '62', 'printers', 'g'), { model: 'Godex G500', kind: 'label_40x24', ip: '192.0.2.10', port: 9100, transport: 'tcp', lastSeen: Timestamp.now() });
    await setDoc(doc(db, 'agents', 'pc22'), { shopId: '22', pcName: 'КАСА-22', online: true, lastSeen: Timestamp.now(), printers: [] });
    await setDoc(doc(db, 'printJobs', 'job22'), { agentId: 'pc22', printer: 'Godex G500', shopId: '22', by: 'emp22', at: Timestamp.now(), status: 'queued', lang: 'EZPL', data: Bytes.fromUint8Array(new Uint8Array([1])), size: 1 });
    await setDoc(doc(db, 'stock', '4823077601234'), { q: { 22: 3 }, name: 'LEGO', code: 'BOC1', article: '10954', updatedAt: Timestamp.now() });
  });
});

const as = (uid) => env.authenticatedContext(uid).firestore();
const anon = () => env.unauthenticatedContext().firestore();

const job = (over = {}) => ({
  agentId: 'pc22', printer: 'Godex G500', shopId: '22', by: 'emp22', at: serverTimestamp(), status: 'queued',
  lang: 'EZPL', data: Bytes.fromUint8Array(new Uint8Array([1, 2, 3])), size: 3, ...over,
});

const device = (by, over = {}) => ({
  shopId: '22', pcName: 'НОВИЙ-ПК', code: '123456', approvedBy: by, approvedAt: serverTimestamp(), ...over,
});

describe('чужий магазин', () => {
  test('співробітник ЧО 22 не бачить і не пише ЧО 62', async () => {
    const db = as('emp22');
    await assertSucceeds(getDoc(doc(db, 'shops', '22')));
    await assertFails(getDoc(doc(db, 'shops', '62')));
    await assertFails(getDoc(doc(db, 'shops', '62', 'printers', 'g')));
    await assertFails(setDoc(doc(db, 'shops', '62', 'printers', 'x'),
      { model: 'Godex', kind: 'label_40x24', ip: '192.0.2.1', port: 9100, transport: 'tcp', lastSeen: serverTimestamp() }));
    await assertFails(getDocs(query(collection(db, 'agents'), where('shopId', '==', '62'))));
    await assertSucceeds(getDocs(query(collection(db, 'agents'), where('shopId', '==', '22'))));
  });

  test('адмін ЧО 22 не видаляє принтер і не змінює code1C у ЧО 62', async () => {
    const db = as('adm22');
    await assertFails(deleteDoc(doc(db, 'shops', '62', 'printers', 'g')));
    await assertFails(setDoc(doc(db, 'shops', '62'), { code1C: '62' }, { merge: true }));
  });
});

describe('users', () => {
  test('адмін ЧО 22 не бачить і не змінює співробітників ЧО 62', async () => {
    const db = as('adm22');
    await assertFails(getDoc(doc(db, 'users', 'emp62')));
    await assertFails(getDocs(query(collection(db, 'users'), where('shopId', '==', '62'))));
    await assertFails(updateDoc(doc(db, 'users', 'new62'), { approved: true }));
    await assertFails(updateDoc(doc(db, 'users', 'emp62'), { admin: true }));
    // Свій магазин — можна.
    await assertSucceeds(getDoc(doc(db, 'users', 'emp22')));
    await assertSucceeds(getDocs(query(collection(db, 'users'), where('shopId', '==', '22'))));
  });

  test('адмін магазину підтверджує свого, але не переводить в інший магазин', async () => {
    const db = as('adm22');
    await assertSucceeds(updateDoc(doc(db, 'users', 'new22'), { approved: true, approvedBy: 'adm22', changedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(db, 'users', 'emp22'), { shopId: '62' }));
    await assertFails(updateDoc(doc(db, 'users', 'emp22'), { admin: true, shopId: '62' }));
    await assertFails(updateDoc(doc(db, 'users', 'emp22'), { approvedBy: 'someoneElse' }));
    await assertFails(updateDoc(doc(db, 'users', 'emp22'), { name: 'Інше ім\'я' }));
    await assertSucceeds(updateDoc(doc(db, 'users', 'emp22'), { admin: true }));
  });

  test('звичайний співробітник не читає список users і чужі анкети', async () => {
    const db = as('emp22');
    await assertSucceeds(getDoc(doc(db, 'users', 'emp22')));
    await assertFails(getDocs(query(collection(db, 'users'), where('shopId', '==', '22'))));
    await assertFails(getDocs(collection(db, 'users')));
    await assertFails(getDoc(doc(db, 'users', 'new22')));
    await assertFails(updateDoc(doc(db, 'users', 'new22'), { approved: true }));
  });

  test('ніхто не ставить superAdmin / stockAdmin — і сам superAdmin теж', async () => {
    for (const [who, target] of [['emp22', 'emp22'], ['new22', 'new22'], ['adm22', 'emp22'], ['adm22', 'adm22'],
      ['sup', 'emp22'], ['sup', 'sup'], ['stocker', 'stocker']]) {
      const db = as(who);
      // Перемикаємо значення: запис того самого значення нічого не змінює.
      const t = USERS[target];
      await assertFails(updateDoc(doc(db, 'users', target), { superAdmin: !t.superAdmin }), `${who} → ${target}.superAdmin`);
      await assertFails(updateDoc(doc(db, 'users', target), { stockAdmin: !t.stockAdmin }), `${who} → ${target}.stockAdmin`);
    }
    await assertFails(setDoc(doc(as('fresh'), 'users', 'fresh'), { name: 'Я', approved: false, shopId: '22', superAdmin: true }));
  });

  test('сам: реєстрація, ім\'я; ролі не змінює', async () => {
    await assertSucceeds(setDoc(doc(as('fresh'), 'users', 'fresh'), { name: 'Я', approved: false, shopId: '22', region: 'Київ', shop: 'ЧО 22' }));
    await assertSucceeds(updateDoc(doc(as('emp22'), 'users', 'emp22'), { name: 'Ольга' }));
    await assertFails(updateDoc(doc(as('emp22'), 'users', 'emp22'), { shopId: '62' }));
    await assertFails(updateDoc(doc(as('emp22'), 'users', 'emp22'), { admin: true }));
    await assertFails(updateDoc(doc(as('new22'), 'users', 'new22'), { approved: true }));
    await assertSucceeds(updateDoc(doc(as('new22'), 'users', 'new22'), { shopId: '62' }));
    // Адмін на анкету з консольним полем superAdmin може змінити своє ім'я.
    await assertSucceeds(updateDoc(doc(as('sup'), 'users', 'sup'), { name: 'Мережа Київ' }));
  });

  test('superAdmin підтверджує будь-кого й переводить між магазинами', async () => {
    const db = as('sup');
    await assertSucceeds(getDocs(collection(db, 'users')));
    await assertSucceeds(updateDoc(doc(db, 'users', 'new62'), { approved: true, approvedBy: 'sup', changedAt: serverTimestamp() }));
    await assertSucceeds(updateDoc(doc(db, 'users', 'emp22'), { shopId: '62', shop: 'ЧО 62', admin: true }));
  });

  test('непідтверджений superAdmin — ніхто', async () => {
    const db = as('supNotApproved');
    await assertFails(getDoc(doc(db, 'users', 'emp22')));
    await assertFails(updateDoc(doc(db, 'users', 'new62'), { approved: true }));
  });
});

describe('підключення ПК', () => {
  test('ПК створює код; погані коди й строки відхиляються', async () => {
    const pc = as('pcX');
    await assertSucceeds(setDoc(doc(pc, 'devicePairing', '345678'), { uid: 'pcX', pcName: 'ПК', expires: inMin(15) }));
    await assertFails(setDoc(doc(pc, 'devicePairing', '012345'), { uid: 'pcX', pcName: 'ПК', expires: inMin(15) }));
    await assertFails(setDoc(doc(pc, 'devicePairing', '12345'), { uid: 'pcX', pcName: 'ПК', expires: inMin(15) }));
    await assertFails(setDoc(doc(pc, 'devicePairing', '345679'), { uid: 'pcX', pcName: 'ПК', expires: inMin(30) }));
    await assertFails(setDoc(doc(pc, 'devicePairing', '345670'), { uid: 'інший', pcName: 'ПК', expires: inMin(15) }));
    await assertFails(setDoc(doc(pc, 'devicePairing', '345671'), { uid: 'pcX', pcName: 'ПК', expires: inMin(15), shopId: '22' }));
    await assertFails(setDoc(doc(anon(), 'devicePairing', '345672'), { uid: 'pcX', pcName: 'ПК', expires: inMin(15) }));
    // Чужий код не перезаписати.
    await assertFails(setDoc(doc(pc, 'devicePairing', '123456'), { uid: 'pcX', pcName: 'ПК', expires: inMin(15) }));
  });

  test('код читає лише адмін; список і зміни заборонені', async () => {
    await assertSucceeds(getDoc(doc(as('adm22'), 'devicePairing', '123456')));
    await assertSucceeds(getDoc(doc(as('sup'), 'devicePairing', '123456')));
    await assertFails(getDoc(doc(as('emp22'), 'devicePairing', '123456')));
    await assertFails(getDocs(collection(as('adm22'), 'devicePairing')));
    await assertFails(updateDoc(doc(as('adm22'), 'devicePairing', '123456'), { pcName: 'x' }));
    await assertFails(deleteDoc(doc(as('emp22'), 'devicePairing', '123456')));
    await assertSucceeds(deleteDoc(doc(as('pcNew'), 'devicePairing', '123456')));
  });

  test('адмін підключає ПК свого магазину за чинним кодом', async () => {
    const db = as('adm22');
    await assertSucceeds(setDoc(doc(db, 'devices', 'pcNew'), device('adm22')));
    await assertSucceeds(getDoc(doc(as('pcNew'), 'devices', 'pcNew')));
    await assertSucceeds(getDoc(doc(as('emp22'), 'devices', 'pcNew')));
    await assertFails(getDoc(doc(as('emp62'), 'devices', 'pcNew')));
    await assertFails(updateDoc(doc(db, 'devices', 'pcNew'), { pcName: 'інший' }));
  });

  test('адмін без коду не створює devices', async () => {
    const db = as('adm22');
    await assertFails(setDoc(doc(db, 'devices', 'pcNew'), device('adm22', { code: '999999' })));
    // Код є, але виданий іншому ПК.
    await assertFails(setDoc(doc(db, 'devices', 'pcHack'), device('adm22')));
    await assertFails(setDoc(doc(db, 'devices', 'pcNew'), device('adm62')));
    await assertFails(setDoc(doc(db, 'devices', 'pcNew'), { ...device('adm22'), approvedAt: Timestamp.now() }));
    // Співробітник — не адмін.
    await assertFails(setDoc(doc(as('emp22'), 'devices', 'pcNew'), device('emp22')));
    // Адмін ЧО 22 не підключає ПК до ЧО 62.
    await assertFails(setDoc(doc(db, 'devices', 'pc62new'), device('adm22', { shopId: '62', code: '777777' })));
    // ПК не підключає сам себе.
    await assertFails(setDoc(doc(as('pcNew'), 'devices', 'pcNew'), device('pcNew')));
  });

  test('минулий код не підходить', async () => {
    await assertFails(setDoc(doc(as('adm22'), 'devices', 'pcOld'), device('adm22', { code: '654321' })));
  });

  test('superAdmin підключає ПК будь-якого магазину', async () => {
    await assertSucceeds(setDoc(doc(as('sup'), 'devices', 'pc62new'), device('sup', { shopId: '62', code: '777777' })));
  });

  test('пристрій без devices нічого не пише', async () => {
    const pc = as('pcNew');
    await assertFails(setDoc(doc(pc, 'agents', 'pcNew'), { shopId: '22', pcName: 'ПК', online: true, lastSeen: serverTimestamp() }));
    await assertFails(updateDoc(doc(pc, 'printJobs', 'job22'), { status: 'printing' }));
  });

  test('після видалення devices пристрій нічого не пише', async () => {
    const pc = as('pc22');
    await assertSucceeds(setDoc(doc(pc, 'agents', 'pc22'), { shopId: '22', pcName: 'КАСА-22', online: true, lastSeen: serverTimestamp(), printers: [] }));
    await assertSucceeds(deleteDoc(doc(as('adm22'), 'devices', 'pc22')));
    await assertFails(setDoc(doc(pc, 'agents', 'pc22'), { shopId: '22', pcName: 'КАСА-22', online: true, lastSeen: serverTimestamp(), printers: [] }));
    await assertFails(updateDoc(doc(pc, 'printJobs', 'job22'), { status: 'printing', startedAt: serverTimestamp() }));
    await assertFails(deleteDoc(doc(pc, 'agents', 'pc22')));
  });

  test('ПК сам відключається; чужий адмін — ні', async () => {
    await assertFails(deleteDoc(doc(as('adm62'), 'devices', 'pc22')));
    await assertFails(deleteDoc(doc(as('emp22'), 'devices', 'pc22')));
    await assertSucceeds(deleteDoc(doc(as('pc22'), 'devices', 'pc22')));
  });
});

describe('agents', () => {
  test('пристрій пише лише себе, свій магазин і поля зі списку', async () => {
    const pc = as('pc22');
    const ok = { shopId: '22', pcName: 'КАСА-22', online: true, lastSeen: serverTimestamp(), version: '2.0', queue: 0, lastError: '', printers: [{ name: 'Godex G500', isDefault: true, kind: 'label_40x24' }] };
    await assertSucceeds(setDoc(doc(pc, 'agents', 'pc22'), ok));
    await assertFails(setDoc(doc(pc, 'agents', 'pc22'), { ...ok, shopId: '62' }));
    await assertFails(setDoc(doc(pc, 'agents', 'pc62'), { ...ok, shopId: '62' }));
    await assertFails(setDoc(doc(pc, 'agents', 'pc22'), { ...ok, secret: 'x' }));
    await assertFails(setDoc(doc(as('emp22'), 'agents', 'emp22'), ok));
  });

  test('читають: свій магазин, superAdmin, сам пристрій', async () => {
    await assertSucceeds(getDoc(doc(as('emp22'), 'agents', 'pc22')));
    await assertSucceeds(getDoc(doc(as('sup'), 'agents', 'pc22')));
    await assertSucceeds(getDoc(doc(as('pc22'), 'agents', 'pc22')));
    await assertFails(getDoc(doc(as('emp62'), 'agents', 'pc22')));
    await assertFails(getDoc(doc(as('new22'), 'agents', 'pc22')));
  });
});

describe('printJobs', () => {
  test('співробітник створює завдання для ПК свого магазину', async () => {
    const db = as('emp22');
    await assertSucceeds(addDoc(collection(db, 'printJobs'), job()));
    // agentId — не підключений пристрій.
    await assertFails(addDoc(collection(db, 'printJobs'), job({ agentId: 'pcNew' })));
    // Пристрій іншого магазину.
    await assertFails(addDoc(collection(db, 'printJobs'), job({ agentId: 'pc62' })));
    // Чужий магазин.
    await assertFails(addDoc(collection(db, 'printJobs'), job({ shopId: '62', agentId: 'pc62' })));
    await assertFails(addDoc(collection(db, 'printJobs'), job({ by: 'emp62' })));
    await assertFails(addDoc(collection(db, 'printJobs'), job({ status: 'done' })));
    await assertFails(addDoc(collection(as('new22'), 'printJobs'), job({ by: 'new22' })));
  });

  test('superAdmin друкує в будь-якому магазині', async () => {
    await assertSucceeds(addDoc(collection(as('sup'), 'printJobs'), job({ shopId: '62', agentId: 'pc62', by: 'sup' })));
  });

  test('читають автор і пристрій-адресат', async () => {
    await assertSucceeds(getDoc(doc(as('emp22'), 'printJobs', 'job22')));
    await assertSucceeds(getDoc(doc(as('pc22'), 'printJobs', 'job22')));
    await assertSucceeds(getDocs(query(collection(as('pc22'), 'printJobs'), where('agentId', '==', 'pc22'))));
    await assertFails(getDoc(doc(as('adm22'), 'printJobs', 'job22')));
    await assertFails(getDoc(doc(as('pc62'), 'printJobs', 'job22')));
  });

  test('статус змінює лише пристрій-адресат і лише дозволені поля', async () => {
    const pc = as('pc22');
    await assertSucceeds(updateDoc(doc(pc, 'printJobs', 'job22'), { status: 'printing', startedAt: serverTimestamp(), attempts: 1 }));
    await assertFails(updateDoc(doc(pc, 'printJobs', 'job22'), { data: Bytes.fromUint8Array(new Uint8Array([9])) }));
    await assertFails(updateDoc(doc(pc, 'printJobs', 'job22'), { status: 'зламано' }));
    await assertFails(updateDoc(doc(as('emp22'), 'printJobs', 'job22'), { status: 'done' }));
    await assertFails(updateDoc(doc(as('pc62'), 'printJobs', 'job22'), { status: 'done' }));
    // Не завершене — не видаляється; завершене — видаляє пристрій.
    await assertFails(deleteDoc(doc(pc, 'printJobs', 'job22')));
    await assertSucceeds(updateDoc(doc(pc, 'printJobs', 'job22'), { status: 'done', doneAt: serverTimestamp() }));
    await assertFails(deleteDoc(doc(as('emp22'), 'printJobs', 'job22')));
    await assertSucceeds(deleteDoc(doc(pc, 'printJobs', 'job22')));
  });
});

describe('залишки мережі', () => {
  const row = (q) => ({ q, name: 'LEGO Duplo', code: 'BOC307585', article: '10954', updatedAt: serverTimestamp() });

  test('читає будь-який підтверджений', async () => {
    await assertSucceeds(getDoc(doc(as('emp62'), 'stock', '4823077601234')));
    await assertSucceeds(getDoc(doc(as('emp22'), 'meta', 'stock')));
    await assertSucceeds(getDoc(doc(as('emp22'), 'stores', '22')));
    await assertFails(getDoc(doc(as('new22'), 'stock', '4823077601234')));
    await assertFails(getDoc(doc(anon(), 'stock', '4823077601234')));
  });

  test('пише лише stockAdmin / superAdmin', async () => {
    await assertFails(setDoc(doc(as('emp22'), 'stock', '1'), row({ 22: 1 })));
    await assertFails(setDoc(doc(as('adm22'), 'stock', '1'), row({ 22: 1 })));
    await assertSucceeds(setDoc(doc(as('stocker'), 'stock', '1'), row({ 22: 1, 62: 0 })));
    await assertSucceeds(setDoc(doc(as('sup'), 'stock', '2'), row({ 22: 2.5 })));
    await assertSucceeds(deleteDoc(doc(as('stocker'), 'stock', '1')));
    await assertSucceeds(setDoc(doc(as('stocker'), 'stores', '22'), { name: 'ЧО 22', address: 'вул. Тестова, 1', city: 'Київ', updatedAt: serverTimestamp() }));
    await assertSucceeds(setDoc(doc(as('stocker'), 'meta', 'stock'), { updatedAt: serverTimestamp(), by: 'stocker', rows: 2, stores: 1, file: 'залишки.xlsx' }));
  });

  test('q — map чисел ≥ 0, поля за білим списком', async () => {
    const db = as('stocker');
    await assertFails(setDoc(doc(db, 'stock', '3'), row({ 22: -1 })));
    await assertFails(setDoc(doc(db, 'stock', '3'), row({ 22: '5' })));
    await assertFails(setDoc(doc(db, 'stock', '3'), row([1, 2])));
    await assertFails(setDoc(doc(db, 'stock', '3'), row({ 22: true })));
    await assertFails(setDoc(doc(db, 'stock', '3'), row({ 22: null })));
    await assertFails(setDoc(doc(db, 'stock', '3'), row({ 22: { a: 1 } })));
    await assertFails(setDoc(doc(db, 'stock', '3'), row({ 22: [1] })));
    await assertFails(setDoc(doc(db, 'stock', '3'), row({ 22: -0.5 })));
    await assertSucceeds(setDoc(doc(db, 'stock', '7'), row({})));
    await assertSucceeds(setDoc(doc(db, 'stock', '8'), row({ 22: 0.25, 62: 1e21, 5: 0 })));
    await assertFails(setDoc(doc(db, 'stock', '3'), { ...row({ 22: 1 }), price: 10 }));
    await assertFails(setDoc(doc(db, 'stores', 'ЧО 22'), { name: 'ЧО 22' }));
    await assertFails(setDoc(doc(db, 'stores', '22'), { name: 'ЧО 22', secret: 1 }));
    await assertFails(setDoc(doc(db, 'meta', 'other'), { rows: 1 }));
    await assertFails(setDoc(doc(db, 'meta', 'stock'), { by: 'sup' }));
    // Від'ємне значення далеко в map теж ловиться.
    const stores = (n) => Object.fromEntries(Array.from({ length: n }, (_, i) => [String(i + 1), 1]));
    await assertSucceeds(setDoc(doc(db, 'stock', '4'), row(stores(100))));
    await assertSucceeds(setDoc(doc(db, 'stock', '9'), row(stores(37))));
    await assertFails(setDoc(doc(db, 'stock', '5'), row({ ...stores(100), 100: -3 })));
    await assertFails(setDoc(doc(db, 'stock', '5'), row({ ...stores(37), 37: 'багато' })));
    await assertFails(setDoc(doc(db, 'stock', '6'), row(stores(101))));
  });
});

describe('shops', () => {
  test('code1C змінює адмін свого магазину або мережі', async () => {
    await assertSucceeds(setDoc(doc(as('adm22'), 'shops', '22'), { code1C: '000000022' }, { merge: true }));
    await assertSucceeds(setDoc(doc(as('sup'), 'shops', '62'), { code1C: '000000062' }, { merge: true }));
    await assertFails(setDoc(doc(as('emp22'), 'shops', '22'), { code1C: 'x' }, { merge: true }));
  });

  test('застосунок і далі пише зведення, підмережі й принтери', async () => {
    const db = as('emp22');
    await assertSucceeds(setDoc(doc(as('adm22'), 'shops', '22'), { code1C: '22' }, { merge: true }));
    await assertSucceeds(setDoc(doc(db, 'shops', '22'),
      { region: 'Київ', address: 'вул. Тестова, 1', rows: 5, updatedAt: serverTimestamp(), updatedBy: 'Оля' }, { merge: true }));
    await assertFails(setDoc(doc(db, 'shops', '22'), { rows: 5, updatedAt: serverTimestamp(), code1C: 'інший' }, { merge: true }));
    await assertSucceeds(setDoc(doc(db, 'shops', '22'), { printerSubnets: ['192.0.2.0/24'] }, { merge: true }));
    await assertSucceeds(setDoc(doc(db, 'shops', '22', 'printers', 'g'),
      { model: 'Godex G500', kind: 'label_40x24', ip: '192.0.2.10', port: 9100, transport: 'tcp', lastSeen: serverTimestamp() }, { merge: true }));
    // Назву й щільність — лише адмін.
    await assertFails(updateDoc(doc(db, 'shops', '22', 'printers', 'g'), { name: 'Каса', lastSeen: serverTimestamp() }));
    await assertSucceeds(updateDoc(doc(as('adm22'), 'shops', '22', 'printers', 'g'), { name: 'Каса', lastSeen: serverTimestamp() }));
    await assertSucceeds(deleteDoc(doc(as('sup'), 'shops', '22', 'printers', 'g')));
  });
});

describe('adminLog', () => {
  const entry = (by) => ({ by, at: serverTimestamp(), action: 'approve', target: 'new22', shopId: '22' });

  test('пишуть адміни від свого імені; читає лише superAdmin; змінювати не можна', async () => {
    await assertSucceeds(setDoc(doc(as('adm22'), 'adminLog', 'a'), entry('adm22')));
    await assertSucceeds(setDoc(doc(as('sup'), 'adminLog', 'b'), entry('sup')));
    await assertFails(setDoc(doc(as('adm22'), 'adminLog', 'c'), entry('sup')));
    await assertFails(setDoc(doc(as('emp22'), 'adminLog', 'd'), entry('emp22')));
    await assertFails(getDoc(doc(as('adm22'), 'adminLog', 'a')));
    await assertSucceeds(getDoc(doc(as('sup'), 'adminLog', 'a')));
    await assertFails(updateDoc(doc(as('sup'), 'adminLog', 'a'), { action: 'x' }));
    await assertFails(deleteDoc(doc(as('sup'), 'adminLog', 'a')));
  });
});
