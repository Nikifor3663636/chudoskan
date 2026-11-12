use crate::model::Lang;

pub const BASE: &str = "https://chudo-ostriv.com.ua";

#[uniffi::export]
pub fn graphql_url() -> String {
    format!("{BASE}/graphql")
}

#[uniffi::export]
pub fn store_header(lang: Lang) -> String {
    lang.code().to_string()
}

#[uniffi::export]
pub fn product_url(lang: Lang, url_key: &str, url_suffix: &str) -> String {
    format!("{BASE}/{}/{url_key}{url_suffix}", lang.code())
}

#[uniffi::export]
pub fn shops_url(lang: Lang, product_id: i64, region: &str) -> String {
    format!("{BASE}/{}/multiwarehouse/shops/index/?id={product_id}&region={region}", lang.code())
}

#[uniffi::export]
pub fn site_search_url(lang: Lang, query: &str) -> String {
    format!("{BASE}/{}/search_page/fast/search/?query={}", lang.code(), encode(query))
}

/// Бесплатный пробный тариф UPCitemdb: ~100 запросов в сутки, без ключа.
#[uniffi::export]
pub fn upc_lookup_url(barcode: &str) -> String {
    format!("https://api.upcitemdb.com/prod/trial/lookup?upc={}", encode(barcode))
}

fn encode(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => (b as char).to_string(),
            _ => format!("%{b:02X}"),
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn urls() {
        assert_eq!(product_url(Lang::Ru, "abc", ".html"), "https://chudo-ostriv.com.ua/ru/abc.html");
        assert!(shops_url(Lang::Ua, 36501, "odesa")
            .ends_with("/ua/multiwarehouse/shops/index/?id=36501&region=odesa"));
        assert!(site_search_url(Lang::Ua, "м 1").ends_with("query=%D0%BC%201"));
    }
}
