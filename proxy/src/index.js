// Белый список: хост → разрешённые начала путей. Всё остальное — 403, это не открытый прокси.
const ROUTES = {
  "chudo-ostriv.com.ua": ["/graphql", "/ua/multiwarehouse/", "/ru/multiwarehouse/", "/ua/search_page/", "/ru/search_page/", "/media/"],
  "api.upcitemdb.com": ["/prod/trial/lookup"],
};
const FORWARD_HEADERS = ["content-type", "store", "accept", "accept-language", "x-requested-with"];
const MAX_BODY = 64 * 1024;

function cors(origin, allowed) {
  const h = new Headers({ "Vary": "Origin" });
  if (allowed.includes(origin)) {
    h.set("Access-Control-Allow-Origin", origin);
    h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    h.set("Access-Control-Allow-Headers", "content-type, store, accept, accept-language, x-requested-with, user-agent");
    h.set("Access-Control-Max-Age", "86400");
  }
  return h;
}

export default {
  async fetch(request, env) {
    const allowed = (env.ALLOWED_ORIGINS || "").split(",");
    const origin = request.headers.get("Origin") || "";
    const headers = cors(origin, allowed);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers });
    if (request.method !== "GET" && request.method !== "POST") return new Response("method", { status: 405, headers });
    // Картинки браузер грузит и без Origin; данные — только со страницы ЧудоСкан.
    const url = new URL(request.url);
    const [, host, ...rest] = url.pathname.split("/");
    const path = "/" + rest.join("/");
    const isMedia = path.startsWith("/media/");
    if (!isMedia && !allowed.includes(origin)) return new Response("origin", { status: 403, headers });
    if (!ROUTES[host]?.some((p) => path.startsWith(p))) return new Response("path", { status: 403, headers });

    const out = new Headers({ "User-Agent": "ChudoScan-Web/1.0" });
    for (const k of FORWARD_HEADERS) {
      const v = request.headers.get(k);
      if (v) out.set(k, v);
    }
    let body;
    if (request.method === "POST") {
      body = await request.arrayBuffer();
      if (body.byteLength > MAX_BODY) return new Response("body", { status: 413, headers });
    }
    const upstream = await fetch(`https://${host}${path}${url.search}`, {
      method: request.method,
      headers: out,
      body,
      cf: isMedia ? { cacheTtl: 86400, cacheEverything: true } : undefined,
    });
    const res = new Headers(upstream.headers);
    for (const [k, v] of headers) res.set(k, v);
    res.delete("set-cookie");
    return new Response(upstream.body, { status: upstream.status, headers: res });
  },
};
