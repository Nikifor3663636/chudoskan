// reset-password: адмін магазину задає тимчасовий пароль співробітнику за запитом
// із екрана «Відновлення пароля». Хто кого може скинути — вирішує база
// (password_reset_target / password_reset_finish у міграції password_requests),
// виклики йдуть від імені адміна з його токеном і сесією пристрою.
//
// Виклик: POST /functions/v1/reset-password, Authorization: Bearer <Firebase ID token>,
// x-device-session (якщо є), тіло {"request_id": "<uuid>"}.
// Відповідь: {"password": "<тимчасовий>"} — показується адміну один раз і ніде не зберігається.
import { createRemoteJWKSet, jwtVerify } from "npm:jose@6.2.17";
import { identity, PROJECT } from "../_shared/google.ts";
import { requestId, rpcError, tempPassword } from "./password.ts";

const JWKS = createRemoteJWKSet(
  new URL("https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"),
);
const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, content-type, apikey, x-client-info, x-device-session",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") ?? "";

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "content-type": "application/json", "cache-control": "no-store" },
  });
}

/** RPC від імені адміна: RLS і перевірки в базі бачать саме його. */
async function rpc(fn: string, id: string, req: Request): Promise<{ ok: true; value: unknown } | { ok: false; status: number; error: string }> {
  const headers: Record<string, string> = {
    apikey: ANON_KEY,
    authorization: req.headers.get("authorization") ?? "",
    "content-type": "application/json",
  };
  const session = req.headers.get("x-device-session");
  if (session) headers["x-device-session"] = session;
  const r = await fetch(`${SUPABASE_URL}/rest/v1/rpc/${fn}`, { method: "POST", headers, body: JSON.stringify({ p_id: id }) });
  const text = await r.text();
  if (r.ok) return { ok: true, value: text ? JSON.parse(text) : null };
  let code: string | undefined;
  try {
    code = JSON.parse(text).code;
  } catch { /* не JSON */ }
  return { ok: false, ...rpcError(r.status, code) };
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
  if (req.method !== "POST") return json({ error: "method" }, 405);

  const idToken = (req.headers.get("authorization") ?? "").replace(/^Bearer\s+/i, "");
  let admin: string;
  try {
    const { payload } = await jwtVerify(idToken, JWKS, {
      issuer: `https://securetoken.google.com/${PROJECT}`,
      audience: PROJECT,
      algorithms: ["RS256"],
    });
    if (typeof payload.sub !== "string" || payload.sub === "") throw new Error("sub");
    admin = payload.sub;
  } catch {
    return json({ error: "invalid token" }, 401);
  }

  let id: string | null = null;
  try {
    id = requestId(await req.json());
  } catch { /* порожнє або не JSON */ }
  if (!id) return json({ error: "bad request" }, 400);

  const target = await rpc("password_reset_target", id, req);
  if (!target.ok) return json({ error: target.error }, target.status);
  const uid = target.value;
  if (typeof uid !== "string" || uid === "" || uid === admin) return json({ error: "forbidden" }, 403);

  const password = tempPassword();
  try {
    await identity("accounts:update", { localId: uid, password });
  } catch (e) {
    console.error("reset-password update", (e as Error).message);
    return json({ error: "update failed" }, 502);
  }
  const done = await rpc("password_reset_finish", id, req);
  if (!done.ok) console.error("reset-password finish", done.error);
  return json({ password });
});
