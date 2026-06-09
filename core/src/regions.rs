//! Города из выпадающего списка «Наявність в магазинах» (снято 09.11.2026).
//! Если сеть откроет новый город — достаточно дописать строку.

use crate::model::Region;

const REGIONS: &[(&str, &str)] = &[
    ("brovari", "Бровари"),
    ("bucha", "Буча"),
    ("vinnica", "Вінниця"),
    ("dnipro", "Дніпро"),
    ("zhitomir", "Житомир"),
    ("ivano-frankivsk", "Івано-Франківськ"),
    ("izmayil", "Ізмаїл"),
    ("kam-janec-podilskij", "Кам'янець-Подільський"),
    ("kam-janske", "Кам'янське"),
    ("kyiv", "Київ"),
    ("kovel", "Ковель"),
    ("kolomija", "Коломия"),
    ("kropivnickij", "Кропивницький"),
    ("luck", "Луцьк"),
    ("lviv", "Львів"),
    ("odesa", "Одеса"),
    ("oleksandriya", "Олександрія"),
    ("poltava", "Полтава"),
    ("rivne", "Рівне"),
    ("stryi", "Стрий"),
    ("ternopil", "Тернопіль"),
    ("uzhgorod", "Ужгород"),
    ("uman", "Умань"),
    ("harkiv", "Харків"),
    ("hmelniickij", "Хмельницький"),
    ("cherkasi", "Черкаси"),
    ("chernivci", "Чернівці"),
    ("chernigiv", "Чернігів"),
    ("chervonohrad", "Шептицький"),
];

pub const DEFAULT_REGION: &str = "odesa";

#[uniffi::export]
pub fn regions() -> Vec<Region> {
    REGIONS
        .iter()
        .map(|&(code, name)| Region { code: code.into(), name: name.into() })
        .collect()
}

#[uniffi::export]
pub fn default_region() -> String {
    DEFAULT_REGION.into()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_is_in_list() {
        assert!(regions().iter().any(|r| r.code == DEFAULT_REGION));
    }
}
