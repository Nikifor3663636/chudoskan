// Ставить custom claim role = "authenticated" усім наявним акаунтам Firebase (етап 7).
// Інші claims зберігає. Без нього Supabase не приймає Firebase ID token.
//
//   GOOGLE_APPLICATION_CREDENTIALS=<шлях до ключа сервісного акаунта> node backfill-role.js [--dry-run]
//
// Ключ лише з env; вміст ніде не виводиться.
import { cert, initializeApp } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { readFileSync } from 'node:fs';

const dry = process.argv.includes('--dry-run');
const keyPath = process.env.GOOGLE_APPLICATION_CREDENTIALS;
if (!keyPath) { console.error('GOOGLE_APPLICATION_CREDENTIALS не задано'); process.exit(2); }
initializeApp({ credential: cert(JSON.parse(readFileSync(keyPath, 'utf8'))) });
const auth = getAuth();

let total = 0, set = 0, already = 0, failed = 0;
let page;
do {
  page = await auth.listUsers(1000, page?.pageToken);
  for (const u of page.users) {
    total++;
    const claims = u.customClaims ?? {};
    if (claims.role === 'authenticated') { already++; continue; }
    if (dry) { set++; continue; }
    try {
      await auth.setCustomUserClaims(u.uid, { ...claims, role: 'authenticated' });
      set++;
    } catch (e) {
      failed++;
      console.error(`${u.uid}: ${e.code ?? e.message}`);
    }
  }
} while (page.pageToken);

console.log(`${dry ? '[dry-run] ' : ''}акаунтів ${total} · уже з role ${already} · ${dry ? 'треба поставити' : 'поставлено'} ${set} · помилок ${failed}`);
process.exitCode = failed ? 1 : 0;
