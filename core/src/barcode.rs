use crate::model::{ClassifiedQuery, QueryKind};

/// Контрольная цифра GTIN (EAN-8/EAN-13/UPC-A): веса 3 и 1 справа налево.
fn gtin_valid(digits: &[u8]) -> bool {
    let Some((&check, body)) = digits.split_last() else {
        return false;
    };
    let sum: u32 = body
        .iter()
        .rev()
        .enumerate()
        .map(|(i, &d)| u32::from(d) * if i % 2 == 0 { 3 } else { 1 })
        .sum();
    (10 - sum % 10) % 10 == u32::from(check)
}

fn to_digits(s: &str) -> Option<Vec<u8>> {
    s.chars()
        .map(|c| c.to_digit(10).map(|d| d as u8))
        .collect()
}

/// Определяет, что ввёл/отсканировал пользователь.
#[uniffi::export]
pub fn classify_query(input: &str) -> ClassifiedQuery {
    let trimmed = input.trim();
    let compact: String = trimmed.chars().filter(|c| !c.is_whitespace()).collect();

    if let Some(d) = to_digits(&compact) {
        match d.len() {
            13 if gtin_valid(&d) => return ean13(compact),
            12 if gtin_valid(&d) => return ean13(format!("0{compact}")),
            8 if gtin_valid(&d) => {
                return ClassifiedQuery { kind: QueryKind::Ean8, normalized: compact }
            }
            _ => {}
        }
    }

    let looks_like_article = !trimmed.contains(' ')
        && (3..=20).contains(&trimmed.chars().count())
        && trimmed.chars().any(|c| c.is_ascii_digit())
        && trimmed.chars().all(|c| c.is_alphanumeric() || c == '-' || c == '.' || c == '/');
    // «M 4428-12» — артикул с пробелом, тоже бывает.
    let spaced_article = trimmed.split(' ').count() == 2
        && trimmed.chars().count() <= 20
        && trimmed.chars().filter(|c| c.is_ascii_digit()).count() >= 3;

    let kind = if looks_like_article || spaced_article {
        QueryKind::Article
    } else {
        QueryKind::Text
    };
    ClassifiedQuery { kind, normalized: trimmed.to_string() }
}

fn ean13(normalized: String) -> ClassifiedQuery {
    ClassifiedQuery { kind: QueryKind::Ean13, normalized }
}

#[uniffi::export]
pub fn is_barcode(kind: QueryKind) -> bool {
    matches!(kind, QueryKind::Ean13 | QueryKind::Ean8)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn valid_ean13() {
        let q = classify_query("5702015357197");
        assert_eq!(q.kind, QueryKind::Ean13);
        assert_eq!(classify_query("4820130620734").kind, QueryKind::Ean13);
    }

    #[test]
    fn bad_checksum_is_not_barcode() {
        assert_eq!(classify_query("5702015357198").kind, QueryKind::Article);
    }

    #[test]
    fn upc_a_becomes_ean13() {
        let q = classify_query("036000291452");
        assert_eq!(q.kind, QueryKind::Ean13);
        assert_eq!(q.normalized, "0036000291452");
    }

    #[test]
    fn articles() {
        for a in ["64103", "HKB86", "SM22009", "G-FL-01-02", "M 4428-12"] {
            assert_eq!(classify_query(a).kind, QueryKind::Article, "{a}");
        }
    }

    #[test]
    fn text() {
        assert_eq!(classify_query("лего поезд").kind, QueryKind::Text);
        assert_eq!(classify_query("Barbie").kind, QueryKind::Text);
    }
}
