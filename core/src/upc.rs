//! Сайт штрихкоды не индексирует (проверено: EAN LEGO 10698 → 0 результатов,
//! артикул «10698» → 1). Поэтому штрихкод сначала резолвим через открытую
//! базу UPCitemdb, вытаскиваем из названий вероятные артикулы и ищем их.

use std::collections::HashMap;

use serde::Deserialize;

#[derive(Deserialize)]
struct UpcResponse {
    #[serde(default)]
    items: Vec<UpcItem>,
}

#[derive(Deserialize)]
struct UpcItem {
    title: Option<String>,
    brand: Option<String>,
    #[serde(default)]
    offers: Vec<UpcOffer>,
}

#[derive(Deserialize)]
struct UpcOffer {
    title: Option<String>,
}

/// Кандидаты-артикулы по убыванию уверенности (не больше `limit`).
/// Поле `model` в UPCitemdb часто мусорное, поэтому опираемся на названия:
/// артикул обычно повторяется во многих офферах.
#[uniffi::export]
pub fn upc_candidates(json: &str, barcode: &str, limit: u32) -> Vec<String> {
    let Ok(resp) = serde_json::from_str::<UpcResponse>(json) else {
        return Vec::new();
    };

    let mut score: HashMap<String, (u32, usize)> = HashMap::new();
    let mut order = 0usize;
    for item in &resp.items {
        let brand = item.brand.as_deref().unwrap_or("").to_lowercase();
        let titles = item.title.iter().chain(item.offers.iter().filter_map(|o| o.title.as_ref()));
        for title in titles {
            for tok in title.split(|c: char| !c.is_alphanumeric() && c != '-') {
                let tok = tok.trim_matches('-');
                if !is_candidate(tok, barcode, &brand) {
                    continue;
                }
                let e = score.entry(tok.to_uppercase()).or_insert((0, order));
                e.0 += 1;
                order += 1;
            }
        }
    }

    let mut ranked: Vec<(String, (u32, usize))> = score.into_iter().collect();
    ranked.sort_by(|a, b| b.1 .0.cmp(&a.1 .0).then(a.1 .1.cmp(&b.1 .1)));
    ranked.into_iter().take(limit as usize).map(|(t, _)| t).collect()
}

/// Название + бренд для поиска по тексту, если артикулов не нашлось.
#[uniffi::export]
pub fn upc_title(json: &str) -> Option<String> {
    let resp = serde_json::from_str::<UpcResponse>(json).ok()?;
    let item = resp.items.into_iter().next()?;
    item.title.filter(|t| !t.trim().is_empty())
}

fn is_candidate(tok: &str, barcode: &str, brand: &str) -> bool {
    let len = tok.chars().count();
    let digits = tok.chars().filter(char::is_ascii_digit).count();
    if !(4..=12).contains(&len) || digits == 0 || tok == barcode {
        return false;
    }
    if tok.to_lowercase() == brand {
        return false;
    }
    // Длинные чисто цифровые строки — это другие штрихкоды/коды магазинов.
    if digits == len && len > 8 {
        return false;
    }
    // Размеры и единицы: 1000pcs, 30cm, 2019 (год) — шум.
    let lower = tok.to_lowercase();
    let unit = ["cm", "mm", "pcs", "pc", "kg", "lbs", "ml", "in"]
        .iter()
        .any(|u| lower.ends_with(u) && lower[..lower.len() - u.len()].chars().all(|c| c.is_ascii_digit()));
    let year = len == 4 && digits == 4 && (lower.starts_with("19") || lower.starts_with("20"));
    !unit && !year
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn picks_repeated_set_number() {
        let json = r#"{"items":[{"title":"Lego Classic 10698 Box 2015","brand":"Lego","model":"3789627",
            "offers":[{"title":"LEGO DUPLO L Creative Brick Box - 10698"},{"title":"LEGO Classic (10698) 30cm"}]}]}"#;
        let c = upc_candidates(json, "5702015357197", 3);
        assert_eq!(c.first().map(String::as_str), Some("10698"));
        assert!(!c.iter().any(|t| t == "2015" || t == "30CM"));
    }

    #[test]
    fn garbage_json_gives_nothing() {
        assert!(upc_candidates("not json", "1", 3).is_empty());
    }
}
