## Що змінено

<!-- Коротко: що і навіщо. Один PR — одна задача. -->

## Як перевірено
- [ ] `./gradlew :app:lintDebug :app:testDebugUnitTest :app:testReleaseUnitTest`
- [ ] `cd rules-tests && npm test` (якщо змінено `firestore.rules`)
- [ ] `dotnet test printer/ChudoScan.Print.Tests` (якщо змінено `printer/`)
- [ ] `cargo test`, `cargo clippy -- -D warnings` (якщо змінено `core/`)
- [ ] Перевірено на телефоні / ПК магазину

## Ліміти Firestore
<!-- Скільки читань / записів додає зміна на день (безкоштовно: 50 000 читань, 20 000 записів, 20 000 видалень). -->

## Безпека
- [ ] Змінено `firestore.rules` — є тести, правила опубліковано (`firebase deploy --only firestore:rules`)
- [ ] Немає ключів, паролів, токенів, email і телефонів людей, реальних залишків чи цін
- [ ] Інтерфейс — українською
