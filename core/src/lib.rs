//! Ядро приложения: ничего не знает о сети, только строит запросы
//! и разбирает ответы. HTTP делает Kotlin (OkHttp / WebView).

uniffi::setup_scaffolding!();

pub mod barcode;
pub mod error;
pub mod ffi_c;
pub mod graphql;
pub mod guard;
pub mod model;
pub mod regions;
pub mod shops;
pub mod upc;
pub mod urls;

pub use error::CoreError;
pub use model::*;
