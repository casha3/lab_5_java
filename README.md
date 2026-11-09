# Лабораторная работа 5 — система обработки платёжных транзакций

Мини-платёжная система (учебный расчётный контур) на **Vert.x Web + jOOQ + PostgreSQL**.
XML-сообщения по XSD (через **JAXB**), атомарные денежные операции, конкурентность
(обеспечивает PostgreSQL при READ COMMITTED) и компенсация (reversal).

> Примечание по инструментарию: среда сборки использует **OpenJDK 21** (установлен в
> `/opt/homebrew/opt/openjdk@21`), Мaven-сборка нацелена на `--release 21`, что полностью
> совместимо с заявленным стеком. Версии библиотек подобраны совместимыми с Java 21/25.
> Требование ТЗ «Язык: Java 25» задокументировано как отклонение в пользу локального
> инструментария (JDK 21), поведение и API не зависят от этого.

---

## Технологический стек

| Компонент | Библиотека / версия |
|---|---|
| Язык | Java (release 21, OpenJDK) |
| HTTP-сервер и роутинг | [Vert.x Web](https://vertx.io/docs/vertx-web/java/) 4.5.x |
| Доступ к БД | [jOOQ](https://www.jooq.org/) 3.19.x (codegen из живой схемы PostgreSQL) |
| БД | PostgreSQL 16+/17 (локально — PostgreSQL 17) |
| XML/JSON | Jakarta XML Binding 4.x (xjc) + Jackson 2.19 |
| Пул соединений | HikariCP 5.1 |
| Логи | slf4j-simple |
| Тесты | JUnit 5, AssertJ, JaCoCo, surefire |
| Сборка | Maven 3.9 (uber-JAR, все зависимости внутри) |

Запреты ТЗ соблюдены: нет Spring, нет ручного конкатенации XML (только JAXB),
все суммы — `BigDecimal` (никаких `float`/`double`), jOOQ/JDBC не вызываются на event loop,
списание/зачисление — условный `UPDATE` (без отдельного `SELECT`+списание),
интеграционные тесты идут против настоящего PostgreSQL.

---

## Архитектура

```
HTTP (Vert.x Router, префикс /api)            <-- event loop
        │  обработчики — лямбды/ссылки на методы
        ▼
request → executeBlocking (общий worker-пул Vert.x) → PaymentService
                                                          │  jOOQ DSLContext.transaction(...)
                                                          ▼
                             PostgreSQL  ←  jOOQ codegen (payment.jooq.generated)
```

- **`main.Main`** — точка входа, собирает конфиг, БД, сервис, HTTP-роутер, стартует сервер.
- **`payment.config.AppConfig`** — конфигурация из env; валидация ставки комиссии (0..100, ≤2 знака),
  иначе приложение не стартует. Константа `M = 9999999999999999.99`.
- **`payment.model`** — доменные записи (`Bank`, `PaymentTransaction`, `BankNotification`)
  и перечисления `PaymentStatus`, `NotificationType`.
- **`payment.money.Money`** — денежный контракт: только `BigDecimal`, сравнение через `compareTo`,
  расчёт комиссии (HALF_UP), проверка представимости сумм двумя знаками без округления.
- **`payment.jooq.Database`** — HikariCP + единый `DSLContext`; метод `transaction(...)` = одна
  транзакция PostgreSQL (откат при любом исключении).
- **`payment.repo`** — `BankRepository`, `PaymentRepository`, `NotificationRepository`
  (типобезопасные генерируемые таблицы/поля).
- **`payment.service.PaymentService`** — вся денежная логика в `dsl.transaction(...)`.
- **`payment.jaxb`** — `JaxbSupport` (unmarshal запроса / marshal уведомления, валидация по XSD)
  и `XmlSecurity` (защищённый разбор без DOCTYPE/XXE/внешних схем).
- **`payment.http.HttpApi`** — REST-эндпоинты, единый JSON-формат ошибок, requestId, thread-logging.

### Конкурентность и потоки (требование Vert.x)

Обработчики маршрутов — лямбды/ссылки на методы на **event loop**. Все DB-операции
выполняются через `vertx.executeBlocking(...)` (общий **worker-пул** Vert.x), а не через новый
executor на каждый запрос. В логах видны имена потоков (event loop vs worker). Тест
`ConcurrencyBlockingTest` доказывает: при искусственной 2-секундной задержке DB-пути
(флаг `DB_DELAY_MILLIS`, только тест) независимый HTTP-запрос без БД (`/api/health`) не блокируется.

## Деньги и атомарность

- Суммы: `NUMERIC(18,2)`, диапазон `0 ≤ v ≤ M`, комиссия `0 ≤ c ≤ M`, `0 < amount ≤ M`.
- Комиссия: `amount × rate / 100`, округление только результата HALF_UP до 2 знаков.
- Reversal возвращает **сохранённую** комиссию (не зависит от текущей ставки).
- Атомарность — одна транзакция `dsl.transaction(...)`:
  - **Payment**: три различных существующих банка → списание эмитента → зачисление получателю
    (контроль верхнего предела) → списание комиссии с эквайера → INSERT платежа (COMMITTED)
    → INSERT двух уведомлений (JAXB-payload). Любая ошибка → полный откат.
  - **Reversal**: атомарный захват `UPDATE ... WHERE id=? AND status='COMMITTED'`
    (защита от повторной/конкурентной компенсации) → обратные движения → статус REVERSED.
    Уведомления не меняются. Ошибка → откат, платёж остаётся COMMITTED.
- Списание — условный `UPDATE` с `balance >= amount`; зачисление — с `balance <= M - amount`;
  проверка затронутых строк, без отдельного `SELECT` остатка.

## XSD / JAXB

Два XSD в `src/main/resources/xsd/`, классы генерируются xjc в Maven build (`jaxb-maven-plugin`):

| Схема | Namespace | Корневой элемент |
|---|---|---|
| `payment-request.xsd` | `urn:payment:request:v1` | `<PaymentRequest>` |
| `bank-notification.xsd` | `urn:payment:notification:v1` | `<BankNotification>` |

- Сгенерированные классы — в пакете `payment.jaxb.generated` (не редактируются руками).
- Входящий платёж разбирается **безопасно**: любой `<!DOCTYPE>`/`<!ENTITY>` отклоняется
  (`400 XML_SECURITY_ERROR`), внешние DTD/схемы клиента запрещены, валидация только по
  встроенным XSD; невалидный XML отклоняется до бизнес-операции.
- Обязательная информация:
  - `BankNotification`: `notificationId`, `transactionId`, `type`, `bankBic`, `amount`, `createdAt`.

## REST API

Базовый префикс `/api`. Единый формат ошибки:
`{"error":"CODE","message":"...","requestId":"..."}`.

| Метод | Путь | Описание |
|---|---|---|
| `POST` | `/api/banks` | создать банк (JSON) → `201` / `409 BANK_EXISTS` + ошибки валидации |
| `GET` | `/api/banks` | список банков → `200` |
| `GET` | `/api/banks/{bic}` | банк → `200` / `404 BANK_NOT_FOUND` |
| `POST` | `/api/transactions` | платёж (`application/xml`, XSD) → `201` / `409` / `400` |
| `GET` | `/api/transactions/{id}` | транзакция → `200` / `404 TRANSACTION_NOT_FOUND` |
| `GET` | `/api/transactions/{id}/notifications/{type}` | XML-payload уведомления → `200` / `404` |
| `POST` | `/api/transactions/{id}/reversal` | компенсация (без тела) → `200` / `404` / `409` |
| `GET` | `/api/health` | healthcheck (без БД, для теста конкурентности) |

Коды ошибок: `400 MALFORMED_XML / SCHEMA_VALIDATION_ERROR / XML_SECURITY_ERROR / INVALID_REQUEST`,
`404 BANK_NOT_FOUND / TRANSACTION_NOT_FOUND / NOTIFICATION_NOT_FOUND`,
`409 BANK_EXISTS / INSUFFICIENT_FUNDS / REVERSAL_INSUFFICIENT_FUNDS / REVERSAL_ALREADY_APPLIED /
BALANCE_LIMIT_EXCEEDED`, `415 UNSUPPORTED_MEDIA_TYPE`, `500 INTERNAL_ERROR`.
Клиенту не отдаются stack trace/SQL/пути/секреты; причина — в server-log с тем же `requestId`.

Пример платёжного запроса:

```xml
<PaymentRequest xmlns="urn:payment:request:v1">
  <clientReference>order-123</clientReference>
  <issuerBic>BICIS001</issuerBic>
  <receiverBic>BICRV001</receiverBic>
  <acquirerBic>BICAQ001</acquirerBic>
  <amount>1200.00</amount>
</PaymentRequest>
```

---

## Запуск

Требуется запущенный локальный PostgreSQL (Homebrew `postgresql@17`).

```bash
# 0. Подготовить БД и схему (создаёт payments_db и payments_db_test)
./run-ddl.sh

# 1. Собрать самодостаточный uber-JAR (все зависимости внутри, без Class-Path)
./run-compile.sh          # → payment-system.jar

# 2. Запустить сервер
./run-server.sh           # env: HTTP_PORT, DB_URL, DB_USER, DB_PASSWORD, COMMISSION_RATE

# 3. Сквозной smoke-тест REST API
./smoke-test.sh           # pass=20 fail=0 (в норме)
```

Переменные окружения сервера:

| Переменная | По умолчанию |
|---|---|
| `HTTP_PORT` | `8080` |
| `DB_URL` | `jdbc:postgresql://localhost:5432/payments_db` |
| `DB_USER` | текущий пользователь |
| `DB_PASSWORD` | пусто |
| `COMMISSION_RATE` | `1.50` (процент, 0..100, ≤2 знака) |

## Тесты

```bash
./run-tests.sh     # mvn clean verify + surefire-report; HTML в target/site/
```

- 11 интеграционных тестов против настоящего PostgreSQL (`payments_db_test`, `TRUNCATE` на старте):
  создание банков, атомарный платёж/реверс, недостаток средств, превышение лимита,
  повторный/конкурентный реверс, рискованная компенсация, возврат сохранённой комиссии.
- Юнит-тесты: денежный контракт (BigDecimal/HALF_UP, compareTo), безопасность XML
  (DOCTYPE/внешняя сущность/невалидная схема), маршалинг уведомлений, JSON (деньги без double,
  enum→строка).
- Тест конкурентности: `DB_DELAY_MILLIS` задержка DB-пути не блокирует `/api/health`.

Отчёты: `target/site/jacoco/index.html`, `target/site/surefire-report.html`.

## Структура проекта

```
db/schema.sql          DDL (Bank, payment_transaction, bank_notification) + CHECK/UNIQUE/FK/индексы
src/main/resources/xsd/  payment-request.xsd, bank-notification.xsd
src/main/java/main/Main.java
src/main/java/payment/{config,money,error,model,jaxb,jooq,repo,service,http}/
src/test/java/...         юнит + интеграционные тесты (JUnit 5)
pom.xml                   Maven (jaxb xjc + jooq codegen, JaCoCo, surefire)
run-ddl.sh / run-compile.sh / run-server.sh / run-tests.sh / smoke-test.sh
payment-system.jar        собранный uber-JAR
```

## Кредиты

Учебная работа; все сторонние библиотеки и их лицензии указаны в `pom.xml`.