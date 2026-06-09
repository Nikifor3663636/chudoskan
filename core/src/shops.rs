//! Наличие в магазинах: `GET /{lang}/multiwarehouse/shops/index/?id={product_id}&region={code}`
//! Ответ: `{"html": "<div class=\"shops-item\">…"}`; наличие — класс
//! `available` / `not-available` у первого `.icon-block`.

use scraper::{Html, Selector};
use serde::Deserialize;

use crate::error::CoreError;
use crate::model::ShopAvailability;

#[derive(Deserialize)]
struct ShopsResponse {
    #[serde(default)]
    html: String,
}

#[uniffi::export]
pub fn parse_shops(json: &str) -> Result<Vec<ShopAvailability>, CoreError> {
    let resp: ShopsResponse = serde_json::from_str(json)?;
    Ok(parse_shops_html(&resp.html))
}

pub fn parse_shops_html(html: &str) -> Vec<ShopAvailability> {
    let doc = Html::parse_fragment(html);
    let item = Selector::parse(".shops-item").expect("static selector");
    let icon = Selector::parse(".icon-block").expect("static selector");
    let text = Selector::parse(".text").expect("static selector");

    doc.select(&item)
        .filter_map(|el| {
            let address = el
                .select(&text)
                .next()?
                .text()
                .collect::<String>()
                .split_whitespace()
                .collect::<Vec<_>>()
                .join(" ");
            let available = el
                .select(&icon)
                .next()
                .is_some_and(|i| i.value().classes().any(|c| c == "available"));
            (!address.is_empty()).then_some(ShopAvailability { address, available })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_region_gives_empty_list() {
        assert!(parse_shops(r#"{"html":""}"#).unwrap().is_empty());
    }

    #[test]
    fn not_available_is_not_confused_with_available() {
        let html = r#"<div class="shops-item"><div class="icon-block not-available"></div>
            <div class="text">  вул. Тест,   1 </div></div>"#;
        let s = parse_shops_html(html);
        assert_eq!(s, vec![ShopAvailability { address: "вул. Тест, 1".into(), available: false }]);
    }
}
