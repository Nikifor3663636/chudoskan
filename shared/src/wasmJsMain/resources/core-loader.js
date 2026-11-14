// Rust-ядро ЧудоСкан в WebAssembly: chudo_call(метод, JSON) → JSON, как на iPhone (core/src/ffi_c.rs).
globalThis.chudoCoreReady = (async () => {
  const { instance } = await WebAssembly.instantiateStreaming(fetch("chudo_core.wasm"), {});
  const e = instance.exports;
  const enc = new TextEncoder();
  const dec = new TextDecoder();
  const put = (s) => {
    const bytes = enc.encode(s);
    const p = e.chudo_alloc(bytes.length);
    new Uint8Array(e.memory.buffer, p, bytes.length).set(bytes);
    return p;
  };
  const get = (p) => {
    const mem = new Uint8Array(e.memory.buffer);
    let end = p;
    while (mem[end]) end++;
    return dec.decode(mem.subarray(p, end));
  };
  globalThis.chudoCore = {
    call(method, args) {
      const m = put(method);
      const a = put(args);
      try {
        const r = e.chudo_call(m, a);
        try { return get(r); } finally { e.chudo_free(r); }
      } finally {
        e.chudo_free(m);
        e.chudo_free(a);
      }
    },
  };
})();
