//! C-интерфейс ядра для iOS (Kotlin/Native через cinterop).
//!
//! Одна функция: `chudo_call("метод", "{аргументы JSON}")` → `{"ok": результат}` или `{"err": "текст"}`.
//! Строку-результат освобождает `chudo_free`. Android пользуется UniFFI-обёрткой, а не этим файлом.

use crate::model::{Lang, QueryKind};
use crate::{barcode, graphql, guard, regions, shops, upc, urls};
use serde::Deserialize;
use serde_json::{json, Value};
use std::ffi::{c_char, CStr, CString};

#[derive(Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
struct Args {
    input: String,
    kind: Option<QueryKind>,
    code: String,
    query: String,
    page_size: u32,
    url_key: String,
    url_suffix: String,
    json: String,
    status: u16,
    body: String,
    barcode: String,
    limit: u32,
    lang: Option<Lang>,
    product_id: i64,
    region: String,
}

fn ok<T: serde::Serialize>(v: T) -> Value {
    json!({ "ok": v })
}

fn err(e: impl ToString) -> Value {
    json!({ "err": e.to_string() })
}

/// Выполнить метод ядра; вход и выход — JSON (без C-указателей, удобно тестировать).
pub fn dispatch(method: &str, args_json: &str) -> Value {
    let a: Args = match serde_json::from_str(if args_json.trim().is_empty() { "{}" } else { args_json }) {
        Ok(a) => a,
        Err(e) => return err(format!("bad args: {e}")),
    };
    let lang = a.lang.unwrap_or(Lang::Ua);
    match method {
        "classify_query" => ok(barcode::classify_query(&a.input)),
        "is_barcode" => match a.kind {
            Some(k) => ok(barcode::is_barcode(k)),
            None => err("kind required"),
        },
        "ean13_full" => barcode::ean13_full(&a.code).map(ok).unwrap_or_else(err),
        "ean13_bars" => barcode::ean13_bars(&a.code).map(ok).unwrap_or_else(err),
        "build_search_body" => ok(graphql::build_search_body(&a.query, a.page_size)),
        "build_details_body" => ok(graphql::build_details_body(&a.url_key)),
        "parse_search" => graphql::parse_search(&a.json, &a.query).map(ok).unwrap_or_else(err),
        "parse_details" => graphql::parse_details(&a.json).map(ok).unwrap_or_else(err),
        "parse_shops" => shops::parse_shops(&a.json).map(ok).unwrap_or_else(err),
        "detect_block" => ok(guard::detect_block(a.status, &a.body)),
        "regions" => ok(regions::regions()),
        "default_region" => ok(regions::default_region()),
        "upc_candidates" => ok(upc::upc_candidates(&a.json, &a.barcode, a.limit)),
        "upc_title" => ok(upc::upc_title(&a.json)),
        "graphql_url" => ok(urls::graphql_url()),
        "store_header" => ok(urls::store_header(lang)),
        "product_url" => ok(urls::product_url(lang, &a.url_key, &a.url_suffix)),
        "shops_url" => ok(urls::shops_url(lang, a.product_id, &a.region)),
        "site_search_url" => ok(urls::site_search_url(lang, &a.query)),
        "upc_lookup_url" => ok(urls::upc_lookup_url(&a.barcode)),
        _ => err(format!("unknown method: {method}")),
    }
}

/// # Safety
/// `method` и `args_json` — валидные C-строки UTF-8 (или NULL). Результат освободить через [chudo_free].
#[no_mangle]
pub unsafe extern "C" fn chudo_call(method: *const c_char, args_json: *const c_char) -> *mut c_char {
    let read = |p: *const c_char| if p.is_null() { String::new() } else { CStr::from_ptr(p).to_string_lossy().into_owned() };
    let out = std::panic::catch_unwind(|| dispatch(&read(method), &read(args_json)).to_string())
        .unwrap_or_else(|_| err("panic in core").to_string());
    CString::new(out).unwrap_or_default().into_raw()
}

/// # Safety
/// `p` — строка, полученная из [chudo_call], освобождается один раз.
#[no_mangle]
pub unsafe extern "C" fn chudo_free(p: *mut c_char) {
    if !p.is_null() {
        drop(CString::from_raw(p));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classify_and_enums_use_kotlin_names() {
        let v = dispatch("classify_query", r#"{"input":"4820000000000"}"#);
        assert_eq!(v["ok"]["kind"], "EAN13");
        let v = dispatch("is_barcode", r#"{"kind":"EAN13"}"#);
        assert_eq!(v["ok"], true);
    }

    #[test]
    fn urls_with_lang() {
        let v = dispatch("shops_url", r#"{"lang":"UA","productId":36501,"region":"odesa"}"#);
        assert!(v["ok"].as_str().unwrap().ends_with("/ua/multiwarehouse/shops/index/?id=36501&region=odesa"));
    }

    #[test]
    fn errors_are_reported() {
        assert!(dispatch("parse_details", r#"{"json":"not json"}"#)["err"].is_string());
        assert!(dispatch("nope", "{}")["err"].as_str().unwrap().contains("unknown"));
    }

    #[test]
    fn regions_are_camel_case_records() {
        let v = dispatch("regions", "");
        assert!(v["ok"].as_array().unwrap().iter().any(|r| r["code"] == "odesa"));
    }

    #[test]
    fn c_abi_roundtrip() {
        let m = CString::new("default_region").unwrap();
        let a = CString::new("{}").unwrap();
        unsafe {
            let p = chudo_call(m.as_ptr(), a.as_ptr());
            let s = CStr::from_ptr(p).to_str().unwrap().to_owned();
            chudo_free(p);
            assert_eq!(s, r#"{"ok":"odesa"}"#);
        }
    }
}
