//! Magento 2 GraphQL сайта (`POST /graphql`, заголовок `Store: ua|ru`).
//! Важно: фильтр по `sku` на сайте отключён, а `sku` — это GUID из 1С.
//! Видимый «Артикул» лежит в `external_id`, поэтому ищем через `search`
//! и уже сами проверяем точное совпадение.

use serde::Deserialize;
use serde_json::json;

use crate::error::CoreError;
use crate::model::{Category, Price, ProductDetails, ProductSummary, StockStatus, Subcategory};

const SUMMARY_FIELDS: &str = "id external_id name url_key url_suffix stock_status \
    price_range{minimum_price{regular_price{value currency} final_price{value currency} discount{percent_off}}} \
    small_image{url}";

const SEARCH_QUERY: &str =
    "query($q:String!,$n:Int!){products(search:$q,pageSize:$n){total_count items{__FIELDS__}}}";

const DETAILS_QUERY: &str = "query($k:String!){products(filter:{url_key:{eq:$k}}){items{\
    id external_id name url_key url_suffix stock_status only_x_left_in_stock \
    price_range{minimum_price{regular_price{value currency} final_price{value currency} discount{percent_off}}} \
    media_gallery{url position disabled} description{html} categories{name}}}}";

/// Тело POST-запроса поиска (переменные — без ручного экранирования).
#[uniffi::export]
pub fn build_search_body(query: &str, page_size: u32) -> String {
    json!({
        "query": SEARCH_QUERY.replace("__FIELDS__", SUMMARY_FIELDS),
        "variables": { "q": query, "n": page_size },
    })
    .to_string()
}

#[uniffi::export]
pub fn build_details_body(url_key: &str) -> String {
    json!({ "query": DETAILS_QUERY, "variables": { "k": url_key } }).to_string()
}

const CATEGORY_FIELDS: &str = "uid name url_key product_count position include_in_menu";

const CATEGORIES_QUERY: &str =
    "{categories(filters:{parent_id:{eq:\"1\"}}){items{product_count children{__F__ children{__F__}}}}}";

const CATEGORY_PRODUCTS_QUERY: &str =
    "query($c:String!,$n:Int!){products(filter:{category_uid:{eq:$c}},pageSize:$n){total_count items{__FIELDS__}}}";

/// Дерево категорій сайту: корінь → верхній рівень меню → підкатегорії.
#[uniffi::export]
pub fn build_categories_body() -> String {
    json!({ "query": CATEGORIES_QUERY.replace("__F__", CATEGORY_FIELDS) }).to_string()
}

/// Товари однієї категорії (у порядку сайту); відповідь розбирає `parse_search`.
#[uniffi::export]
pub fn build_category_products_body(uid: &str, page_size: u32) -> String {
    json!({
        "query": CATEGORY_PRODUCTS_QUERY.replace("__FIELDS__", SUMMARY_FIELDS),
        "variables": { "c": uid, "n": page_size },
    })
    .to_string()
}

// ---------- сырые типы ответа ----------

#[derive(Deserialize)]
struct Envelope<T> {
    data: Option<T>,
    #[serde(default)]
    errors: Vec<GqlError>,
}

#[derive(Deserialize)]
struct GqlError {
    message: String,
}

#[derive(Deserialize)]
struct ProductsData {
    products: Products,
}

#[derive(Deserialize)]
struct Products {
    #[serde(default)]
    items: Vec<RawProduct>,
}

#[derive(Deserialize)]
struct RawProduct {
    id: i64,
    external_id: Option<String>,
    name: String,
    url_key: String,
    url_suffix: Option<String>,
    stock_status: Option<String>,
    only_x_left_in_stock: Option<f64>,
    price_range: Option<RawPriceRange>,
    small_image: Option<RawImage>,
    #[serde(default)]
    media_gallery: Vec<RawMedia>,
    description: Option<RawHtml>,
    #[serde(default)]
    categories: Vec<RawCategory>,
}

#[derive(Deserialize)]
struct RawPriceRange {
    minimum_price: RawMinPrice,
}

