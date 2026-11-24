// Тести firestore.rules на емуляторі: npm test (запускає емулятор сам).
import { readFileSync } from 'node:fs';
import { after, before, beforeEach, describe, test } from 'node:test';
import {
  assertFails, assertSucceeds, initializeTestEnvironment,
} from '@firebase/rules-unit-testing';
import {
  Bytes, Timestamp, addDoc, collection, deleteDoc, doc, getDoc, getDocs, query, serverTimestamp,
  setDoc, updateDoc, where, writeBatch,
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
    await assertSucceeds(setDoc(doc(as('stocker'), 'meta', 'stock'), { updatedAt: serverTimestamp(), updatedBy: 'Залишки', items: 2, stores: 1 }));
  });

  test('пакет залишків: читає підтверджений, пише stockAdmin, ≤ 800 КБ', async () => {
    const part = (n, extra = {}) => ({ v: '1732440000000', i: 0, n: 2, data: Bytes.fromUint8Array(new Uint8Array(n)), ...extra });
    await assertSucceeds(setDoc(doc(as('stocker'), 'stockPacks', '1732440000000_0'), part(800000, { changed: Bytes.fromUint8Array(new Uint8Array(10)) })));
    await assertSucceeds(getDoc(doc(as('emp22'), 'stockPacks', '1732440000000_0')));
    await assertFails(getDoc(doc(as('new22'), 'stockPacks', '1732440000000_0')));
    await assertFails(setDoc(doc(as('emp22'), 'stockPacks', 'x_0'), part(10)));
    await assertFails(setDoc(doc(as('stocker'), 'stockPacks', 'x_1'), part(800001)));
    await assertFails(setDoc(doc(as('stocker'), 'stockPacks', 'x_2'), { ...part(10), data: 'base64' }));
    await assertFails(setDoc(doc(as('stocker'), 'stockPacks', 'x_3'), part(10, { extra: 1 })));
    await assertSucceeds(setDoc(doc(as('stocker'), 'meta', 'stock'), { updatedAt: serverTimestamp(), items: 2, stores: 1, pack: '1732440000000', parts: 2 }));
    await assertFails(setDoc(doc(as('stocker'), 'meta', 'stock'), { pack: 1, parts: 2 }));
    await assertSucceeds(deleteDoc(doc(as('stocker'), 'stockPacks', '1732440000000_0')));
    await assertFails(deleteDoc(doc(as('emp22'), 'stockPacks', '1732440000000_0')));
  });

  test('meta/stock.bytes і лічильник видалення старих stock: лише stockAdmin, ≤ 5 000 на добу', async () => {
    await assertSucceeds(setDoc(doc(as('stocker'), 'meta', 'stock'), { pack: '1', parts: 2, bytes: 1100000 }));
    await assertFails(setDoc(doc(as('stocker'), 'meta', 'stock'), { pack: '1', parts: 2, bytes: '1 МБ' }));
    const c = (extra = {}) => ({ day: '2026-11-24', deleted: 450, remaining: 21550, at: serverTimestamp(), by: 'Залишки', ...extra });
    await assertSucceeds(setDoc(doc(as('stocker'), 'meta', 'stockCleanup'), c()));
    await assertSucceeds(getDoc(doc(as('stocker'), 'meta', 'stockCleanup')));
    await assertFails(getDoc(doc(as('emp22'), 'meta', 'stockCleanup')));
    await assertFails(setDoc(doc(as('adm22'), 'meta', 'stockCleanup'), c()));
    await assertFails(setDoc(doc(as('stocker'), 'meta', 'stockCleanup'), c({ deleted: 5001 })));
    await assertFails(setDoc(doc(as('stocker'), 'meta', 'stockCleanup'), c({ extra: true })));
    await assertFails(setDoc(doc(as('stocker'), 'meta', 'stockCleanup'), c({ day: 'сьогодні' })));
    await assertFails(setDoc(doc(as('stocker'), 'meta', 'other'), c()));
    await assertFails(deleteDoc(doc(as('stocker'), 'meta', 'stockCleanup')));
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
    await assertFails(setDoc(doc(db, 'meta', 'stock'), { items: 1, file: 'x.xlsx' }));
    await assertFails(setDoc(doc(db, 'meta', 'stock'), { items: '1' }));
    await assertFails(setDoc(doc(db, 'meta', 'stock'), { changedAfter: 'вчора' }));
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

describe('імпорт залишків мережі (етап 3)', () => {
  test('stockAdmin: список магазинів, прив\'язка складу, склад, підсумок, журнал — як робить застосунок', async () => {
    const db = as('stocker');
    await assertSucceeds(getDocs(collection(db, 'shops')));
    await assertSucceeds(setDoc(doc(db, 'stores', '09'), { name: 'ЧО 09 Біла Церква (ТЦ Гермес)', city: 'Біла Церква' }, { merge: true }));
    // «Який це магазин?»: stores/{код}.shopId і shops/{shopId}.code1C одним пакетом.
    const b = writeBatch(db);
    b.set(doc(db, 'stores', '22'), { shopId: '22' }, { merge: true });
    b.set(doc(db, 'shops', '22'), { code1C: '22' }, { merge: true });
    await assertSucceeds(b.commit());
    await assertSucceeds(setDoc(doc(db, 'meta', 'stock'), { updatedAt: serverTimestamp(), updatedBy: 'Залишки', items: 23780, stores: 46, changedAfter: Timestamp.now() }));
    await assertSucceeds(addDoc(collection(db, 'adminLog'),
      { by: 'stocker', at: serverTimestamp(), action: 'stockImport', details: 'магазинів 46 · товарів 23780' }));
    // Інші дії в журнал stockAdmin не пише.
    await assertFails(addDoc(collection(db, 'adminLog'), { by: 'stocker', at: serverTimestamp(), action: 'approve' }));
  });

  test('пачка 450 товарів по 46 магазинів проходить правила', async () => {
    const db = as('stocker');
    const q = Object.fromEntries(Array.from({ length: 46 }, (_, i) => [String(i + 1).padStart(2, '0'), (i % 5) + 1]));
    const b = writeBatch(db);
    for (let i = 0; i < 450; i++) {
      b.set(doc(db, 'stock', String(4820000000000 + i)), { q, name: `Товар ${i}, з комою`, code: 'BOC022446', article: '', updatedAt: serverTimestamp() });
    }
    await assertSucceeds(b.commit());
  });

  test('звичайний співробітник і адмін магазину не імпортують', async () => {
    for (const who of ['emp22', 'adm22']) {
      const db = as(who);
      await assertFails(getDocs(collection(db, 'shops')));
      await assertFails(setDoc(doc(db, 'stores', '22'), { shopId: '22' }, { merge: true }));
      await assertFails(setDoc(doc(db, 'meta', 'stock'), { items: 1 }));
      await assertFails(setDoc(doc(db, 'stock', '1'), { q: { 22: 1 }, name: 'x', code: '', article: '' }));
    }
    // Адмін ЧО 22 не ставить code1C чужому магазину.
    await assertFails(setDoc(doc(as('adm22'), 'shops', '62'), { code1C: '62' }, { merge: true }));
  });
});

describe('адміністрування мережі (етап 6)', () => {
  test('superAdmin працює як у ЧО 62: залишки, зведення, принтери', async () => {
    const db = as('sup');
    await assertSucceeds(getDocs(collection(db, 'shops', '62', 'stock')));
    await assertSucceeds(setDoc(doc(db, 'shops', '62', 'stock', 'bc_1'), { article: '1', barcode: '1', name: 'x', qty: 2 }));
    await assertSucceeds(setDoc(doc(db, 'shops', '62'), { region: 'Одеса', address: 'вул. Інша, 2', rows: 1, updatedAt: serverTimestamp(), updatedBy: 'Мережа' }, { merge: true }));
    await assertSucceeds(getDoc(doc(db, 'shops', '62', 'printers', 'g')));
    await assertSucceeds(getDocs(collection(db, 'agents')));
    // Співробітник ЧО 22 у чужий магазин — ні.
    await assertFails(getDocs(collection(as('emp22'), 'shops', '62', 'stock')));
  });

  test('дії з анкетами з approvedBy і changedAt, кожна — в adminLog', async () => {
    const sup = as('sup');
    // Відхилити: непідтверджений без магазину.
    await assertSucceeds(updateDoc(doc(sup, 'users', 'new62'), { approved: false, admin: false, shopId: '', approvedBy: 'sup', changedAt: serverTimestamp() }));
    // Перевести з ЧО 22 у ЧО 62.
    await assertSucceeds(updateDoc(doc(sup, 'users', 'emp22'), { shopId: '62', shop: 'вул. Інша, 2', region: 'Одеса', admin: false, approvedBy: 'sup', changedAt: serverTimestamp() }));
    await assertSucceeds(addDoc(collection(sup, 'adminLog'), { by: 'sup', action: 'transfer', target: 'emp22', shopId: '62', at: serverTimestamp() }));
    await assertSucceeds(addDoc(collection(sup, 'adminLog'), { by: 'sup', action: 'actAs', target: '62', shopId: '62', at: serverTimestamp() }));
    // «Мій магазин»: адмін ЧО 22 вимикає доступ своєму співробітнику, але не переводить.
    await assertSucceeds(updateDoc(doc(as('adm22'), 'users', 'new22'), { approved: true, approvedBy: 'adm22', changedAt: serverTimestamp() }));
    await assertSucceeds(updateDoc(doc(as('adm22'), 'users', 'new22'), { approved: false, admin: false, approvedBy: 'adm22', changedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(as('adm22'), 'users', 'new22'), { shopId: '', approvedBy: 'adm22' }));
    await assertSucceeds(addDoc(collection(as('adm22'), 'adminLog'), { by: 'adm22', action: 'disable', target: 'new22', shopId: '22', at: serverTimestamp() }));
    // Журнал читає лише адмін мережі.
    await assertSucceeds(getDocs(collection(sup, 'adminLog')));
    await assertFails(getDocs(collection(as('adm22'), 'adminLog')));
  });
});

describe('Термінал: sales', () => {
  const item = (over = {}) => ({ barcode: '2000999154010', name: 'Пакет', code: 'BOC022446', article: '', qty: 2, price: 2490, manualPrice: false, ...over });
  const sale = (by, over = {}) => ({
    shopId: '22', by, at: serverTimestamp(), items: [item(), item({ barcode: '', qty: 1, price: 12900, manualPrice: true })],
    discount: 1000, total: 17880, status: 'draft', code: 'AB23CD', ...over,
  });

  test('створює співробітник свого магазину, читає лише свій магазин', async () => {
    await assertSucceeds(setDoc(doc(as('emp22'), 'sales', 's1'), sale('emp22')));
    await assertSucceeds(getDoc(doc(as('emp22'), 'sales', 's1')));
    await assertSucceeds(getDoc(doc(as('adm22'), 'sales', 's1')));
    await assertSucceeds(getDoc(doc(as('sup'), 'sales', 's1')));
    await assertSucceeds(getDocs(query(collection(as('emp22'), 'sales'), where('shopId', '==', '22'))));
    // Чужий магазин прочитати не може.
    await assertFails(getDoc(doc(as('emp62'), 'sales', 's1')));
    await assertFails(getDoc(doc(as('adm62'), 'sales', 's1')));
    await assertFails(getDocs(collection(as('emp62'), 'sales')));
    await assertFails(getDoc(doc(as('new22'), 'sales', 's1')));
    await assertFails(getDoc(doc(anon(), 'sales', 's1')));
  });

  test('створення: свій магазин, автор — я, білий список, total ≥ 0, items ≤ 200', async () => {
    const db = as('emp22');
    await assertFails(setDoc(doc(as('emp62'), 'sales', 'x1'), sale('emp62')));
    await assertFails(setDoc(doc(as('new22'), 'sales', 'x2'), sale('new22')));
    await assertFails(setDoc(doc(db, 'sales', 'x3'), sale('adm22')));
    await assertFails(setDoc(doc(db, 'sales', 'x4'), sale('emp22', { total: -1 })));
    await assertFails(setDoc(doc(db, 'sales', 'x5'), sale('emp22', { total: 10.5 })));
    await assertFails(setDoc(doc(db, 'sales', 'x6'), sale('emp22', { items: [] })));
    await assertFails(setDoc(doc(db, 'sales', 'x7'), sale('emp22', { items: Array.from({ length: 201 }, () => item()) })));
    await assertSucceeds(setDoc(doc(db, 'sales', 'x8'), sale('emp22', { items: Array.from({ length: 200 }, () => item()) })));
    await assertFails(setDoc(doc(db, 'sales', 'x9'), sale('emp22', { payment: { rrn: '1' } })));
    await assertFails(setDoc(doc(db, 'sales', 'x10'), sale('emp22', { status: 'paid' })));
    await assertFails(setDoc(doc(db, 'sales', 'x11'), sale('emp22', { code: 'abc' })));
    await assertFails(setDoc(doc(db, 'sales', 'x12'), sale('emp22', { items: [item({ price: -5 })] })));
    await assertFails(setDoc(doc(db, 'sales', 'x13'), sale('emp22', { items: [item({ secret: 1 })] })));
    await assertFails(setDoc(doc(db, 'sales', 'x14'), sale('emp22', { at: Timestamp.fromMillis(0) })));
  });

  test('статус змінює лише автор або адмін магазину, інші поля — ні', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'sales', 's1'), { ...sale('emp22'), at: Timestamp.now() });
    });
    await assertSucceeds(updateDoc(doc(as('emp22'), 'sales', 's1'), { status: 'sent' }));
    await assertSucceeds(updateDoc(doc(as('adm22'), 'sales', 's1'), { status: 'cancelled' }));
    await assertFails(updateDoc(doc(as('adm62'), 'sales', 's1'), { status: 'draft' }));
    await assertFails(updateDoc(doc(as('emp62'), 'sales', 's1'), { status: 'draft' }));
    await assertFails(updateDoc(doc(as('emp22'), 'sales', 's1'), { total: 0 }));
    await assertFails(updateDoc(doc(as('emp22'), 'sales', 's1'), { status: 'refunded' }));
    await assertFails(deleteDoc(doc(as('adm22'), 'sales', 's1')));
    // Інший співробітник того ж магазину — не автор.
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'users', 'emp22b'), { name: 'Інна', approved: true, shopId: '22' });
    });
    await assertFails(updateDoc(doc(as('emp22b'), 'sales', 's1'), { status: 'draft' }));
  });
});

