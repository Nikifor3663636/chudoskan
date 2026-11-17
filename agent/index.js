'use strict';

/**
 * ЧудоСкан — агент друку на ПК магазину (поки Godex підключений до комп'ютера по USB).
 *
 * Що робить:
 *  1. Кожні 60 с пише agents/{AGENT_ID}: { shopId, pcName, online, lastSeen, printers: [{ name, isDefault, kind }] }.
 *  2. Слухає printJobs, де agentId == AGENT_ID і status == "queued".
 *  3. Друкує байти задачі (EZPL для Godex, PCL для Brother) напряму в спулер Windows (RAW, raw-print.ps1)
 *     і ставить status: printing → done / error (+ error з текстом).
 *
 * Налаштування — тільки змінні середовища (у репозиторій нічого не потрапляє):
 *  GOOGLE_APPLICATION_CREDENTIALS  шлях до JSON-ключа сервісного акаунта Firebase
 *  SHOP_ID                         id магазину (як у users/{uid}.shopId)
 *  AGENT_ID                        необов'язково; за замовчуванням — ім'я комп'ютера
 *
 * Одноразово записати підмережі принтерів магазину:
 *  node index.js --subnets 192.168.1.0/24,10.0.0.0/24
 */

const os = require('os');
const fs = require('fs');
const path = require('path');
const { execFile } = require('child_process');
const admin = require('firebase-admin');
const { getPrinters, getDefaultPrinter } = require('pdf-to-printer');
const { printerList, parseSubnets } = require('./lib');

const SHOP_ID = process.env.SHOP_ID;
const AGENT_ID = (process.env.AGENT_ID || os.hostname()).toLowerCase();
const HEARTBEAT_MS = 60 * 1000;

if (!process.env.GOOGLE_APPLICATION_CREDENTIALS) {
  console.error('Немає GOOGLE_APPLICATION_CREDENTIALS: вкажи шлях до ключа сервісного акаунта.');
  process.exit(1);
}
if (!SHOP_ID) {
  console.error('Немає SHOP_ID: вкажи id магазину.');
  process.exit(1);
}

admin.initializeApp({ credential: admin.credential.applicationDefault() });
const db = admin.firestore();
const agentRef = db.collection('agents').doc(AGENT_ID);

function log(...a) {
  console.log(new Date().toISOString(), ...a);
}

/** Список принтерів ПК і пульс агента. */
async function heartbeat() {
  let printers = [];
  try {
    const all = await getPrinters();
    const def = await getDefaultPrinter().catch(() => null);
    printers = printerList(all, def && def.name);
  } catch (e) {
    log('Не вдалося отримати список принтерів:', e.message);
  }
  await agentRef.set(
    {
      shopId: SHOP_ID,
      pcName: os.hostname(),
      online: true,
      lastSeen: admin.firestore.FieldValue.serverTimestamp(),
      printers,
    },
    { merge: true },
  );
}

/** RAW у спулер Windows: драйвер не перетворює байти, принтер отримує EZPL/PCL як є. */
function printRaw(printerName, file) {
  return new Promise((resolve, reject) => {
    execFile(
      'powershell.exe',
      ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', path.join(__dirname, 'raw-print.ps1'), '-Printer', printerName, '-File', file],
      { windowsHide: true, timeout: 60 * 1000 },
      (err, stdout, stderr) => (err ? reject(new Error((stderr || err.message).trim())) : resolve(stdout)),
    );
  });
}

/** Одна задача: забрати (транзакція — щоб два агенти не друкували одне), надрукувати, позначити. */
async function handle(doc) {
  const ref = doc.ref;
  const taken = await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists || snap.get('status') !== 'queued') return null;
    tx.update(ref, { status: 'printing', startedAt: admin.firestore.FieldValue.serverTimestamp() });
    return snap.data();
  });
  if (!taken) return;
  const tmp = path.join(os.tmpdir(), `chudoscan-${ref.id}.bin`);
  try {
    const bytes = taken.data && typeof taken.data.toUint8Array === 'function' ? Buffer.from(taken.data.toUint8Array()) : Buffer.from(taken.data || []);
    if (!bytes.length) throw new Error('Порожнє завдання');
    fs.writeFileSync(tmp, bytes);
    await printRaw(taken.printer, tmp);
    await ref.update({ status: 'done', doneAt: admin.firestore.FieldValue.serverTimestamp() });
    log(`Надруковано ${ref.id} на «${taken.printer}» (${bytes.length} байт)`);
  } catch (e) {
    await ref.update({ status: 'error', error: `ПК ${os.hostname()}: ${e.message}`.slice(0, 500) });
    log(`Помилка ${ref.id}:`, e.message);
  } finally {
    fs.rm(tmp, { force: true }, () => {});
  }
}

async function main() {
  const i = process.argv.indexOf('--subnets');
  if (i > 0) {
    const subnets = parseSubnets(process.argv[i + 1]);
    if (!subnets.length) throw new Error('Вкажи підмережі так: --subnets 192.168.1.0/24,10.0.0.0/24');
    await db.collection('shops').doc(SHOP_ID).set(
      { printerSubnets: admin.firestore.FieldValue.arrayUnion(...subnets) },
      { merge: true },
    );
    log('Записано printerSubnets:', subnets.join(', '));
    return;
  }

  await heartbeat();
  setInterval(() => heartbeat().catch((e) => log('Пульс:', e.message)), HEARTBEAT_MS);

  // Задачі по черзі: принтер один, паралельний друк лише перемішає етикетки.
  let chain = Promise.resolve();
  db.collection('printJobs')
    .where('agentId', '==', AGENT_ID)
    .where('status', '==', 'queued')
    .onSnapshot(
      (snap) => snap.docChanges().filter((c) => c.type === 'added').forEach((c) => {
        chain = chain.then(() => handle(c.doc)).catch((e) => log('Задача:', e.message));
      }),
      (e) => log('Слухач задач:', e.message),
    );
  log(`Агент «${AGENT_ID}» магазину ${SHOP_ID} працює.`);

  const stop = async () => {
    await agentRef.set({ online: false }, { merge: true }).catch(() => {});
    process.exit(0);
  };
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