#[derive(Deserialize)]
struct RawMinPrice {
    regular_price: RawMoney,
    final_price: RawMoney,
    discount: Option<RawDiscount>,
}

#[derive(Deserialize)]
struct RawMoney {
    value: Option<f64>,
    currency: Option<String>,
}

#[derive(Deserialize)]
struct RawDiscount {
    percent_off: Option<f64>,
}

#[derive(Deserialize)]
struct RawImage {
    url: Option<String>,
}

#[derive(Deserialize)]
struct RawMedia {
    url: Option<String>,
    position: Option<i64>,
    disabled: Option<bool>,
}

#[derive(Deserialize)]
struct RawHtml {
    html: Option<String>,
}

#[derive(Deserialize)]
struct RawCategory {
    name: Option<String>,
}

#[derive(Deserialize)]
struct CategoriesData {
    categories: Option<CategoryList>,
}

#[derive(Deserialize)]
struct CategoryList {
    #[serde(default)]
    items: Vec<RawCategoryNode>,
}

#[derive(Deserialize)]
struct RawCategoryNode {
    uid: Option<String>,
    name: Option<String>,
    url_key: Option<String>,
    product_count: Option<u32>,
    position: Option<i64>,
    /// Magento віддає `1`/`0`; про всяк випадок приймаємо й `true`/`false`.
    include_in_menu: Option<serde_json::Value>,
    #[serde(default)]
    children: Vec<RawCategoryNode>,
}

// ---------- преобразование ----------

fn stock(s: Option<&str>) -> StockStatus {
    match s {
        Some("IN_STOCK") => StockStatus::InStock,
        Some("OUT_OF_STOCK") => StockStatus::OutOfStock,
        _ => StockStatus::Unknown,
    }
}

impl RawPriceRange {
    fn into_price(self) -> Option<Price> {
        let m = self.minimum_price;
        let final_price = m.final_price.value?;
        Some(Price {
            final_price,
            regular_price: m.regular_price.value.unwrap_or(final_price),
            discount_percent: m.discount.and_then(|d| d.percent_off).unwrap_or(0.0),
            currency: m.final_price.currency.unwrap_or_else(|| "UAH".into()),
        })
    }
}

/// Плейсхолдер Magento вместо фото не показываем.
fn real_image(url: Option<String>) -> Option<String> {
    url.filter(|u| !u.contains("/placeholder/"))
}

fn products(json: &str) -> Result<Vec<RawProduct>, CoreError> {
    let env: Envelope<ProductsData> = serde_json::from_str(json)?;
    match (env.data, env.errors.first()) {
        (Some(d), _) => Ok(d.products.items),
        (None, Some(e)) => Err(CoreError::GraphQl { msg: e.message.clone() }),
        (None, None) => Err(CoreError::GraphQl { msg: "пустой ответ".into() }),
    }
}

fn norm(s: &str) -> String {
    s.chars().filter(|c| c.is_alphanumeric()).flat_map(char::to_lowercase).collect()
}

/// Разбирает ответ поиска. Точное совпадение артикула (без учёта регистра,
/// пробелов и дефисов) поднимается наверх и помечается `exact_match`.
#[uniffi::export]
pub fn parse_search(json: &str, query: &str) -> Result<Vec<ProductSummary>, CoreError> {
    let q = norm(query);
    let mut list: Vec<ProductSummary> = products(json)?
        .into_iter()
        .map(|p| {
            let exact_match =
                !q.is_empty() && p.external_id.as_deref().map(norm).as_deref() == Some(q.as_str());
            ProductSummary {
                id: p.id,
                article: p.external_id,
                name: p.name,
                url_key: p.url_key,
                url_suffix: p.url_suffix.unwrap_or_else(|| ".html".into()),
                stock: stock(p.stock_status.as_deref()),
                price: p.price_range.and_then(RawPriceRange::into_price),
                image: real_image(p.small_image.and_then(|i| i.url)),
                exact_match,
            }
        })
        .collect();
    // sort_by_key стабилен: исходный порядок релевантности сохраняется.
    list.sort_by_key(|p| !p.exact_match);
    Ok(list)
}

