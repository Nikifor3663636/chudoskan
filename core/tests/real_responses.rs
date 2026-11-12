//! Тесты на ответах, снятых с chudo-ostriv.com.ua 09.11.2026.
//! Если сайт поменяет разметку — сохраните новый ответ сюда и поправьте парсер.

use chudo_core::graphql::{parse_details, parse_search};
use chudo_core::shops::parse_shops;
use chudo_core::upc::{upc_candidates, upc_title};
use chudo_core::StockStatus;

fn fixture(name: &str) -> String {
    std::fs::read_to_string(format!("{}/tests/fixtures/{name}", env!("CARGO_MANIFEST_DIR"))).expect("fixture")
}

#[test]
fn search_by_article_finds_exact_lego() {
    let r = parse_search(&fixture("search_10698.json"), "10698").unwrap();
    assert_eq!(r.len(), 1);
    let p = &r[0];
    assert!(p.exact_match);
    assert_eq!(p.id, 1233);
    assert_eq!(p.stock, StockStatus::InStock);
    let price = p.price.as_ref().unwrap();
    assert_eq!((price.final_price, price.regular_price), (2189.0, 2499.0));
    assert!(price.discount_percent > 12.0);
}

#[test]
fn exact_match_is_moved_to_top() {
    let r = parse_search(&fixture("search_fuzzy.json"), " 64103 ").unwrap();
    assert_eq!(r[0].id, 36501);
    assert!(r[0].exact_match);
    assert!(r[1..].iter().all(|p| !p.exact_match));
    // плейсхолдер вместо фото отброшен, отсутствующий суффикс → .html
    assert_eq!(r[1].image, None);
    assert_eq!(r[2].url_suffix, ".html");
    assert_eq!(r[2].stock, StockStatus::Unknown);
}

#[test]
fn details_sorted_gallery_and_clean_text() {
    let d = parse_details(&fixture("details_64103.json")).unwrap();
    assert_eq!(d.article.as_deref(), Some("64103"));
    assert_eq!(d.stock, StockStatus::OutOfStock);
    assert_eq!(d.images.len(), 2, "disabled image skipped");
    assert!(d.images[0].ends_with("_1.jpeg"));
    assert!(d.description.starts_with("Комфорт"));
    assert!(!d.description.contains('<'));
    assert_eq!(d.categories.len(), 2);
}

#[test]
fn shops_mixed_availability() {
    let s = parse_shops(&fixture("shops_kyiv_1233.json")).unwrap();
    assert_eq!(s.len(), 3);
    assert_eq!(s.iter().filter(|x| x.available).count(), 2);
    assert!(s[1].address.contains("Борщагівка"));
    assert!(!s[1].available);
}

#[test]
fn upc_resolves_lego_set_number() {
    let json = fixture("upc_5702015357197.json");
    let c = upc_candidates(&json, "5702015357197", 5);
    assert_eq!(c[0], "10698");
    assert!(!c.contains(&"3789627".to_string()), "мусорное поле model не используется");
    assert!(upc_title(&json).unwrap().contains("Lego"));
}
