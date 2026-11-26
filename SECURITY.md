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