describe('оплата: стан продажу лише вперед', () => {
  const base = (status, over = {}) => ({
    shopId: '22', by: 'emp22', at: Timestamp.now(), status, code: 'AB23CD', discount: 0, total: 4980,
    items: [{ barcode: '1', name: 'Пакет', code: '', article: '', qty: 2, price: 2490, manualPrice: false }], ...over,
  });
  const put = async (id, data) => env.withSecurityRulesDisabled(async (ctx) => { await setDoc(doc(ctx.firestore(), 'sales', id), data); });
  const pay = (over = {}) => ({ provider: 'fake', rrn: '432812345678', authCode: 'A1B2C3', maskedPan: '4149 49** **** 1234', amountKop: 4980, at: Timestamp.now(), ...over });
  // Зміна статусу — разом із записом у журнал, як у застосунку.
  const step = (db, id, from, to, extra = {}, by = 'emp22') => {
    const b = writeBatch(db);
    b.update(doc(db, 'sales', id), { status: to, ...extra });
    b.set(doc(collection(db, 'sales', id, 'log')), { by, at: serverTimestamp(), from, to });
    return b.commit();
  };

  test('дозволені переходи телефону проходять, назад і через крок — ні', async () => {
    const db = as('emp22');
    await put('s', base('draft'));
    await assertFails(step(db, 's', 'draft', 'paying'));
    await assertFails(step(db, 's', 'draft', 'paid', { payment: pay() }));
    await assertSucceeds(step(db, 's', 'draft', 'sent'));
    await assertFails(step(db, 's', 'sent', 'draft'));
    await assertSucceeds(step(db, 's', 'sent', 'paying'));
    // У "paying" кошик зафіксовано.
    await assertFails(updateDoc(doc(db, 'sales', 's'), { total: 100 }));
    await assertFails(updateDoc(doc(db, 'sales', 's'), { items: [] }));
    await assertFails(step(db, 's', 'paying', 'paid'));
    await assertFails(step(db, 's', 'paying', 'paid', { payment: pay({ amountKop: 100 }) }));
    await assertFails(step(db, 's', 'paying', 'paid', { payment: pay({ secretKey: 'x' }) }));
    await assertSucceeds(step(db, 's', 'paying', 'paid', { payment: pay() }));
    await assertFails(step(db, 's', 'paid', 'paying'));
    await assertFails(step(db, 's', 'paid', 'draft'));
    // fiscalized і exported1C ставить лише ПК магазину.
    await assertFails(step(db, 's', 'paid', 'fiscalized'));
  });

  test('відхилено або скасовано — назад у draft; чернетку можна редагувати', async () => {
    const db = as('emp22');
    await put('d', base('paying'));
    await assertSucceeds(step(db, 'd', 'paying', 'declined'));
    await assertSucceeds(step(db, 'd', 'declined', 'draft'));
    await assertSucceeds(updateDoc(doc(db, 'sales', 'd'), { total: 2490, items: [{ barcode: '1', name: 'Пакет', code: '', article: '', qty: 1, price: 2490, manualPrice: false }] }));
    await put('c', base('paying'));
    await assertSucceeds(step(db, 'c', 'paying', 'cancelled'));
    await assertSucceeds(step(db, 'c', 'cancelled', 'draft'));
  });

  test('клієнт не пише fiscal, exported1C, verified', async () => {
    const db = as('emp22');
    await put('p', base('paid', { payment: pay() }));
    await assertFails(updateDoc(doc(db, 'sales', 'p'), { fiscal: { fiscalNumber: '1', at: Timestamp.now() } }));
    await assertFails(updateDoc(doc(db, 'sales', 'p'), { exported1C: Timestamp.now() }));
    await assertFails(updateDoc(doc(db, 'sales', 'p'), { verified: true }));
    await assertFails(updateDoc(doc(as('adm22'), 'sales', 'p'), { verified: true }));
    await assertFails(updateDoc(doc(as('sup'), 'sales', 'p'), { verified: true }));
    await assertFails(setDoc(doc(db, 'sales', 'n1'), { ...base('draft'), at: serverTimestamp(), verified: true }));
  });

  test('ПК магазину: звірка, фіскалізація, 1С — лише вперед і лише свій магазин', async () => {
    const pc = as('pc22');
    await put('p', base('paid', { payment: pay() }));
    await assertSucceeds(updateDoc(doc(pc, 'sales', 'p'), { verified: true }));
    await assertFails(updateDoc(doc(pc, 'sales', 'p'), { status: 'exported1C', exported1C: Timestamp.now() }));
    await assertFails(updateDoc(doc(pc, 'sales', 'p'), { status: 'fiscalized', fiscal: { fiscalNumber: '', at: Timestamp.now() } }));
    await assertSucceeds(updateDoc(doc(pc, 'sales', 'p'), { status: 'fiscalized', fiscal: { fiscalNumber: '4000123456', prro: 'bank', at: Timestamp.now() } }));
    await assertFails(updateDoc(doc(pc, 'sales', 'p'), { total: 1 }));
    await assertSucceeds(updateDoc(doc(pc, 'sales', 'p'), { status: 'exported1C', exported1C: Timestamp.now() }));
    await assertFails(updateDoc(doc(pc, 'sales', 'p'), { status: 'paid' }));
    await assertFails(updateDoc(doc(as('pc62'), 'sales', 'p'), { verified: false }));
  });

  test('журнал — лише разом зі справжньою зміною статусу', async () => {
    const db = as('emp22');
    await put('j', base('draft'));
    await assertFails(addDoc(collection(db, 'sales', 'j', 'log'), { by: 'emp22', at: serverTimestamp(), from: 'draft', to: 'paid' }));
    await assertSucceeds(step(db, 'j', 'draft', 'sent'));
    await assertSucceeds(getDocs(collection(db, 'sales', 'j', 'log')));
    await assertFails(getDocs(collection(as('emp62'), 'sales', 'j', 'log')));
  });

  test('повернення — окремий документ із посиланням на продаж', async () => {
    const db = as('emp22');
    await put('r', base('paid', { payment: pay() }));
    const refund = (over = {}) => ({ saleId: 'r', shopId: '22', by: 'emp22', createdAt: serverTimestamp(), provider: 'fake', rrn: 'R1', amountKop: 4980, at: Timestamp.now(), ...over });
    const both = (over = {}) => {
      const b = writeBatch(db);
      b.set(doc(collection(db, 'refunds')), refund(over));
      b.update(doc(db, 'sales', 'r'), { status: 'refunded' });
      b.set(doc(collection(db, 'sales', 'r', 'log')), { by: 'emp22', at: serverTimestamp(), from: 'paid', to: 'refunded' });
      return b.commit();
    };
    await assertFails(addDoc(collection(db, 'refunds'), refund()));
    await assertFails(both({ amountKop: 4981 }));
    await assertSucceeds(both());
    await assertFails(both());
  });

  test('shops.payment — лише адмін мережі, лише ідентифікатори', async () => {
    await assertSucceeds(setDoc(doc(as('sup'), 'shops', '22'), { payment: { provider: 'fake', terminalId: 'T0000001' } }, { merge: true }));
    await assertSucceeds(getDoc(doc(as('emp22'), 'shops', '22')));
    await assertFails(setDoc(doc(as('adm22'), 'shops', '22'), { payment: { provider: 'none' } }, { merge: true }));
    await assertFails(setDoc(doc(as('sup'), 'shops', '22'), { payment: { provider: 'privat', apiKey: 'x' } }, { merge: true }));
    await assertFails(setDoc(doc(as('sup'), 'shops', '22'), { payment: { provider: 'Privat Bank' } }, { merge: true }));
  });
});

