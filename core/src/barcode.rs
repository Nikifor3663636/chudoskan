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

#[derive(Debug, PartialEq, thiserror::Error, uniffi::Error)]
pub enum BarcodeError {
    #[error("EAN-13: нужно 12 или 13 цифр")]
    BadLength,
    #[error("EAN-13: допустимы только цифры")]
    NotDigits,
    #[error("EAN-13: неверная контрольная цифра")]
    BadChecksum,
}

/// Коды цифр набора L; R — инверсия L, G — R задом наперёд.
const EAN_L: [u8; 10] = [
    0b0001101, 0b0011001, 0b0010011, 0b0111101, 0b0100011,
    0b0110001, 0b0101111, 0b0111011, 0b0110111, 0b0001011,
];
/// Чётность левой половины по первой цифре: бит 1 — набор G (старший бит — первая цифра половины).
const EAN_PARITY: [u8; 10] = [
    0b000000, 0b001011, 0b001101, 0b001110, 0b010011,
    0b011001, 0b011100, 0b010101, 0b010110, 0b011010,
];

fn ean_check(d: &[u8]) -> u8 {
    let sum: u32 = d.iter().take(12).enumerate().map(|(i, &x)| x as u32 * if i % 2 == 0 { 1 } else { 3 }).sum();
    ((10 - sum % 10) % 10) as u8
}

/// Полные 13 цифр: 13 — проверяем контрольную; 12 — UPC-A (добавляем 0) или EAN-13 без контрольной цифры.
#[uniffi::export]
pub fn ean13_full(code: &str) -> Result<String, BarcodeError> {
    let code = code.trim();
    if !code.chars().all(|c| c.is_ascii_digit()) {
        return Err(BarcodeError::NotDigits);
    }
    let d: Vec<u8> = code.bytes().map(|b| b - b'0').collect();
    match d.len() {
        13 if ean_check(&d) == d[12] => Ok(code.to_string()),
        13 => Err(BarcodeError::BadChecksum),
        12 => {
            let upc: Vec<u8> = std::iter::once(0).chain(d.iter().copied()).collect();
            if ean_check(&upc) == upc[12] {
                Ok(format!("0{code}"))
            } else {
                Ok(format!("{code}{}", ean_check(&d)))
            }
        }
        _ => Err(BarcodeError::BadLength),
    }
}

/// Модули штрихкода EAN-13 слева направо: 95 штук, `true` — чёрный.
#[uniffi::export]
pub fn ean13_bars(code: &str) -> Result<Vec<bool>, BarcodeError> {
    let full = ean13_full(code)?;
    let d: Vec<u8> = full.bytes().map(|b| b - b'0').collect();
    let mut bars = Vec::with_capacity(95);
    let mut push = |bits: u8, n: u32| (0..n).rev().for_each(|i| bars.push(bits >> i & 1 == 1));
    push(0b101, 3);
    let parity = EAN_PARITY[d[0] as usize];
    for (i, &x) in d[1..7].iter().enumerate() {
        let l = EAN_L[x as usize];
        let g = (!l & 0x7f).reverse_bits() >> 1; // R задом наперёд
        push(if parity >> (5 - i) & 1 == 1 { g } else { l }, 7);
    }
    push(0b01010, 5);
    for &x in &d[7..13] {
        push(!EAN_L[x as usize] & 0x7f, 7);
    }
    push(0b101, 3);
    Ok(bars)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn bits(s: &str) -> Vec<bool> {
        s.chars().map(|c| c == '1').collect()
    }

    #[test]
    fn ean13_zeros() {
        let b = ean13_bars("0000000000000").unwrap();
        let want = format!("101{}01010{}101", "0001101".repeat(6), "1110010".repeat(6));
        assert_eq!(b, bits(&want));
    }

    #[test]
    fn ean13_known_code() {
        // 4006381333931: первая 4 → чётность LGLLGG.
        let b = ean13_bars("4006381333931").unwrap();
        assert_eq!(b.len(), 95);
        let left = "0001101 0100111 0101111 0111101 0001001 0110011";
        let right = "1000010 1000010 1000010 1110100 1000010 1100110";
        let want = format!("101{}01010{}101", left.replace(' ', ""), right.replace(' ', ""));
        assert_eq!(b, bits(&want));
    }

    #[test]
    fn ean13_check_digit_and_errors() {
        assert_eq!(ean13_full("400638133393").unwrap(), "4006381333931");
        assert_eq!(ean13_full("036000291452").unwrap(), "0036000291452");
        assert_eq!(ean13_bars("4006381333932"), Err(BarcodeError::BadChecksum));
        assert_eq!(ean13_bars("40063813339"), Err(BarcodeError::BadLength));
        assert_eq!(ean13_bars("40063813339A1"), Err(BarcodeError::NotDigits));
    }

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
