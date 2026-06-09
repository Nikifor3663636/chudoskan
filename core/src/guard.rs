//! Сайт за Cloudflare. Обычный HTTP-клиент иногда получает страницу
//! проверки вместо JSON — тогда Kotlin повторяет запрос через WebView.

use crate::model::BlockKind;

#[uniffi::export]
pub fn detect_block(status: u16, body: &str) -> Option<BlockKind> {
    let head: String = body.chars().take(4000).collect::<String>().to_lowercase();
    let challenge = head.contains("cf-challenge")
        || head.contains("challenge-platform")
        || head.contains("just a moment")
        || head.contains("cf_chl_");
    match status {
        _ if challenge => Some(BlockKind::CloudflareChallenge),
        429 => Some(BlockKind::RateLimited),
        403 | 503 => Some(BlockKind::Forbidden),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn json_is_fine() {
        assert_eq!(detect_block(200, r#"{"html":""}"#), None);
    }

    #[test]
    fn challenge_page() {
        let b = "<html><title>Just a moment...</title><script src=/cdn-cgi/challenge-platform/x>";
        assert_eq!(detect_block(403, b), Some(BlockKind::CloudflareChallenge));
    }
}
