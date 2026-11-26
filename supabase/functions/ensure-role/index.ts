// ensure-role: ставить у Firebase-акаунт claim role = "authenticated", без якого Supabase
// не приймає Firebase ID token. Інших claims не додає: усі права — у таблицях Postgres.
//
// Виклик: POST /functions/v1/ensure-role, Authorization: Bearer <Firebase ID token>.
// Відповідь: {changed: true} — клієнт робить getIdToken(true); {changed: false} — role уже є.
// Секрет FIREBASE_SERVICE_ACCOUNT — JSON ключа сервісного акаунта (supabase secrets set).
import { createRemoteJWKSet, importPKCS8, jwtVerify, SignJWT } from "npm:jose@6.2.17";

const PROJECT = Deno.env.get("FIREBASE_PROJECT_ID") ?? "chudoostriv";
const JWKS = createRemoteJWKSet(
  new URL("https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"),
);
const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, content-type, apikey, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

type ServiceAccount = { client_email: string; private_key: string };
let cached: { token: string; exp: number } | null = null;

async function googleToken(): Promise<string> {
  if (cached && cached.exp > Date.now() + 60_000) return cached.token;
  const sa = JSON.parse(Deno.env.get("FIREBASE_SERVICE_ACCOUNT") ?? "{}") as ServiceAccount;
  if (!sa.client_email || !sa.private_key) throw new Error("FIREBASE_SERVICE_ACCOUNT is not set");
  const key = await importPKCS8(sa.private_key, "RS256");
  const now = Math.floor(Date.now() / 1000);
  const assertion = await new SignJWT({ scope: "https://www.googleapis.com/auth/identitytoolkit" })
    .setProtectedHeader({ alg: "RS256", typ: "JWT" })
    .setIssuer(sa.client_email).setSubject(sa.client_email)
    .setAudience("https://oauth2.googleapis.com/token")
    .setIssuedAt(now).setExpirationTime(now + 3600)
    .sign(key);
  const r = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
  });
  if (!r.ok) throw new Error(`oauth ${r.status}`);
  const j = await r.json();
  cached = { token: j.access_token, exp: Date.now() + j.expires_in * 1000 };
  return cached.token;
}

async function identity(path: string, body: unknown) {
  const r = await fetch(`https://identitytoolkit.googleapis.com/v1/projects/${PROJECT}/${path}`, {
    method: "POST",
    headers: { authorization: `Bearer ${await googleToken()}`, "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!r.ok) throw new Error(`identitytoolkit ${path} ${r.status}`);
  return r.json();
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { ...CORS, "content-type": "application/json" } });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
  if (req.method !== "POST") return json({ error: "method" }, 405);

  const idToken = (req.headers.get("authorization") ?? "").replace(/^Bearer\s+/i, "");
  let uid: string;
  let role: unknown;
  try {
    const { payload } = await jwtVerify(idToken, JWKS, {
      issuer: `https://securetoken.google.com/${PROJECT}`,
      audience: PROJECT,
      algorithms: ["RS256"],
    });
    if (typeof payload.sub !== "string" || payload.sub === "") throw new Error("sub");
    uid = payload.sub;
    role = payload.role;
  } catch {
    return json({ error: "invalid token" }, 401);
  }
  if (role === "authenticated") return json({ changed: false });

  try {
    // Наявні custom claims зберігаємо, додаємо лише role.
    const found = await identity("accounts:lookup", { localId: [uid] });
    const user = found.users?.[0];
    if (!user) return json({ error: "no user" }, 404);
    const claims = user.customAttributes ? JSON.parse(user.customAttributes) : {};
    if (claims.role === "authenticated") return json({ changed: true });
    claims.role = "authenticated";
    await identity("accounts:update", { localId: uid, customAttributes: JSON.stringify(claims) });
    return json({ changed: true });
  } catch (e) {
    console.error("ensure-role", uid, (e as Error).message);
    return json({ error: "update failed" }, 502);
  }
});
