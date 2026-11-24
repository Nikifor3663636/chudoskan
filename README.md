<p align="center">
  <img src="docs/logo.png" alt="Чудо Острів" width="340">
</p>

<h1 align="center">ЧудоСкан</h1>

<p align="center">
  <b>Помічник продавця «Чудо Острів» у кишені.</b><br>
  Навів камеру на коробку — і за секунду видно ціну, скільки товару у твоєму магазині
  й у магазинах міста. Цінник — однією кнопкою.
</p>

<p align="center">
  <a href="https://github.com/Nikifor3663636/chudoskan/actions/workflows/android.yml"><img src="https://github.com/Nikifor3663636/chudoskan/actions/workflows/android.yml/badge.svg" alt="Android"></a>
  <a href="https://github.com/Nikifor3663636/chudoskan/actions/workflows/printer.yml"><img src="https://github.com/Nikifor3663636/chudoskan/actions/workflows/printer.yml/badge.svg" alt="ЧудоСкан Друк"></a>
  <a href="https://github.com/Nikifor3663636/chudoskan/actions/workflows/rules.yml"><img src="https://github.com/Nikifor3663636/chudoskan/actions/workflows/rules.yml/badge.svg" alt="Правила Firestore"></a>
  <a href="https://github.com/Nikifor3663636/chudoskan/releases/latest"><img src="https://img.shields.io/github/v/release/Nikifor3663636/chudoskan?label=%D0%B2%D0%B5%D1%80%D1%81%D1%96%D1%8F&color=FF7500" alt="Версія"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Windows-10%20%7C%2011-0078D4?logo=windows&logoColor=white" alt="Windows 10, 11">
</p>

<p align="center">
  <a href="https://nikifor3663636.github.io/chudoskan/"><img src="https://img.shields.io/badge/%E2%AC%87%20%D0%92%D1%81%D1%82%D0%B0%D0%BD%D0%BE%D0%B2%D0%B8%D1%82%D0%B8-FF7500?style=for-the-badge" alt="Встановити"></a>
  &nbsp;
  <a href="ARCHITECTURE.md"><img src="https://img.shields.io/badge/%D0%90%D1%80%D1%85%D1%96%D1%82%D0%B5%D0%BA%D1%82%D1%83%D1%80%D0%B0-2B2B2B?style=for-the-badge" alt="Архітектура"></a>
</p>

<p align="center">
  <a href="docs/media/chudoskan-promo.mp4">
    <img src="docs/media/chudoskan-promo.gif" alt="ЧудоСкан за 30 секунд" width="780">
  </a>
</p>

<table>
<tr>
<td width="33%" valign="top">

### 📷 Скан
Камера читає штрихкод — відкривається картка, як на сайті: фото, ціна, акція, опис.

</td>
<td width="33%" valign="top">

### 📦 Залишки
«У вашому магазині: 3 шт» і скільки в інших магазинах міста — точні числа з 1С.

</td>
<td width="33%" valign="top">

### 🏷 Цінники
Одна кнопка — цінник, як у 1С: на Brother по Wi-Fi або на Godex через ПК.

</td>
</tr>
</table>

