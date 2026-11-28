// Тимчасовий пароль: 10 символів без схожих (0/O, 1/l/I), щоб адмін міг продиктувати.
// Чисті функції без Deno API — тести запускаються й у Node (password.test.ts).

export const ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
export const LENGTH = 10;

/** Рівномірно (без зсуву по модулю): відкидаємо байти ≥ 220 = 4 × 55. */
export function tempPassword(random: (n: number) => Uint8Array = (n) => crypto.getRandomValues(new Uint8Array(n))): string {
  const limit = 256 - (256 % ALPHABET.length);
  let out = "";
  while (out.length < LENGTH) {
    for (const b of random(LENGTH * 2)) {
      if (b < limit && out.length < LENGTH) out += ALPHABET[b % ALPHABET.length];
    }
  }
  return out;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Тіло запиту `{request_id}`; усе інше — помилка 400. */
export function requestId(body: unknown): string | null {
  if (typeof body !== "object" || body === null) return null;
  const id = (body as Record<string, unknown>).request_id;
  return typeof id === "string" && UUID.test(id) ? id.toLowerCase() : null;
}

/** Помилки PostgREST → відповідь застосунку (коди з міграції password_requests). */
export function rpcError(status: number, code: string | undefined): { status: number; error: string } {
  if (code === "42501" || status === 401 || status === 403) return { status: 403, error: "forbidden" };
  if (code === "P0002") return { status: 404, error: "no_employee" };
  if (code === "P0001") return { status: 409, error: "closed" };
  return { status: 502, error: "db" };
}
