#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("некорректный JSON: {msg}")]
    BadJson { msg: String },
    #[error("ошибка GraphQL: {msg}")]
    GraphQl { msg: String },
    #[error("ошибка распознавания: {msg}")]
    Ai { msg: String },
    #[error("товар не найден")]
    NotFound,
}

impl From<serde_json::Error> for CoreError {
    fn from(e: serde_json::Error) -> Self {
        CoreError::BadJson { msg: e.to_string() }
    }
}
