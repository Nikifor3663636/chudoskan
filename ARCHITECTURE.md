# Архітектура ЧудоСкан

З чого складається ЧудоСкан, як частини пов'язані і чому вони влаштовані саме так.
Встановлення й збірка — у [README](README.md).

**Зміст** · [1. Огляд](#1-огляд) · [2. Модулі](#2-модулі) · [3. Rust-ядро](#3-rust-ядро) ·
[4. Мости ядра](#4-мости-ядра) · [5. Android](#5-android) · [6. iPhone і веб](#6-iphone-і-веб) ·
[7. Пошук товару](#7-пошук-товару) · [8. Дані у Firestore](#8-дані-у-firestore) · [9. Вхід і ролі](#9-вхід-і-ролі) ·
[10. Залишки мережі](#10-залишки-мережі) · [11. Друк](#11-друк) · [12. ЧудоСкан Друк](#12-чудоскан-друк) ·
[13. Збірка і CI](#13-збірка-і-ci) · [14. Тести](#14-тести) · [15. Рішення](#15-рішення) · [16. Обмеження](#16-обмеження)

---

## 1. Огляд

Продавець сканує штрихкод і за секунду бачить ціну, залишок у своєму магазині й у магазинах міста,
а цінник друкується однією кнопкою.

**Три принципи:**

| | Принцип | Навіщо |
|---|---|---|
| 🦀 | **Одна логіка даних сайту** — на Rust | Однаково працює на Android, iPhone і в вебі; тести на справжніх відповідях сайту |
| 🧪 | **Ядро без вводу-виводу** | Rust не ходить у мережу: будує запити й розбирає відповіді — легко тестувати |
| 🔒 | **Права вирішує сервер** | Кожен доступ до Firestore перевіряють `firestore.rules`, покриті тестами |

```mermaid
flowchart LR
    subgraph people["Люди"]
        seller(["👤 Продавець"])
        admin(["🧑‍💼 Адмін"])
    end
    subgraph clients["Клієнти"]
        android["📱 Android<br/>app/"]
        ios["🍏 iPhone · 🌐 веб<br/>shared/"]
        pc["💻 ЧудоСкан Друк<br/>printer/"]
    end
    core[["🦀 Rust-ядро<br/>core/"]]
    site[("🌐 chudo-ostriv.com.ua<br/>GraphQL · наявність")]
    fb[("🔥 Firebase<br/>Auth · Firestore")]
    proxy["☁️ Cloudflare Worker<br/>proxy/"]
    brother["🖨 Brother"]
    godex["🏷 Godex"]

    seller & admin --> android & ios
    android & ios --> core
    android --> site
    ios -->|веб| proxy --> site
    android & ios & pc --> fb
    android -->|"TCP 9100"| brother
    pc -->|"RAW у спулер"| godex

    classDef brand fill:#FF7500,stroke:#C25500,color:#fff;
    classDef soft fill:#FFF3E8,stroke:#FF7500,color:#5A2A00;
    class core brand;
    class site,fb,proxy soft;
```

## 2. Модулі

| Модуль | Мова | Призначення |
|---|---|---|
| [`core/`](core) | Rust | Запити до сайту й розбір відповідей, штрихкоди EAN/UPC, міста, розпізнавання блокувань |
| [`app/`](app) | Kotlin, Jetpack Compose | Android: сканер, пошук, картка, залишки, друк, замовлення, адміністрування |
| [`shared/`](shared) | Kotlin Multiplatform, Compose Multiplatform | Екрани iPhone і веб-версії (Wasm), Firebase через REST |
| [`iosApp/`](iosApp) | Swift | Тонка оболонка iPhone для екранів `shared` |
| [`printer/`](printer) | C#, .NET 8, WinForms | «ЧудоСкан Друк» — друк на Godex з ПК магазину |
| [`proxy/`](proxy) | JavaScript | Cloudflare Worker: CORS для веб-версії (білий список шляхів) |
| [`firestore.rules`](firestore.rules) · [`rules-tests/`](rules-tests) | Rules, JS | Хто що бачить у базі, і тести цього на емуляторі |
| [`docs/`](docs) | HTML | Сторінка встановлення (GitHub Pages), відео, знімки |

Залежності спрямовані лише всередину: клієнти залежать від ядра, ядро не знає про клієнтів.

## 3. Rust-ядро

| Файл | Що робить |
|---|---|
| `barcode.rs` | Тип вводу (EAN-13, EAN-8, UPC-A, артикул, текст), контрольна цифра, смуги EAN-13 для цінника |
| `graphql.rs` | Тіла запитів Magento GraphQL (через `variables`) і розбір відповідей; точний збіг артикула |
| `shops.rs` | Розбір HTML наявності по магазинах |
| `upc.rs` | Відповідь UPCitemdb → імовірні артикули з назви |
| `guard.rs` | Сторінка перевірки Cloudflare, 403/429/503 |
| `regions.rs` · `urls.rs` · `model.rs` | Міста сайту, адреси, спільні типи |
| `ffi_c.rs` | C-інтерфейс для iPhone: `chudo_call(метод, JSON)` |

Функції чисті: на вхід рядок — на вихід значення або `CoreError`. У релізі `opt-level = "z"`, LTO,
`panic = "abort"`: бібліотека для arm64 — близько 1,5 МБ.

## 4. Мости ядра

| | Android | iPhone |
|---|---|---|
| Механізм | [UniFFI](https://mozilla.github.io/uniffi-rs/) → Kotlin-обгортка | C ABI → Kotlin/Native `cinterop` |
| Бібліотека | `libchudo_core.so` (cargo-ndk, 3 ABI) | `libchudo_core.a` (статична) |
| Виклик | Типізовані функції | Одна функція `chudo_call("method", "{json}")` |

UniFFI не генерує код для Kotlin/Native, тому для iPhone — JSON-міст. Щоб моделі по обидва боки
не розійшлися, `CoreContractTest` проганяє Kotlin-моделі `shared` через справжнє ядро.

```mermaid
sequenceDiagram
    participant UI as Екран (Kotlin)
    participant Core as Core.kt
    participant Rust as chudo_call (Rust)
    UI->>Core: parseSearch(body, query)
    Core->>Rust: "parse_search", {"json": body, "query": q}
    Rust-->>Core: {"ok": [ProductSummary…]}
    Core-->>UI: List<ProductSummary>
```

## 5. Android

Звичайні шари Compose-застосунку, без DI-фреймворку: усі залежності створює `ChudoApp`.

```mermaid
flowchart TB
    subgraph ui["ui/ — екрани Compose"]
        direction LR
        home["home"]
        scan["scan"]
        results["results"]
        product["product"]
        stock["stock"]
        orders["orders"]
        admin["admin"]
        profile["profile"]
    end
    vm["ViewModel + StateFlow"]
    subgraph data["data/"]
        site["SiteClient<br/>OkHttp + WebView"]
        net["NetworkStockRepository<br/>залишки мережі"]
        auth["AuthRepository"]
        parser["Звіт 1С<br/>xlsx · csv"]
    end
    subgraph print["print/"]
        resolver["PrinterResolver"]
        render["LabelRenderer"]
        tr["TCP 9100 · USB OTG · черга для ПК"]
    end
    ui --> vm --> data
    ui --> print
    site --> core[["uniffi.chudo_core"]]
    render --> core
    resolver --> tr

    classDef brand fill:#FF7500,stroke:#C25500,color:#fff;
    class core brand;
```

- **Камера:** CameraX + ML Kit Barcode; код приймається, якщо збігся у двох кадрах поспіль.
- **Мережа:** OkHttp; якщо Cloudflare повернув сторінку перевірки — повтор через невидимий WebView.
- **Стан:** `StateFlow` у ViewModel, у Compose — `collectAsStateWithLifecycle`.
- **Дизайн:** стиль «Mine», світла й темна тема, фірмовий помаранчевий `#FF7500`.
- **Навігація:** `Пошук · Замовлення · 📷 · Залишки · Профіль`.

## 6. iPhone і веб

Екрани пишуться один раз на Kotlin (Compose Multiplatform) і малюються на iPhone та у браузері (Wasm).
Платформне — через `expect`/`actual`:

| `expect` | Android | iPhone | Веб |
|---|---|---|---|
| Сховище | SharedPreferences | `NSUserDefaults` | `localStorage` |
| HTTP | Ktor + OkHttp | Ktor + Darwin | Ktor + fetch через `proxy/` |
| Сканер | — | AVFoundation | фото камерою |

Firebase на iPhone і в вебі — **через REST** (Identity Toolkit, Secure Token, Firestore REST):
модуль лишається чистим Kotlin без CocoaPods. Проєкт Xcode генерує XcodeGen з `iosApp/project.yml`.

## 7. Пошук товару

Сайт не індексує штрихкоди, тому пошук — ланцюжок кроків, і кожен видно користувачу.

```mermaid
flowchart TD
    in["Ввід: штрихкод · артикул · назва"] --> saved{"Штрихкод уже прив'язано?"}
    saved -->|так| card
    saved -->|ні| site["GraphQL search на сайті"]
    site -->|"точний збіг артикула"| card["Картка товару"]
    site -->|ні| upc["UPCitemdb → артикули з назви"] --> site2["GraphQL по кожному"]
    site2 -->|"знайшли → «Запам'ятати?»"| card
    site2 -->|ні| text["Пошук за назвою → список"]
    card --> avail["Наявність на сайті: є / немає"]
    card --> qty["Кількість з 1С: stock/{штрихкод}<br/>свій магазин і місто"]

    classDef brand fill:#FF7500,stroke:#C25500,color:#fff;
    class card brand;
```

Пошук за **кодом 1С** (`BOC022446`) і артикулом із залишків — на вкладці «Залишки»
(запит `stock` за полями `code` / `article`).

| Що | Як |
|---|---|
| Пошук, картка | `POST /graphql`, заголовок `Store: ua` |
| Артикул | поле `external_id` |
| Наявність | `GET /ua/multiwarehouse/shops/index/?id={id}&region={код}` |
| Штрихкод → товар | збережена прив'язка, далі `api.upcitemdb.com` (≈100 запитів на добу) |

## 8. Дані у Firestore

```mermaid
flowchart LR
    users["users/{uid}<br/>анкета · approved · admin<br/>superAdmin · stockAdmin"]
    shops["shops/{shopId}<br/>адреса · code1C · printerSubnets"]
    printers["shops/{shopId}/printers/{id}<br/>реєстр принтерів"]
    stock["stock/{штрихкод}<br/>q: {«22»: 1, …} · name · code · article"]
    stores["stores/{код ЧО}<br/>назва · місто · shopId"]
    meta["meta/stock<br/>коли й хто оновив"]
    devices["devices/{uid ПК}<br/>shopId"]
    pairing["devicePairing/{код}<br/>15 хв"]
    agents["agents/{uid ПК}<br/>пульс · Godex · черга"]
    jobs["printJobs/{id}<br/>queued → printing → done"]
    log["adminLog/{id}"]

    users --> shops --> printers
    stores --> shops
    stock --> stores
    pairing --> devices --> agents
    jobs --> agents
```

| Колекція | Хто пише | Хто читає |
|---|---|---|
| `users` | сам (анкета), адмін (права) | сам, адмін свого магазину, адмін мережі |
| `shops` · `printers` | адмін магазину / мережі, телефони (реєстр) | співробітники магазину |
| `stock` · `stores` · `meta` | адмін залишків / мережі | усі підтверджені |
| `devicePairing` · `devices` | ПК (код), адмін (підтвердження) | адмін, сам ПК |
| `agents` · `printJobs` | ПК (пульс, статус), співробітник (завдання) | магазин, ПК-адресат |
| `adminLog` | адміни | адмін мережі |

## 9. Вхід і ролі

```mermaid
sequenceDiagram
    actor E as 👤 Співробітник
    participant App as 📱 Застосунок
    participant Auth as Firebase Auth
    participant DB as Firestore
    actor Adm as 🏪 Адмін
    E->>App: ім'я, телефон, пароль, магазин
    App->>Auth: createUser(380…@tel.chudoostriv.app, пароль)
    App->>DB: users/{uid} {approved: false}
    Adm->>DB: approved = true (у застосунку)
    E->>App: вхід за телефоном і паролем
    App->>DB: дані свого магазину й міста
```

- **Логін.** Firebase тримає пароль лише в парі з email, тому логін — службова адреса з номера:
  `+380961234567` → `380961234567@tel.chudoostriv.app`. Користувач її не бачить, SMS не потрібні.
- **Ролі:** співробітник → адмін магазину (`admin`) → адмін мережі (`superAdmin`); окремо — адмін
  залишків (`stockAdmin`). `superAdmin` і `stockAdmin` ставляться лише в консолі Firebase, клієнт їх
  змінити не може.
- **«Працювати як у магазині ЧО NN»** — адмін мережі тимчасово бачить застосунок очима будь-якого магазину.

## 10. Залишки мережі

```mermaid
flowchart LR
    c["1С: «Ведомость по партиям<br/>товаров на складах»"] -->|.xlsx| app["📱 Імпорт<br/>адмін мережі"]
    app --> parse["Потоковий розбір<br/>склади «ЧО NN» у колонках"]
    parse --> diff["Різниця з минулим файлом<br/>(знімок на телефоні)"]
    diff -->|"лише змінене"| stock[("stock/{штрихкод}")]
    diff --> meta[("meta/stock")]
    stock --> card["Картка товару:<br/>свій магазин + місто"]

    classDef brand fill:#FF7500,stroke:#C25500,color:#fff;
    classDef soft fill:#FFF3E8,stroke:#FF7500,color:#5A2A00;
    class app brand;
    class stock,meta soft;
```

- **Формат звіту:** у колонці B — «назва, КОД, артикул» (ділиться з кінця, бо в назвах бувають коми);
  під кожним складом — «Штрих код | Конечный остаток»; порожньо = товару немає. 1С пише
  `xl/SharedStrings.xml` з великої S — частини архіву порівнюються без урахування регістру.
- **Обсяг:** ~24 000 товарів × 46 магазинів; аркуш ~100 МБ XML читається потоково.
- **Зберігання:** документ на товар `stock/{штрихкод}` з мапою `q` — ключ код магазину. Скан — одне читання.
- **Захист:** магазин без товарів у файлі не обнуляється; товарів < 50% від минулого — підтвердження;
  невідомий склад — «Який це магазин?» з підказкою за назвою ТЦ.
- **Ліміт:** перше завантаження ~24 000 записів (більше безкоштовних 20 000 на добу) — застосунок попереджає;
  далі пишуться лише зміни.

## 11. Друк

Продавець нічого не налаштовує: «Цінник» → надруковано.

```mermaid
flowchart LR
    tag["PriceTag<br/>дані 1С + ціна сайту"] --> r["LabelRenderer<br/>один Bitmap: прев'ю = друк"]
    r -->|"40×24 · 203 dpi"| ezpl["EZPL"]
    r -->|"A4 · 300 dpi"| pcl["PCL-растр"]
    ezpl & pcl --> pick{"PrinterResolver"}
    pick -->|"кеш · реєстр · пошук ≤ 6 с"| tcp["TCP 9100 → Brother"]
    pick --> usb["USB OTG"]
    pick -->|Godex через ПК| jobs[("printJobs")] --> pc["💻 ЧудоСкан Друк"]
    pick -.->|"обраний недоступний"| ask["Питання:<br/>друкувати на іншому?"]

    classDef brand fill:#FF7500,stroke:#C25500,color:#fff;
    classDef warn fill:#FFF3E8,stroke:#FF7500,color:#5A2A00;
    class r brand;
    class ask warn;
```

- **Реєстр** `shops/{shopId}/printers/{id}`: id — MAC (SNMP), тому зміна IP не створює дубль.
- **Пошук:** NSD, TCP 9100 по `printerSubnets` і /24 Wi-Fi телефона, SNMP, вебсторінка Godex.
- **Вибір принтера** запам'ятовується окремо для етикеток і A4; якщо обраний не відповідає — питання, а не
  тиха заміна.
- **A4 альбомно:** аркуш малюється 3508×2480 і повертається на 90°; розкладка рахується від друкованої
  області Brother (поле 4,2 мм).
- **EAN-13:** тиха зона 5 модулів, модуль — ціле число точок; тест читає штрихкод зі справжнього Bitmap друку.

## 12. ЧудоСкан Друк

Програма для ПК, до якого кабелем підключено Godex. Без служби Windows, без прав адміністратора і
без ключів сервісного акаунта.

```mermaid
sequenceDiagram
    participant PC as 💻 ЧудоСкан Друк
    participant Auth as Firebase Auth
    participant DB as Firestore
    actor Adm as 🏪 Адмін магазину
    PC->>Auth: анонімний вхід → uid
    PC->>DB: devicePairing/{код} (діє 15 хв)
    Note over PC: великий код у вікні
    Adm->>DB: код у застосунку → devices/{uid} = {shopId}
    DB-->>PC: devices/{uid} з'явився → робочий режим
    loop кожні 60 с
        PC->>DB: agents/{uid}: онлайн, Godex, черга
    end
    DB-->>PC: printJobs (queued, для цього ПК)
    PC->>PC: RAW у спулер Windows → Godex
    PC->>DB: done / error
```

- **Права ПК** — лише його магазин: взяти завдання для себе, змінити їхній статус, писати свій пульс.
  «Відключити» видаляє `devices/{uid}` — доступ закривається одразу.
- **Надійність:** завдання забирається транзакцією; зависле `printing` > 2 хв повертається в чергу один раз;
  `done/error` старші 7 днів видаляються.
- **Вікно:** 🟢 Працює · 🔵 Підключіть комп'ютер · 🔴 Потрібна увага · ⚪ Немає зв'язку; значок біля годинника.
- **Збірка:** `printer.yml` — .NET-тести → установники x64 і x86 (Inno Setup, `PrivilegesRequired=lowest`).

## 13. Збірка і CI

```mermaid
flowchart LR
    push(["git push"]) --> a["android.yml"] --> a1["Rust: fmt · clippy · test"] --> a2["lint · unit-тести · APK"]
    push --> r["rules.yml"] --> r1["емулятор Firestore · rules-tests"]
    push --> p["printer.yml"] --> p1["dotnet test"] --> p2["установники x64 / x86<br/>перевірка на ключі"]
    push --> i["ios.yml"] --> i1["Rust для iOS → фреймворк → xcodebuild"]
    push --> w["pages.yml"] --> w1["веб-версія → GitHub Pages"]
    a2 -.->|"тег vX.Y"| rel1["реліз APK"]
    p2 -.->|"тег printer-vX.Y.Z"| rel2["реліз установників"]
```

Версії залежностей — в одному каталозі [`gradle/libs.versions.toml`](gradle/libs.versions.toml).
Lint Android суворий: попередження — це помилки.

## 14. Тести

| Набір | Де | Що перевіряє |
|---|---|---|
| Ядро | `core/src/**`, `core/tests/real_responses.rs` | Штрихкоди, URL, розбір GraphQL, блокування; справжні відповіді сайту |
| Контракт моделей | `shared/src/androidUnitTest/CoreContractTest.kt` | Kotlin-моделі iPhone-мосту ↔ JSON ядра |
| Залишки мережі | `app/src/test/.../data/Network*Test.kt`, `StoreMatcherTest.kt` | Розбір звіту 1С, різниця, захист, підказка магазину |
| Друк | `app/src/test/.../print/*Test.kt` | EAN-13 (ZXing), EZPL/PCL, розкладка аркушів, вибір принтера без тихої заміни |
| Дизайн | `app/src/test/.../design/DesignSnapshots.kt` | Знімки Paparazzi екранів і цінників |
| Правила | `rules-tests/rules.test.js` | Ролі, свій магазин, підключення ПК, залишки мережі — дозволи й відмови |
| ЧудоСкан Друк | `printer/ChudoScan.Print.Tests` | Godex з WMI, дедуплікація, код, завислі завдання, стан |

Якщо сайт змінить розмітку, першими впадуть тести на `fixtures` — раніше, ніж це помітять продавці.

## 15. Рішення

| Рішення | Чому |
|---|---|
| **Rust для спільної логіки** | Ядро з'явилося раніше iPhone-версії й покрите тестами на справжніх відповідях; маленька нативна бібліотека для всіх платформ |
| **Ядро без мережі** | HTTP потребує платформного (cookies, WebView для Cloudflare); так ядро детерміноване |
| **Два мости** | UniFFI зручніший для Android, але не підтримує Kotlin/Native; для iPhone — C + JSON + контрактний тест |
| **Firebase через REST на iPhone і в вебі** | Потрібно кілька викликів — простіше Ktor, ніж нативний SDK з CocoaPods |
| **Вхід за телефоном без SMS** | SMS у Firebase платні; телефон + пароль безкоштовні, а підтвердження адміном закриває доступ стороннім |
| **ПК входить анонімно й підключається кодом** | Жодних ключів сервісного акаунта на комп'ютерах магазинів; права лише свого магазину |
| **Залишки мережі одним звітом 1С** | Один файл від адміна мережі замість вивантаження в кожному магазині; пишуться лише зміни |
| **Ручний DI** | Один контейнер на десяток залежностей зрозуміліший за Hilt/Koin і не сповільнює збірку |

## 16. Обмеження

- Сайт віддає наявність «є / немає»; кількість — лише зі звіту 1С і на момент завантаження.
- Залишки читаються з Firestore під час скану; без інтернету — лише те, що вже відкривали
  (план — локальна база на телефоні й пакетне зберігання).
- Перше завантаження залишків мережі перевищує безкоштовний добовий ліміт записів Firestore.
- UPCitemdb (безкоштовно) — близько 100 запитів на добу; прив'язані штрихкоди ліміт не витрачають.
- iPhone у TestFlight потребує Apple Developer Program; CI до цього готовий.
- Замовлення поки на демо-даних.
