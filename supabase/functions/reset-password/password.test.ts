// Запуск: node --test supabase/functions/reset-password/password.test.ts (або deno test).
import { strict as assert } from "node:assert";
import { test } from "node:test";
import { ALPHABET, LENGTH, requestId, rpcError, tempPassword } from "./password.ts";

test("тимчасовий пароль: 10 символів з алфавіту без схожих", () => {
  for (let i = 0; i < 200; i++) {
    const p = tempPassword();
    assert.equal(p.length, LENGTH);
    for (const c of p) assert.ok(ALPHABET.includes(c), c);
    assert.doesNotMatch(p, /[0O1lI]/);
  }
});

test("байти ≥ 220 відкидаються (без зсуву по модулю)", () => {
  let calls = 0;
  const p = tempPassword((n) => {
    calls++;
    return new Uint8Array(n).fill(calls === 1 ? 255 : 0);
  });
  assert.equal(p, "a".repeat(LENGTH));
  assert.equal(calls, 2);
});

test("паролі різні", () => {
  assert.equal(new Set(Array.from({ length: 50 }, () => tempPassword())).size, 50);
});

test("тіло запиту: лише request_id у форматі uuid", () => {
  const id = "8B0D6A4E-1C2F-4A5B-9C8D-7E6F5A4B3C2D";
  assert.equal(requestId({ request_id: id }), id.toLowerCase());
  assert.equal(requestId({ request_id: "1; drop table" }), null);
  assert.equal(requestId({}), null);
  assert.equal(requestId(null), null);
  assert.equal(requestId("x"), null);
});

test("помилки бази → коди відповіді", () => {
  assert.deepEqual(rpcError(400, "42501"), { status: 403, error: "forbidden" });
  assert.deepEqual(rpcError(401, undefined), { status: 403, error: "forbidden" });
  assert.deepEqual(rpcError(400, "P0002"), { status: 404, error: "no_employee" });
  assert.deepEqual(rpcError(400, "P0001"), { status: 409, error: "closed" });
  assert.deepEqual(rpcError(500, "XX000"), { status: 502, error: "db" });
});