#[uniffi::export]
pub fn parse_details(json: &str) -> Result<ProductDetails, CoreError> {
    let p = products(json)?.into_iter().next().ok_or(CoreError::NotFound)?;

    let mut media: Vec<RawMedia> =
        p.media_gallery.into_iter().filter(|m| m.disabled != Some(true) && m.url.is_some()).collect();
    media.sort_by_key(|m| m.position.unwrap_or(i64::MAX));
    let images = media.into_iter().filter_map(|m| real_image(m.url)).collect();

    Ok(ProductDetails {
        id: p.id,
        article: p.external_id,
        name: p.name,
        url_key: p.url_key,
        url_suffix: p.url_suffix.unwrap_or_else(|| ".html".into()),
        stock: stock(p.stock_status.as_deref()),
        only_x_left: p.only_x_left_in_stock,
        price: p.price_range.and_then(RawPriceRange::into_price),
        images,
        description: p.description.and_then(|d| d.html).map(|h| html_to_text(&h)).unwrap_or_default(),
        categories: p.categories.into_iter().filter_map(|c| c.name).collect(),
    })
}

impl RawCategoryNode {
    fn count(&self) -> u32 {
        self.product_count.unwrap_or(0)
    }

    fn name(&self) -> String {
        self.name.as_deref().unwrap_or("").split_whitespace().collect::<Vec<_>>().join(" ")
    }

    /// Показуємо лише те, що є в меню сайту, має товари, не тестове
    /// і не дублює всього батька (на сайті «Іграшки» = увесь каталог).
    fn visible(&self, parent_count: u32) -> bool {
        let in_menu = match &self.include_in_menu {
            Some(serde_json::Value::Number(n)) => n.as_i64() != Some(0),
            Some(serde_json::Value::Bool(b)) => *b,
            _ => true,
        };
        let name = self.name().to_lowercase();
        let test = name.contains("тест") || name.contains("test");
        in_menu
            && !test
            && !name.is_empty()
            && self.uid.as_deref().is_some_and(|u| !u.is_empty())
            && self.count() > 0
            && (parent_count == 0 || self.count() < parent_count)
    }
}

/// Відбирає видимі вузли, сортує за позицією в меню (стабільно) і прибирає
/// повтори назв: на сайті є «Конструктори» і «Конструктори » — лишаємо першу.
fn visible_nodes(nodes: Vec<RawCategoryNode>, parent_count: u32) -> Vec<RawCategoryNode> {
    let mut list: Vec<RawCategoryNode> = nodes.into_iter().filter(|n| n.visible(parent_count)).collect();
    list.sort_by_key(|n| n.position.unwrap_or(i64::MAX));
    let mut seen = std::collections::HashSet::new();
    list.retain(|n| seen.insert(n.name().to_lowercase()));
    list
}

#[uniffi::export]
pub fn parse_categories(json: &str) -> Result<Vec<Category>, CoreError> {
    let env: Envelope<CategoriesData> = serde_json::from_str(json)?;
    let roots = match (env.data.and_then(|d| d.categories), env.errors.first()) {
        (Some(c), _) => c.items,
        (None, Some(e)) => return Err(CoreError::GraphQl { msg: e.message.clone() }),
        (None, None) => return Err(CoreError::GraphQl { msg: "пустой ответ".into() }),
    };
    let mut top = Vec::new();
    for root in roots {
        let root_count = root.count();
        top.extend(visible_nodes(root.children, root_count));
    }
    let top = visible_nodes(top, 0);
    Ok(top
        .into_iter()
        .map(|c| {
            let count = c.count();
            let name = c.name();
            Category {
                uid: c.uid.unwrap_or_default(),
                url_key: c.url_key.unwrap_or_default(),
                children: visible_nodes(c.children, count)
                    .into_iter()
                    .map(|s| Subcategory {
                        name: s.name(),
                        count: s.count(),
                        uid: s.uid.unwrap_or_default(),
                        url_key: s.url_key.unwrap_or_default(),
                    })
                    .collect(),
                name,
                count,
            }
        })
        .collect())
}

