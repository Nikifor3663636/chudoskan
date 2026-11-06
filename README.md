# ЧудоСкан 2.0 — сканер наличия для «Чудо Острів»

![ЧудоСкан](docs/screenshots/showcase.png)

Описание проекта, скриншоты и ссылки на приложения — в [PROJECT.md](PROJECT.md).

Версия 2.0 — обновление старого APK `com.chudoostriv.app` 1.0: тот же пакет и `versionCode 2`,
поэтому ставится поверх старой версии.

Android-приложение: сканируешь штрихкод или вводишь артикул → карточка товара с сайта
chudo-ostriv.com.ua, кнопка «Відкрити на сайті» и наличие **в реальных магазинах** выбранного города.

- **Rust** (`core/`) — разбор ответов сайта, логика штрихкодов, сборка запросов. Без сети, покрыт тестами.
- **Kotlin** (`app/`) — Jetpack Compose UI, CameraX + ML Kit, OkHttp, корутины.
- Связка — **UniFFI** (Kotlin-обёртка сгенерирована: `app/src/main/java/uniffi/chudo_core/`).

## Что изменилось по сравнению со старым APK (1.0)

| | 1.0 | 2.0 |
|---|---|---|
| Камера | только фото → ChatGPT | живой сканер штрихкодов (ML Kit); фото-режим через OpenAI убран |
| Поиск | HTML `/ua/catalogsearch/result/` + Jsoup-селекторы | GraphQL сайта, точное совпадение артикула |
| Наличие | угаданные селекторы `.store-item` — не совпадали с сайтом | реальный эндпоинт `multiwarehouse/shops`, 29 городов |
| Штрихкод без артикула | — | UPCitemdb → артикул, «запам'ятати» связку |
| Kidsвіт | поиск по `/catalogsearch` | убран: сайт переехал на Prom.ua, `/catalogsearch` отдаёт 404, а поиск по «lego», «лялька», «Hot Wheels» — 0 товаров (09.11.2026) |

### Чтобы встало поверх старой версии

Android принимает обновление, только если подпись совпадает. Старый APK подписан debug-ключом
(`CN=Android Debug`). Собирай 2.0 **на том же компьютере**, где собирал 1.0 (`%USERPROFILE%\.android\debug.keystore`).
На другом компьютере будет ошибка «приложение не установлено» — тогда удали старую версию.

## Что выяснил анализ сайта (09.11.2026)

| Что | Как устроено |
|---|---|
| Движок | Magento 2, тема Brander, за Cloudflare |
| Поиск / карточка | Открытый **GraphQL** `POST /graphql`, заголовок `Store: ua\|ru` |
| Артикул с сайта | поле `external_id`. `sku` — это GUID из 1С, фильтр по `sku` отключён |
| Наличие в магазинах | `GET /{ua\|ru}/multiwarehouse/shops/index/?id={product_id}&region={код}` → `{"html": …}`, класс `available` / `not-available` |
| Города | 29 кодов (`odesa`, `kyiv`, …) — `core/src/regions.rs` |
| Штрихкоды | **сайт их не индексирует**: EAN LEGO 10698 → 0 результатов, артикул «10698» → 1 |

Отсюда цепочка поиска в `ChudoRepository.lookup`:

1. штрихкод, который ты уже привязал («Так, запам'ятати») — открывается мгновенно;
2. поиск по сайту как есть (работает для артикула, названия и брендов, у которых EAN = артикул, например Artos Games);
3. штрихкод → открытая база **UPCitemdb** → вероятные артикулы из названий (`10698`) → поиск каждого;
4. название из UPCitemdb → текстовый поиск.

Если Cloudflare отвечает страницей проверки, запрос автоматически повторяется из невидимого WebView (`WebViewFetcher`).

## Вход сотрудников и остатки магазина (Firebase)

Приложение открывается только после входа **по номеру телефона и паролю**. Сотрудник регистрируется
(имя, телефон, пароль, город, свой магазин) — без SMS (SMS в Firebase только на платном тарифе Blaze); администратор подтверждает его, и во вкладке «Остатки» сотрудник видит количество штук **только в своём магазине**.
Количество берётся из выгрузки 1С (Excel `.xlsx` или CSV), которую сотрудник загружает кнопкой «Загрузить выгрузку 1С».
Файл заменяет остатки магазина целиком; товар ищется по артикулу с сайта, затем по штрихкоду.

Сменить пароль — Профіль → «Змінити пароль» (нужен текущий). Забыл пароль — администратор задаёт новый: Admin SDK / Identity Toolkit `accounts:update` с `password` для uid сотрудника (в консоли Firebase задать пароль нельзя — там только письмо, а почта служебная). Простой вариант без скриптов: удалить пользователя в Authentication и его документ `users/{uid}` — сотрудник зарегистрируется заново, и его снова подтверждают.
Firebase хранит пароль только в паре с email, поэтому логин — служебный адрес из номера
(`+380961234567` → `380961234567@tel.chudoostriv.app`, см. `AuthRepository.loginFor`); пользователь его не видит, писем на него нет.

Firestore: `users/{uid}` — анкета (`shop`, `shopId`, `approved`), `shops/{shopId}/stock/{артикул}` — остатки.
Доступ ограничивают правила [`firestore.rules`](firestore.rules): читать и писать остатки может только подтверждённый
сотрудник этого магазина.

Подключение (один раз):

1. [console.firebase.google.com](https://console.firebase.google.com) → **Add project**.
2. **Add app → Android**, пакет `com.chudoostriv.app` → скачать `google-services.json` в папку `app/` (в git не попадает).
3. **Authentication → Sign-in method** → включить **Email/Password** (вход по номеру и паролю).
4. **Firestore Database → Create database** (регион `europe-west`) → вкладка **Rules** → вставить `firestore.rules` → **Publish**.
5. Собрать APK заново.

Подтвердить сотрудника: **Firestore → users → документ сотрудника → `approved` = `true`**.
Если сотрудник перешёл в другой магазин — удалить его документ в `users`: при следующем входе приложение
попросит выбрать магазин заново, и анкету нужно будет подтвердить ещё раз.

## Печать ценников

Профиль → «Принтери». Любой принтер, три способа подключения (`print/`):

| Подключение | Как | Код |
|---|---|---|
| Сеть магазина | скан /24 на :9100 + mDNS; модель по SNMP (`hrDeviceDescr`/`sysDescr`), Godex по `~HI`; при смене IP принтер находится заново по отпечатку | `PrinterDiscovery`, `Snmp` |
| Wi-Fi принтера (без роутера) | Brother Wireless Direct `DIRECT-…`, Wi-Fi Direct, AP-режим: `WifiNetworkSpecifier` на время печати (Android 10+), адрес — шлюз сети | `WifiDirectLink` |
| Bluetooth | спаренный принтер, SPP (RFCOMM) | `BluetoothLink` |

Липкие этикетки — картинка ценника в языке принтера: ZPL (Zebra, Godex), TSPL (TSC, Xprinter и др.), EPL, ESC/POS
(`LabelEncoder`, байты проверены в `app/src/test`). Язык угадывается по модели, меняется в карточке принтера; «Тест» печатает пробную этикетку.
A4 — системный друк Android (любой принтер с сервисом печати) или PDF напрямую.
SSID и пароль Wi-Fi у Godex/Brother задаются с самого принтера или программой производителя: открытой команды для этого у них нет.

## Сборка APK (Windows)

Нужно: Android Studio (SDK + **NDK** через SDK Manager → SDK Tools), Rust (`rustup`).

```powershell
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
```

Дальше открыть папку в Android Studio → **Build → Generate App Bundles or APKs → Build APK(s)**.
Gradle сам вызовет `cargo ndk` (задача `:app:buildRust`) и положит `.so` в `app/src/main/jniLibs/`.

Из консоли:

```powershell
.\gradlew.bat assembleDebug        # app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat assembleRelease      # подписан debug-ключом — для личного использования
```

Если `.so` уже собраны и Rust не менялся: `skipRust=true` в `gradle.properties`.

> Версии: AGP 8.13 + Kotlin 2.2 — проверенная связка. Android Studio предложит перейти на AGP 9.x
> (Upgrade Assistant), это можно сделать позже.

## Rust-ядро

```bash
cd core
cargo test            # 26 тестов, в т.ч. на реальных ответах сайта (tests/fixtures)
cargo clippy --all-targets -- -D warnings
```

После изменения публичного API ядра перегенерировать Kotlin-обёртку:

```powershell
.\scripts\gen-bindings.ps1
```

## Структура

```
core/src/
  barcode.rs   EAN-13/EAN-8/UPC-A: контрольная цифра, артикул или текст
  graphql.rs   тела запросов (через variables) и разбор ответов, точное совпадение артикула
  shops.rs     разбор наличия по магазинам
  upc.rs       UPCitemdb → кандидаты-артикулы
  guard.rs     распознавание блокировки Cloudflare
  urls.rs / regions.rs
app/src/main/java/ua/chudo/scanner/
  data/        SiteClient (OkHttp + WebView-фолбэк), ChudoRepository, AppPrefs
  ui/home      поиск, сканирование, город, недавние
  ui/scan      CameraX + ML Kit, сканер штрихкодов, ліхтарик
  ui/results   этапы поиска, список, «не знайшли»
  ui/product   галерея, ціна, наявність у магазинах, «запам'ятати штрихкод»
docs/               логотип и скриншоты
```

## Ограничения

- Наличие на сайте — да/нет по магазину, без количества.
- UPCitemdb (пробный тариф) — около 100 запросов в сутки. Привязанные штрихкоды в лимит не входят.
- Приложение читает публичные данные сайта; если разметка поменяется, тесты в `core/tests` покажут, что сломалось.