**Зміст** · [Як це працює](#-як-це-працює) · [Встановлення](#-встановлення) · [Можливості](#-можливості) ·
[Друк](#-друк) · [Залишки мережі](#-залишки-мережі) · [Ролі](#-ролі) · [Для розробника](#-для-розробника) ·
[Безпека](#-безпека) · [Дорожня карта](#-дорожня-карта)

---

## 🧭 Як це працює

```mermaid
flowchart LR
    seller(["👤 Продавець"]) --> app["📱 ЧудоСкан<br/>Android · iPhone · веб"]
    admin(["🧑‍💼 Адмін мережі"]) -->|"звіт 1С .xlsx<br/>раз на день"| app

    app -->|"ціна · фото · наявність"| site[("🌐 chudo-ostriv.com.ua")]
    app <-->|"вхід · залишки · принтери"| fb[("🔥 Firebase<br/>Auth + Firestore")]
    app -->|"Wi-Fi · порт 9100"| brother["🖨 Brother<br/>цінники A4"]
    fb -->|"завдання друку"| pc["💻 ЧудоСкан Друк<br/>ПК магазину"]
    pc -->|USB| godex["🏷 Godex<br/>етикетки 40×24"]

    classDef brand fill:#FF7500,stroke:#C25500,color:#fff;
    classDef store fill:#FFF3E8,stroke:#FF7500,color:#5A2A00;
    classDef dev fill:#F4F4F4,stroke:#888,color:#222;
    class app brand;
    class site,fb store;
    class brother,godex,pc dev;
```

| Частина | Хто користується | Що робить |
|---|---|---|
| **📱 Застосунок** | Продавці й адміни | Скан, пошук, залишки, цінники, замовлення, адміністрування |
| **🔥 Firebase** | — (хмара) | Вхід за телефоном, ролі, залишки мережі, реєстр принтерів, черга друку |
| **💻 ЧудоСкан Друк** | Комп'ютер з Godex | Працює у фоні, бере етикетки з черги й друкує на Godex |

## 📲 Встановлення

<img src="docs/qr-install.png" width="140" align="right" alt="QR-код сторінки встановлення">

Відкрий **[nikifor3663636.github.io/chudoskan](https://nikifor3663636.github.io/chudoskan/)** на телефоні
або наведи камеру на QR-код — сторінка сама покаже потрібну кнопку.

| Пристрій | Як поставити |
|---|---|
| 🤖 **Android 8.0+** | APK з [останнього релізу](https://github.com/Nikifor3663636/chudoskan/releases/latest); нова версія ставиться поверх старої |
| 🍏 **iPhone** | [Відкрити в Safari](https://nikifor3663636.github.io/chudoskan/app/) → «Поділитися» → «На екран „Додому“» |
| 💻 **ПК з Godex** | «ЧудоСкан Друк» — [3 кроки, без прав адміністратора](printer/README.md) |

```mermaid
flowchart LR
    a["1 · Реєстрація<br/>ім'я, телефон, пароль, магазин"] --> b["2 · Адмін<br/>підтверджує"] --> c["3 · Вхід<br/>телефон + пароль, без SMS"]
    classDef step fill:#FFF3E8,stroke:#FF7500,color:#5A2A00;
    class a,b,c step;
```

## ✨ Можливості

| Головний екран | Картка товару | Наявність у магазинах | Залишки |
|:-:|:-:|:-:|:-:|
| <img src="docs/screenshots/01-home.png" width="185" alt="Головний екран"> | <img src="docs/screenshots/02-product.png" width="185" alt="Картка товару"> | <img src="docs/screenshots/03-shops.png" width="185" alt="Наявність у магазинах"> | <img src="docs/screenshots/04-stock.png" width="185" alt="Залишки"> |

- **Пошук** — за штрихкодом, артикулом, кодом 1С (`BOC022446`) або назвою.
- **Картка товару** — як на сайті: фото, ціна, стара ціна, опис; кнопка **«Цінник»**.
- **Залишки** — свій магазин і магазини міста з кількостями з 1С; дату оновлення видно завжди.
- **Пакет цінників** — вибрати товари або взяти змінені з минулого завантаження → один документ.
- **Замовлення** — самовивіз: «Нові → Збираю → Готові», збирання сканером.
- **Нижня панель** — `Пошук · Замовлення · 📷 · Залишки · Профіль`.

## 🖨 Друк

```mermaid
flowchart LR
    tag["🏷 Цінник у картці<br/>або пакет із «Залишків»"] --> render["Один малюнок:<br/>що бачиш — те й друкується"]
    render -->|"A4 · PCL 300 dpi"| wifi["Wi-Fi магазину"] --> brother["🖨 Brother"]
    render -->|"40×24 · EZPL"| queue[("Черга printJobs")] --> pc["💻 ЧудоСкан Друк"] --> godex["🏷 Godex"]

    classDef brand fill:#FF7500,stroke:#C25500,color:#fff;
    classDef soft fill:#FFF3E8,stroke:#FF7500,color:#5A2A00;
    class render brand;
    class wifi,queue,pc soft;
```

| Шаблон | Принтер | На аркуш |
|---|---|---|
| Етикетка 40×24 | Godex | рулон |
| Цінник 68×38 | Brother | 20 альбомно · 21 книжно |
| Крупногабарит 140×100 | Brother | 4 альбомно |
| Крупногабарит 148×105 | Brother | 2 книжно |

- Штрихкод, код і артикул — **з 1С**, назва й ціна — **з сайту**. Немає ціни на сайті — цінник не друкується.
- Обраний принтер не відповідає (USB вийняли, ПК вимкнено) — застосунок **питає**, а не друкує мовчки на іншому.
- Телефон сам знаходить принтери в мережі магазину; «Принтери» бачать лише адміни.

## 📦 Залишки мережі

Адмін мережі раз на день завантажує один звіт 1С — і в усіх 46 магазинах видно точні залишки.

```mermaid
sequenceDiagram
    autonumber
    actor A as 🧑‍💼 Адмін мережі
    participant C as 1С
    participant App as 📱 ЧудоСкан
    participant F as 🔥 Firestore
    actor P as 👤 Продавець
    A->>C: «Ведомость по партиям товаров на складах», «по» = сьогодні
    C-->>A: .xlsx — склади в колонках
    A->>App: Профіль → Залишки мережі → файл
    App->>App: розбір і порівняння з минулим файлом
    App->>F: тільки змінені товари
    P->>App: скан штрихкоду
    App->>F: один документ товару
    F-->>App: свій магазин + магазини міста
```

<details>
<summary><b>Налаштування звіту в 1С</b> (один раз, далі — «Восстановить значения»)</summary>

| Налаштування | Значення |
|---|---|
| Группировки строк | **Номенклатура** |
| Группировки колонок | **Склад** |
| Показатели | **Штрих код**, **Конечный остаток → Количество** |
| Дополнительные поля | **Код**, **Артикул** |
| Отбор | склади мережі, без «&lt;Пустое значение&gt;» |
| Период «по» | **сьогодні** |
| Збереження | **Файл → Сохранить как → .xlsx** |

</details>

Покрокова інструкція, щоденний порядок і автоматичне збереження: [docs/zvit-1c.md](docs/zvit-1c.md).

Захист від помилок: магазин без жодного товару у файлі **не обнуляється**; товарів менше половини від
минулого разу — застосунок **перепитає**; новий склад — питання **«Який це магазин?»** з підказкою.

## 👥 Ролі

```mermaid
flowchart LR
    e["👤 Співробітник<br/>скан · залишки · цінники · замовлення"]
    a["🏪 Адмін магазину<br/>+ свої співробітники · принтери · ПК з Godex"]
    s["🌐 Адмін мережі<br/>+ усі магазини · залишки мережі · журнал дій"]
    e --> a --> s
    classDef r fill:#FFF3E8,stroke:#FF7500,color:#5A2A00;
    classDef top fill:#FF7500,stroke:#C25500,color:#fff;
    class e,a r;
    class s top;
```

| Роль | Як призначити |
|---|---|
| Співробітник | Реєструється сам, підтверджує адмін магазину або мережі |
| Адмін магазину (`admin`) | Адмін мережі в застосунку |
| Адмін мережі (`superAdmin`) · адмін залишків (`stockAdmin`) | Лише в консолі Firebase |

## 🛠 Для розробника

```mermaid
flowchart TB
    subgraph clients["Клієнти"]
        app["app/<br/>Android · Kotlin · Compose"]
        shared["shared/<br/>Kotlin Multiplatform<br/>iPhone + веб (Wasm)"]
        printer["printer/<br/>C# .NET 8 · WinForms"]
    end
    core[["core/<br/>Rust: штрихкоди, GraphQL сайту,<br/>наявність, захист від блокувань"]]
    proxy["proxy/<br/>Cloudflare Worker<br/>CORS для веб-версії"]
    fb[("Firebase<br/>firestore.rules + rules-tests/")]

    app -- UniFFI --> core
    shared -- "C ABI · JSON" --> core
    shared -. веб .-> proxy
    app -.-> fb
    shared -.-> fb
    printer -.-> fb

    classDef brand fill:#FF7500,stroke:#C25500,color:#fff;
    classDef soft fill:#FFF3E8,stroke:#FF7500,color:#5A2A00;
    class core brand;
    class proxy,fb soft;
```

| Папка | Що всередині |
|---|---|
| [`core/`](core) | 🦀 Rust-ядро без мережі: запити й розбір відповідей сайту, EAN/UPC, міста |
| [`app/`](app) | 🤖 Android: `data/` · `print/` · `ui/{home,scan,results,product,stock,orders,admin,auth,profile}` |
| [`shared/`](shared) | 🍏🌐 Kotlin Multiplatform: екрани iPhone і веб-версія, Firebase через REST |
| [`printer/`](printer) | 💻 «ЧудоСкан Друк»: Core, тести, WinForms, установник Inno Setup |
| [`rules-tests/`](rules-tests) | 🔒 Тести `firestore.rules` на емуляторі |
| [`proxy/`](proxy) | ☁️ Посередник Cloudflare Workers (сайт мережі не віддає CORS) |
| [`docs/`](docs) | Сторінка встановлення, відео, знімки екрана |

<details>
<summary><b>🤖 Android</b></summary>

JDK 21, Android SDK + NDK, Rust, `cargo-ndk`. Шлях до проєкту — лише латиницею.

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleRelease
```

Ядро не мінялося — `-PskipRust=true`. Знімки дизайну — `./gradlew :app:recordPaparazziDebug`.
Тег `vX.Y` — CI збирає APK і публікує реліз.

</details>

<details>
<summary><b>💻 ЧудоСкан Друк</b></summary>

```bash
dotnet test printer/ChudoScan.Print.Tests
dotnet publish printer/ChudoScan.Print -c Release -r win-x64 --self-contained -p:PublishSingleFile=true
```

Тег `printer-vX.Y.Z` — установники x64 і x86 у релізі. Деталі — [printer/README.md](printer/README.md).

</details>

<details>
<summary><b>🔒 Правила Firestore</b></summary>

```bash
cd rules-tests && npm ci && npm test      # емулятор запускається сам (потрібна Java)
firebase deploy --only firestore:rules
```

</details>

<details>
<summary><b>🦀 Ядро · 🌐 веб · 🍏 iPhone</b></summary>

```bash
cd core && cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test
./gradlew :shared:wasmJsBrowserDistribution       # веб-версія → GitHub Pages
bash scripts/build-ios-core.sh                    # ядро для iPhone
```

</details>

<details>
<summary><b>🔥 Консоль Firebase</b></summary>

1. Android-застосунок `com.chudoostriv.app`; `google-services.json` → `app/` (у git не потрапляє;
   для CI — секрет `GOOGLE_SERVICES_JSON`).
2. **Authentication → Sign-in method:** **Email/Password** (вхід за телефоном) і **Anonymous**
   (вхід «ЧудоСкан Друк»).
3. **Адмін мережі:** `users/<uid>` → `superAdmin = true`, `approved = true`. Адмін залишків — `stockAdmin = true`.

</details>

| CI | Що перевіряє |
|---|---|
| [`android.yml`](.github/workflows/android.yml) | Rust fmt · clippy · test → lint → unit-тести → APK |
| [`rules.yml`](.github/workflows/rules.yml) | Правила Firestore на емуляторі |
| [`printer.yml`](.github/workflows/printer.yml) | .NET-тести → установники x64/x86 → перевірка на приватні ключі |
| [`ios.yml`](.github/workflows/ios.yml) · [`pages.yml`](.github/workflows/pages.yml) | Збірка iPhone · публікація веб-версії |

Як усе влаштовано всередині — **[ARCHITECTURE.md](ARCHITECTURE.md)**.

## 🔒 Безпека

- Доступ вирішує **сервер**: [`firestore.rules`](firestore.rules) — ролі, свій магазин, поля за білим списком;
  кожне правило перевірене [тестами на емуляторі](rules-tests/rules.test.js).
- ПК з Godex входить **анонімно** й отримує права лише свого магазину після підключення кодом;
  «Відключити» одразу закриває доступ.
- Жодних ключів сервісних акаунтів, паролів і токенів у репозиторії, програмах і журналах.
- Дії адмінів пишуться в журнал `adminLog`.
- Знайшли вразливість? Повідомте приватно — **[SECURITY.md](SECURITY.md)**.

## 🗺 Дорожня карта

- [x] Скан, ціна й наявність із сайту
- [x] Залишки всієї мережі з 1С, «У магазинах міста»
- [x] Цінники з даними 1С, A4 книжно й альбомно, пакетний друк
- [x] Godex через «ЧудоСкан Друк» без ключів і без прав адміністратора
- [x] Ролі й «Адміністрування мережі»
- [ ] Залишки без інтернету: локальна база на телефоні
- [ ] Замовлення з сайту замість демо-даних, сповіщення
- [ ] iPhone-застосунок у TestFlight

---

<p align="center">
  <sub>Дані про товари — з сайту <a href="https://chudo-ostriv.com.ua">chudo-ostriv.com.ua</a>. «Чудо Острів» — товарний знак мережі.</sub>
</p>
