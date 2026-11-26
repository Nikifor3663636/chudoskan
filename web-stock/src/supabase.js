// Supabase для сторінки залишків: вхід — Firebase, дані — Postgres (Third-Party Auth: Firebase ID token).
import { createClient } from '@supabase/supabase-js';

// Публічні: URL проєкту й publishable key (доступ вирішують RLS-політики, supabase/migrations).
export const SUPABASE_URL = 'https://wbboztsxzuxfocdqjyzk.supabase.co';
export const SUPABASE_KEY = 'sb_publishable_pXZn08SVg5og-I9_PuX5LA_qdTmydyk';

/** firestore | supabase — з config.js (window.CHUDO_BACKEND), інакше firestore. */
export function backend() {
  const b = globalThis.window?.CHUDO_BACKEND;
  return b === 'supabase' ? 'supabase' : 'firestore';
}

/**
 * Supabase приймає Firebase ID token лише з claim role = "authenticated".
 * Якщо його ще немає — ensure-role ставить, і токен оновлюється.
 */
export async function ensureRole(user, url = SUPABASE_URL) {
  const t = await user.getIdTokenResult();
  if (t.claims.role === 'authenticated') return false;
  const r = await fetch(`${url}/functions/v1/ensure-role`, {
    method: 'POST',
    headers: { authorization: `Bearer ${t.token}` },
  });
  if (!r.ok) throw Object.assign(new Error(`ensure-role ${r.status}`), { code: 'ensure-role' });
  await user.getIdToken(true);
  return true;
}

export function supabaseFor(auth, url = SUPABASE_URL, key = SUPABASE_KEY) {
  return createClient(url, key, {
    accessToken: async () => (await auth.currentUser?.getIdToken()) ?? null,
  });
}

/** Помилка PostgREST ({message, code, details}) → Error з тими самими полями. */
export function asError(e) { return e instanceof Error ? e : Object.assign(new Error(e?.message ?? String(e)), e); }

/** Пачки рядків для stock_upload_chunk. */
export const CHUNK = 2000;

export function chunkRows(items, size = CHUNK) {
  const rows = [...items].map(([barcode, it]) => ({ barcode, name: it.name, code: it.code, article: it.article, q: it.q }));
  const out = [];
  for (let i = 0; i < rows.length; i += size) out.push(rows.slice(i, i + size));
  return out;
}

/** Помилка commit: що від нас хоче сервер. */
export function commitProblem(error) {
  const m = error?.message ?? '';
  if (m.includes('confirm_required')) {
    const [file, prev] = String(error.details ?? '').split(',').map(Number);
    return { kind: 'confirm', file, prev };
  }
  if (m.includes('unknown_stores')) return { kind: 'unknown', codes: String(error.details ?? '').split(',').filter(Boolean) };
  return null;
}

/**
 * begin → chunk × N (з прогресом) → commit. Повертає звіт commit або {needConfirm} / {unknown}.
 * sb.rpc повертає {data, error}; помилки PostgREST кидаємо далі.
 */
export async function uploadStock(sb, { fileName, items, shops, confirm = false, onProgress = () => {} }) {
  const begin = await sb.rpc('stock_upload_begin', { p_file_name: fileName, p_source: 'web' });
  if (begin.error) throw asError(begin.error);
  const id = begin.data;
  const chunks = chunkRows(items);
  let sent = 0;
  try {
    for (const rows of chunks) {
      const r = await sb.rpc('stock_upload_chunk', { p_id: id, p_rows: rows });
      if (r.error) throw asError(r.error);
      sent += rows.length;
      onProgress(sent / Math.max(1, items.size), sent);
    }
  } catch (e) {
    await sb.rpc('stock_upload_abort', { p_id: id }).catch(() => {});
    throw e;
  }
  return commitStock(sb, id, shops, confirm);
}

/**
 * commit. Якщо сервер просить підтвердження чи прив'язку складів — сеанс лишається відкритим
 * (staging не чіпається), і commitStock(…, true) можна викликати знову без повторної відправки.
 */
export async function commitStock(sb, id, shops, confirm = false) {
  const c = await sb.rpc('stock_upload_commit', { p_id: id, p_shops: shops, p_confirm: confirm });
  if (c.error) {
    const p = commitProblem(c.error);
    if (p) return { id, problem: p };
    await sb.rpc('stock_upload_abort', { p_id: id }).catch(() => {});
    throw asError(c.error);
  }
  return { id, report: c.data };
}

export const abortStock = (sb, id) => sb.rpc('stock_upload_abort', { p_id: id });
