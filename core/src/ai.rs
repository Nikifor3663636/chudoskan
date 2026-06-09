//! Распознавание товара по фото (как в старом APK, но с JSON-ответом).
//! Сеть делает Kotlin; здесь только тело запроса и разбор ответа.

use serde::Deserialize;
use serde_json::json;

use crate::error::CoreError;

const PROMPT: &str = "На фото іграшка або дитячий товар. Визнач його для пошуку в українському \
інтернет-магазині. Відповідай ТІЛЬКИ JSON без пояснень: \
{\"brand\": рядок або null, \"name\": рядок, \"article\": рядок або null}. \
article — номер набору або артикул, якщо його видно на упаковці (наприклад 10698, HKB86), інакше null. \
name — бренд і модель коротко, до 6 слів. Якщо на фото не товар — name = \"\".";

pub const DEFAULT_MODEL: &str = "gpt-4o-mini";

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct PhotoGuess {
    pub brand: Option<String>,
    pub name: String,
    pub article: Option<String>,
    /// Что искать на сайте: артикул, если он есть, иначе «бренд назва».
    pub search_query: String,
}

#[uniffi::export]
pub fn openai_url() -> String {
    "https://api.openai.com/v1/chat/completions".into()
}

#[uniffi::export]
pub fn default_ai_model() -> String {
    DEFAULT_MODEL.into()
}

#[uniffi::export]
pub fn build_vision_body(model: &str, jpeg_base64: &str) -> String {
    json!({
        "model": model,
        "max_tokens": 150,
        "response_format": { "type": "json_object" },
        "messages": [{
            "role": "user",
            "content": [
                { "type": "text", "text": PROMPT },
                { "type": "image_url", "image_url": {
                    "url": format!("data:image/jpeg;base64,{jpeg_base64}"),
                    "detail": "low"
                }}
            ]
        }]
    })
    .to_string()
}

#[derive(Deserialize)]
struct ChatResponse {
    #[serde(default)]
    choices: Vec<Choice>,
    error: Option<ApiError>,
}

#[derive(Deserialize)]
struct Choice {
    message: Message,
}

#[derive(Deserialize)]
struct Message {
    content: Option<String>,
}

#[derive(Deserialize)]
struct ApiError {
    message: String,
}

#[derive(Deserialize)]
struct Guess {
    brand: Option<String>,
    #[serde(default)]
    name: String,
    article: Option<String>,
}

fn clean(s: Option<String>) -> Option<String> {
    s.map(|v| v.trim().trim_matches('"').trim().to_string())
        .filter(|v| !v.is_empty() && !v.eq_ignore_ascii_case("null"))
}

fn plausible_article(a: &str) -> bool {
    (3..=20).contains(&a.chars().count()) && a.chars().any(|c| c.is_ascii_digit())
}

/// Разбирает ответ chat/completions. Если модель ответила не JSON,
/// весь текст считается названием (поведение старого APK).
#[uniffi::export]
pub fn parse_vision_response(json: &str) -> Result<PhotoGuess, CoreError> {
    let resp: ChatResponse = serde_json::from_str(json)?;
    if let Some(e) = resp.error {
        return Err(CoreError::Ai { msg: e.message });
    }
    let content = resp
        .choices
        .into_iter()
        .next()
        .and_then(|c| c.message.content)
        .ok_or_else(|| CoreError::Ai { msg: "порожня відповідь".into() })?;

    let body = content.trim().trim_start_matches("```json").trim_matches('`').trim();
    let g = serde_json::from_str::<Guess>(body).unwrap_or(Guess {
        brand: None,
        name: body.lines().next().unwrap_or("").to_string(),
        article: None,
    });

    let brand = clean(g.brand);
    let name = clean(Some(g.name)).unwrap_or_default();
    let article = clean(g.article).filter(|a| plausible_article(a));

    let search_query = match (&article, &brand) {
        (Some(a), _) => a.clone(),
        (None, Some(b)) if !name.to_lowercase().starts_with(&b.to_lowercase()) => format!("{b} {name}"),
        _ => name.clone(),
    };
    let search_query = search_query.split_whitespace().take(6).collect::<Vec<_>>().join(" ");

    if search_query.is_empty() {
        return Err(CoreError::NotFound);
    }
    Ok(PhotoGuess { brand, name, article, search_query })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn wrap(content: &str) -> String {
        json!({ "choices": [{ "message": { "content": content } }] }).to_string()
    }

    #[test]
    fn body_has_image_and_model() {
        let v: serde_json::Value = serde_json::from_str(&build_vision_body("m1", "QUJD")).unwrap();
        assert_eq!(v["model"], "m1");
        let url = v["messages"][0]["content"][1]["image_url"]["url"].as_str().unwrap();
        assert_eq!(url, "data:image/jpeg;base64,QUJD");
    }

    #[test]
    fn article_wins() {
        let g = parse_vision_response(&wrap(r#"{"brand":"LEGO","name":"Classic Creative Box","article":"10698"}"#)).unwrap();
        assert_eq!(g.search_query, "10698");
    }

    #[test]
    fn brand_prefixed_once() {
        let g = parse_vision_response(&wrap(r#"{"brand":"Barbie","name":"Barbie Dreamhouse","article":null}"#)).unwrap();
        assert_eq!(g.search_query, "Barbie Dreamhouse");
        let g = parse_vision_response(&wrap(r#"{"brand":"Intex","name":"матрац велюр","article":"null"}"#)).unwrap();
        assert_eq!(g.search_query, "Intex матрац велюр");
    }

    #[test]
    fn plain_text_fallback_and_fences() {
        let g = parse_vision_response(&wrap("LEGO Duplo потяг")).unwrap();
        assert_eq!(g.search_query, "LEGO Duplo потяг");
        let g = parse_vision_response(&wrap("```json\n{\"name\":\"Hot Wheels\",\"article\":\"HKB86\"}\n```")).unwrap();
        assert_eq!(g.search_query, "HKB86");
    }

    #[test]
    fn errors() {
        let e = parse_vision_response(r#"{"error":{"message":"Incorrect API key"}}"#);
        assert!(matches!(e, Err(CoreError::Ai { msg }) if msg.contains("API key")));
        assert!(matches!(parse_vision_response(&wrap(r#"{"name":""}"#)), Err(CoreError::NotFound)));
    }
}
