// Доступ до Firebase Auth (Identity Toolkit) від імені сервісного акаунта.
// Секрет FIREBASE_SERVICE_ACCOUNT — JSON ключа (supabase secrets set), у репозиторії його немає.
import { importPKCS8, SignJWT } from "npm:jose@6.2.17";

export const PROJECT = Deno.env.get("FIREBASE_PROJECT_ID") ?? "chudoostriv";

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

export async function identity(path: string, body: unknown) {
  const r = await fetch(`https://identitytoolkit.googleapis.com/v1/projects/${PROJECT}/${path}`, {
    method: "POST",
    headers: { authorization: `Bearer ${await googleToken()}`, "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!r.ok) throw new Error(`identitytoolkit ${path} ${r.status}`);
  return r.json();
}
