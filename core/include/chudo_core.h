/* C-интерфейс Rust-ядра ЧудоСкан для iOS (см. core/src/ffi_c.rs). */
#ifndef CHUDO_CORE_H
#define CHUDO_CORE_H

#ifdef __cplusplus
extern "C" {
#endif

/* method — имя функции ядра, args_json — аргументы JSON.
   Возвращает {"ok": ...} или {"err": "..."}; освободить через chudo_free. */
char *chudo_call(const char *method, const char *args_json);
void chudo_free(char *p);

#ifdef __cplusplus
}
#endif

#endif
