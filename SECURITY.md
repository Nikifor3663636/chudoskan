# Безпека

## Як повідомити про вразливість

Не створюйте публічний issue. Скористайтеся приватним звітом:
**Security → Report a vulnerability** у цьому репозиторії.

Відповідь — протягом 3 робочих днів, виправлення критичних проблем — у найближчому релізі.

## Що захищено

- **Дані магазинів** (залишки, принтери, ПК) доступні лише підтвердженим співробітникам
  свого магазину — це перевіряє сервер, а не застосунок: RLS-політики Supabase
  (`supabase/migrations`, тести `supabase/tests`) або, до вимкнення, правила Firestore (`firestore.rules`).
- **Вхід** — лише Firebase Auth. Supabase приймає Firebase ID token тільки нашого проєкту
  (restrictive-політика на `iss` і `aud`) і з claim `role = authenticated`, який ставить
  Edge Function `ensure-role`; інших claims немає — усі права в таблицях.
- **Анкета співробітника**: підтвердження і магазин змінює тільки адміністратор; `super_admin`
  і `stock_admin` — лише в Supabase Dashboard. Колонки обмежені GRANT і тригером.
- **Залишки** пишуться лише через RPC `stock_upload_*` (stockAdmin / superAdmin або серверний ключ вигрузки 1С).
- **Ключі** (`google-services.json`, `GoogleService-Info.plist`, ключі Apple, ключ сервісного акаунта
  Firebase, `service_role` / secret key Supabase, пароль бази) не зберігаються в репозиторії —
  лише в секретах GitHub Actions, `supabase secrets` або локальному `.env.local` (у `.gitignore`).
  У клієнтах — тільки публічні URL і publishable key Supabase.
- **Пристрій**: резервне копіювання даних застосунку вимкнене (`allowBackup=false`),
  щоб паролі Wi-Fi принтерів не потрапляли в хмарні бекапи.
- **Залежності** оновлює Dependabot; CI перевіряє Rust (`clippy -D warnings`) і Android lint без попереджень.

## Підтвердження Android-пристрою

Пілотний Android APK приймає `-PdeviceGatewayUrl=https://...`. У штатних збірках
параметр порожній. Для пілота потрібен HTTPS-шлюз з такими POST-операціями під
`/v1/android/`. Запити передають Firebase ID token у `Authorization: Bearer`.
Двійкові поля мають формат base64url без доповнення.

| Операція | Запит | Відповідь |
| --- | --- | --- |
| `begin` | За наявності `public_key` у SEC1 | `status=enroll` та реєстраційний `nonce`, `status=prove` та `nonce` і `expires_at` (Unix-секунди), або поточний стан заявки |
| `enroll` | Реєстраційний `nonce` та `certificates` (ланцюжок DER) | `pending`, `another_pending` або `blocked` |
| `prove` | `nonce` і DER-підпис у `signature` | `approved` та UUID у `session` після створення серверної сесії |

Шлюз перевіряє підпис, issuer, audience і строк дії Firebase токена. Для
реєстрації він видає одноразовий nonce, прив’язаний до UID. Перед створенням
заявки він перевіряє ланцюжок апаратної атестації Android, nonce, статус
відкликання сертифікатів, пакет і сертифікат підпису застосунку. IP береться
з довіреного транспортного з’єднання або від налаштованого довіреного проксі.

Render Blueprint у `render.yaml` збирає Rust-шлюз із `security/Dockerfile`.
Перед розгортанням застосуйте міграції Supabase. Створіть для шлюзу окремий
Postgres LOGIN лише з членством у `chudoscan_device_gateway`. Встановіть
`DEVICE_DATABASE_URL` з TLS для цього логіна та `ANDROID_SIGNING_SHA256`
(64 hex-символи SHA-256 сертифіката підпису пілотного APK) в секретах Render.
Не передавайте пароль власника бази або ключ `service_role` шлюзу.

У тестового співробітника має бути мережевий адміністратор з окремим UID.
Після перевірки входу додайте тільки UID цього співробітника в приватну
`app.device_required`. Для нього Supabase перевірятиме `X-Device-Session`
разом із Firebase ID token. Пілотний клієнт опитує зміни, які раніше
отримував через Realtime. Для відкату видаліть цього співробітника з
`app.device_required`. Якщо Firestore ще містить копію закритих даних, до
робочого запуску окремо закрийте доступ до неї старими клієнтами.
