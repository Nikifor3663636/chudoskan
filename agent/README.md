# ЧудоСкан — агент друку на ПК магазину

Запасний шлях друку, коли принтер підключений до комп'ютера, а не до мережі (наприклад, Godex по USB).
Телефон кладе задачу в Firestore `printJobs`, агент друкує її на принтері цього ПК.

## Що потрібно

- Windows, Node.js 18+.
- Принтер встановлений у Windows (драйвер Godex / Brother), назва в «Принтери та сканери» містить «Godex» або «Brother».
- JSON-ключ сервісного акаунта Firebase (Консоль → Налаштування проєкту → Сервісні акаунти → «Створити ключ»).
  Ключ зберігай поза репозиторієм; у git він не потрапляє.

## Запуск

```bat
cd agent
npm install
set GOOGLE_APPLICATION_CREDENTIALS=C:\chudoscan\service-account.json
set SHOP_ID=<id магазину з users/{uid}.shopId>
npm start
```

`AGENT_ID` за замовчуванням — ім'я комп'ютера. Щоб агент запускався сам — Планувальник завдань Windows:
«під час входу в систему», дія `node C:\…\agent\index.js`, змінні середовища задати на рівні системи.

## Підмережі принтерів (один раз)

Телефони шукають мережеві принтери в `shops/{shopId}.printerSubnets` і самі доповнюють список.
Початкові підмережі записує адміністратор:

```bat
node index.js --subnets <підмережа1>/24,<підмережа2>/24
```

## Як це працює

1. Кожні 60 с: `agents/{AGENT_ID}` = `{ shopId, pcName, online, lastSeen, printers: [{ name, isDefault, kind }] }`.
   `kind`: Godex → `label_40x24`, Brother → `a4`. Віртуальні принтери (PDF, XPS, Fax) не показуються.
2. Слухає `printJobs`, де `agentId` = свій і `status` = `queued`. Забирає задачу транзакцією (`printing`),
   пише байти (EZPL для Godex, PCL для Brother) у спулер Windows як RAW (`raw-print.ps1`) — драйвер їх не змінює.
3. Ставить `done` або `error` з текстом — телефон показує його співробітнику.

Телефон вважає агента онлайн, якщо `lastSeen` не старший за 3 хв.

## Перевірка

```bat
npm run check
```