describe('автооновлення: meta/app', () => {
  const sha = 'a'.repeat(64);
  const app = (over = {}) => ({
    versionCode: 1042, versionName: '3.1', apkUrl: 'https://drive.google.com/uc?export=download&id=1AbCdEfGhIjKlMnOpQrStUvWxYz',
    sha256: sha, minVersionCode: 0, notes: 'Термінал', source: 'drive', publishedBy: 'Мережа', publishedAt: serverTimestamp(), ...over,
  });

  test('читає будь-який підтверджений, пише лише superAdmin', async () => {
    await assertSucceeds(setDoc(doc(as('sup'), 'meta', 'app'), app()));
    await assertSucceeds(getDoc(doc(as('emp22'), 'meta', 'app')));
    await assertFails(getDoc(doc(as('new22'), 'meta', 'app')));
    await assertFails(getDoc(doc(anon(), 'meta', 'app')));
    await assertFails(setDoc(doc(as('adm22'), 'meta', 'app'), app()));
    await assertFails(setDoc(doc(as('stocker'), 'meta', 'app'), app()));
    await assertFails(setDoc(doc(as('emp22'), 'meta', 'app'), { minVersionCode: 0 }, { merge: true }));
    await assertFails(setDoc(doc(as('supNotApproved'), 'meta', 'app'), app()));
  });

  test('суперадмін піднімає мінімальну версію без релізу; білий список полів', async () => {
    await assertSucceeds(setDoc(doc(as('sup'), 'meta', 'app'), { minVersionCode: 1042, message: 'Оновіться до 3.1' }, { merge: true }));
    await assertSucceeds(setDoc(doc(as('sup'), 'meta', 'app'), { source: 'github' }, { merge: true }));
    await assertFails(setDoc(doc(as('sup'), 'meta', 'app'), app({ token: 'x' })));
    await assertFails(setDoc(doc(as('sup'), 'meta', 'app'), app({ sha256: 'abc' })));
    await assertFails(setDoc(doc(as('sup'), 'meta', 'app'), app({ apkUrl: 'http://insecure/app.apk' })));
    await assertFails(setDoc(doc(as('sup'), 'meta', 'app'), app({ source: 'ftp' })));
    await assertFails(setDoc(doc(as('sup'), 'meta', 'app'), app({ minVersionCode: '5' })));
    await assertFails(deleteDoc(doc(as('sup'), 'meta', 'app')));
  });
});