/// HTML описания → абзацы plain-text через пустую строку.
pub fn html_to_text(html: &str) -> String {
    let doc = scraper::Html::parse_fragment(html);
    let sel = scraper::Selector::parse("h1,h2,h3,h4,p,li").expect("static selector");
    let blocks: Vec<String> = doc
        .select(&sel)
        .map(|e| {
            let t = e.text().collect::<Vec<_>>().join(" ");
            let t = t.split_whitespace().collect::<Vec<_>>().join(" ");
            if e.value().name() == "li" {
                format!("• {t}")
            } else {
                t
            }
        })
        .filter(|t| !t.is_empty())
        .collect();
    if blocks.is_empty() {
        doc.root_element().text().collect::<String>().split_whitespace().collect::<Vec<_>>().join(" ")
    } else {
        blocks.join("\n\n")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn search_body_uses_variables() {
        let b: serde_json::Value = serde_json::from_str(&build_search_body("a\"b", 5)).unwrap();
        assert_eq!(b["variables"]["q"], "a\"b");
        assert_eq!(b["variables"]["n"], 5);
        assert!(b["query"].as_str().unwrap().contains("external_id"));
    }

    #[test]
    fn graphql_error_is_reported() {
        let r = parse_search(r#"{"errors":[{"message":"boom"}]}"#, "x");
        assert!(matches!(r, Err(CoreError::GraphQl { .. })));
    }

    #[test]
    fn html_lists_and_paragraphs() {
        let t = html_to_text("<h2>Заголовок</h2><p>Текст  <a>ссылка</a></p><ul><li>раз</li></ul>");
        assert_eq!(t, "Заголовок\n\nТекст ссылка\n\n• раз");
    }
}

#[cfg(test)]
mod category_tests {
    use super::*;

    fn node(name: &str, count: u32, pos: i64, menu: serde_json::Value) -> serde_json::Value {
        json!({"uid": format!("u-{name}"), "name": name, "url_key": "k", "product_count": count,
               "position": pos, "include_in_menu": menu, "children": []})
    }

    #[test]
    fn categories_body_asks_tree_with_counts() {
        let b: serde_json::Value = serde_json::from_str(&build_categories_body()).unwrap();
        let q = b["query"].as_str().unwrap();
        assert!(q.contains("product_count") && q.contains("children{uid") && q.contains("parent_id"));
        assert!(!q.contains("__F__"));
    }

    #[test]
    fn category_products_body_uses_variables() {
        let b: serde_json::Value = serde_json::from_str(&build_category_products_body("Mjc=", 40)).unwrap();
        assert_eq!(b["variables"]["c"], "Mjc=");
        assert_eq!(b["variables"]["n"], 40);
        assert!(b["query"].as_str().unwrap().contains("category_uid"));
    }

    #[test]
    fn hidden_empty_and_sorted() {
        let json = json!({"data": {"categories": {"items": [{"product_count": 100, "children": [
            node("Б", 10, 5, json!(1)),
            node("А", 20, 1, json!(true)),
            node("Порожня", 0, 0, json!(1)),
            node("Схована", 5, 0, json!(0)),
            node("Усе", 100, 0, json!(1)),
            node("В", 3, 1, json!(1)),
        ]}]}}});
        let c = parse_categories(&json.to_string()).unwrap();
        let names: Vec<_> = c.iter().map(|x| x.name.as_str()).collect();
        assert_eq!(names, ["А", "В", "Б"], "за позицією, рівні — у порядку сайту");
    }

    #[test]
    fn empty_and_error_responses() {
        assert!(parse_categories(r#"{"data":{"categories":{"items":[]}}}"#).unwrap().is_empty());
        assert!(matches!(
            parse_categories(r#"{"errors":[{"message":"x"}]}"#),
            Err(CoreError::GraphQl { .. })
        ));
        assert!(matches!(parse_categories("<html>"), Err(CoreError::BadJson { .. })));
    }
}
