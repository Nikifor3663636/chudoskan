#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Lang {
    Ua,
    Ru,
}

impl Lang {
    pub fn code(self) -> &'static str {
        match self {
            Lang::Ua => "ua",
            Lang::Ru => "ru",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StockStatus {
    InStock,
    OutOfStock,
    Unknown,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Price {
    pub final_price: f64,
    pub regular_price: f64,
    pub discount_percent: f64,
    pub currency: String,
}

/// Строка в результатах поиска.
#[derive(Debug, Clone, PartialEq, uniffi::Record, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProductSummary {
    pub id: i64,
    /// Видимый на сайте «Артикул» (в Magento — `external_id`).
    pub article: Option<String>,
    pub name: String,
    pub url_key: String,
    pub url_suffix: String,
    pub stock: StockStatus,
    pub price: Option<Price>,
    pub image: Option<String>,
    /// Артикул совпал с запросом один в один.
    pub exact_match: bool,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProductDetails {
    pub id: i64,
    pub article: Option<String>,
    pub name: String,
    pub url_key: String,
    pub url_suffix: String,
    pub stock: StockStatus,
    pub only_x_left: Option<f64>,
    pub price: Option<Price>,
    pub images: Vec<String>,
    pub description: String,
    pub categories: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ShopAvailability {
    pub address: String,
    pub available: bool,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Region {
    pub code: String,
    pub name: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum QueryKind {
    /// Валидный EAN-13 / UPC-A (нормализован в 13 цифр).
    Ean13,
    Ean8,
    /// Похоже на артикул: короткий токен с цифрами (64103, HKB86, SM22009).
    Article,
    /// Произвольный текст — ищем по названию.
    Text,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ClassifiedQuery {
    pub kind: QueryKind,
    /// Нормализованная строка для поиска.
    pub normalized: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum BlockKind {
    /// Cloudflare отдал страницу проверки браузера.
    CloudflareChallenge,
    /// 403/429 без понятной причины.
    Forbidden,
    RateLimited,
}
