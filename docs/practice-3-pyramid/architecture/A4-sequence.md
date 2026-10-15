# А4. Sequence — путь транзакции

## Чего не хватало в проекте

Sequence в [`docs/architecture.md`](../../architecture.md) покрывает только happy
path и в нескольких местах расходится с кодом:

| # | В документации                                         | В коде                                                                                                                                                                                                                                         | Ссылка                                                                                                                                                                                                                                                                                                                                                                          |
|---|--------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1 | Authorization сначала вызывает Bin Lookup, потом CMS   | сначала `getCard` в CMS, Bin Lookup только для найденной карты, результат не используется                                                                                                                                                      | [`AuthServiceImpl.java:46,66`](../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java)                                                                                                                                                                                                                                        |
| 2 | RRN генерируется после reserve                         | RRN генерируется до reserve, он нужен CMS как ключ резерва                                                                                                                                                                                     | [`AuthServiceImpl.java:149-151`](../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java)                                                                                                                                                                                                                                      |
| 3 | «Резервирование не атомарно, нужен SELECT FOR UPDATE»  | CMS уже берёт `PESSIMISTIC_WRITE` и повторно проверяет баланс в `startReservation`                                                                                                                                                             | [`CardJpaRepository.java:20`](../../../services/card-management/src/main/java/com/processing/cardmanagement/repositories/CardJpaRepository.java), [`Card.java:101`](../../../services/card-management/src/main/java/com/processing/cardmanagement/models/Card.java)                                                                                                             |
| 4 | Нет вызова Merchant Acquirer                           | после authorize Switch синхронно запрашивает комиссию                                                                                                                                                                                          | [`RouteService.java:70`](../../../services/switch/src/main/java/com/processing/service/RouteService.java)                                                                                                                                                                                                                                                                       |
| 5 | Нет проверки лимитов в БД                              | upsert `limit_usage` в PostgreSQL до reserve                                                                                                                                                                                                   | [`AuthServiceImpl.java:125`](../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java)                                                                                                                                                                                                                                          |
| 6 | Нет declined и reversal                                | см. сценарии 2 и 3 ниже                                                                                                                                                                                                                        | —                                                                                                                                                                                                                                                                                                                                                                               |
| 7 | Reversal «mti=0400, publisher confirm не пришёл за 2s» | MTI 0400 в коде нет (Gateway пропускает только `0100`). Откат — HTTP `POST /api/internal/rollback {rrn, pan, amount}`. Ожидания confirm с таймаутом 2s нет: rollback запускается только если `convertAndSend` синхронно бросил `AmqpException` | [`TransactionRequestValidator.java:37`](../../../services/gateway/src/main/java/com/processing/gateway/validation/TransactionRequestValidator.java), [`LoggerClient.java:40-49`](../../../services/switch/src/main/java/com/processing/service/LoggerClient.java), [`RouteService.java:85-91`](../../../services/switch/src/main/java/com/processing/service/RouteService.java) |

## Что сделано

Три сценария, восстановленные по коду. В каждом видно, где синхронный
вызов, где ответ клиенту и где начинается асинхронная «вилка».

---

## Сценарий 1. Happy path (APPROVED, `00`)

```mermaid
sequenceDiagram
    actor T as Terminal Simulator
    participant GW as Gateway
    participant SW as Switch
    participant AUTH as Authorization
    participant CMS as Card Management
    participant BIN as Bin Lookup
    participant DB as PostgreSQL
    participant MA as Merchant Acquirer
    participant RMQ as RabbitMQ
    participant LOG as Logger
    participant NS as Notification Service

    T->>GW: POST /api/transactions (mti 0100)
    GW->>GW: validate request, rate limit
    GW->>SW: POST /api/internal/route
    SW->>SW: normalize, BIN → issuerId (switch.bin-routing)
    SW->>AUTH: POST /api/internal/authorize (retry до 3x)
    AUTH->>CMS: GET /api/cards/{pan}
    CMS-->>AUTH: Card {status ACTIVE, expiry, balance, limits}
    AUTH->>BIN: GET /api/bin/{bin}
    BIN-->>AUTH: BinLookupResponse {issuerId} (только лог)
    AUTH->>AUTH: check status, expiry, balance
    AUTH->>DB: upsertLimitUsage (лимиты в SQL)
    DB-->>AUTH: 1 row
    AUTH->>AUTH: generate RRN, authCode
    AUTH->>CMS: POST /api/cards/{pan}/reserve {amount, rrn}
    CMS->>CMS: SELECT FOR UPDATE, reservation, balance, outbox_event
    CMS-->>AUTH: OK
    AUTH-->>SW: AuthResponse {APPROVED, 00, rrn, authCode}
    SW->>MA: POST /api/simulator/merchant/fee
    MA-->>SW: {acquirerFee}
    SW->>RMQ: Publish Transaction (async, без ожидания confirm)
    SW-->>GW: AuthorizationResponse
    GW-->>T: HTTP 200 {APPROVED, 00}

    Note over RMQ,NS: Асинхронно, после ответа клиенту
    RMQ-->>LOG: Consume Transaction
    LOG->>LOG: Save to DB, WebSocket broadcast
    CMS->>CMS: OutboxProcessor (каждую 1s)
    CMS-->>RMQ: Publish card.CardServiceReserveEvent (outbox)
    RMQ-->>NS: Consume card event
    NS->>NS: Save notification
```

## Сценарий 2. Declined — карта BLOCKED (`05 CARD_BLOCKED`)

```mermaid
sequenceDiagram
    actor T as Terminal Simulator
    participant GW as Gateway
    participant SW as Switch
    participant AUTH as Authorization
    participant CMS as Card Management
    participant BIN as Bin Lookup
    participant MA as Merchant Acquirer
    participant RMQ as RabbitMQ
    participant LOG as Logger

    T->>GW: POST /api/transactions
    GW->>SW: POST /api/internal/route
    alt BIN не найден в switch.bin-routing
        SW->>SW: UnknownBinException
        SW->>RMQ: Publish Transaction {DECLINED, 14} (async)
        SW-->>GW: {DECLINED, 14, CARD_NOT_FOUND}
    else BIN известен
        SW->>AUTH: POST /api/internal/authorize
        AUTH->>CMS: GET /api/cards/{pan}
        CMS-->>AUTH: Card {status BLOCKED}
        AUTH->>BIN: GET /api/bin/{bin}
        BIN-->>AUTH: BinLookupResponse
        AUTH->>AUTH: status != ACTIVE → CARD_BLOCKED
        Note over AUTH: лимиты, RRN, reserve не выполняются
        AUTH-->>SW: HTTP 403 {DECLINED, 05, CARD_BLOCKED}
        SW->>MA: POST /api/simulator/merchant/fee
        MA-->>SW: {acquirerFee}
        SW->>RMQ: Publish Transaction {DECLINED, 05} (async)
        SW-->>GW: {DECLINED, 05, CARD_BLOCKED}
    end
    GW-->>T: HTTP 200 {DECLINED, ...}
    RMQ-->>LOG: Consume Transaction
    LOG->>LOG: Save DECLINED to DB
```

Наблюдение: Switch всегда отвечает `200`
([`RouteController.java:44`](../../../services/switch/src/main/java/com/processing/controller/RouteController.java)),
поэтому HTTP-статусы Authorization видны только во внутреннем контракте
Switch <-> Authorization. Клиенту отказ виден только по полям `status`/`responseCode`.

## Сценарий 3. Reversal — публикация в RabbitMQ не удалась (`96`)

```mermaid
sequenceDiagram
    actor T as Terminal Simulator
    participant GW as Gateway
    participant SW as Switch
    participant AUTH as Authorization
    participant CMS as Card Management
    participant RMQ as RabbitMQ
    participant LOG as Logger

    T->>GW: POST /api/transactions
    GW->>SW: POST /api/internal/route
    SW->>AUTH: POST /api/internal/authorize
    Note over AUTH,CMS: как в happy path: getCard, проверки, лимиты, RRN
    AUTH->>CMS: POST /api/cards/{pan}/reserve {amount, rrn}
    CMS-->>AUTH: OK (balance -= amount)
    AUTH-->>SW: AuthResponse {APPROVED, 00, rrn}
    SW->>RMQ: Publish Transaction
    RMQ-->>SW: AmqpException (брокер недоступен)
    alt rollback успешен
        SW->>AUTH: POST /api/internal/rollback {rrn, pan, amount}
        AUTH->>CMS: POST /api/cards/{pan}/rollback {rrn, amount}
        CMS->>CMS: reservation -> ROLLED_BACK, balance += amount
        CMS-->>AUTH: OK
        AUTH-->>SW: RollbackResponse {APPROVED, 00}
    else rollback не удался
        SW->>AUTH: POST /api/internal/rollback
        AUTH-->>SW: ошибка / {DECLINED, 96}
        Note over SW,CMS: деньги остаются зарезервированы, повтора нет
    end
    SW-->>GW: {DECLINED, 96}
    GW-->>T: HTTP 200 {DECLINED, 96}
    Note over LOG: запись о транзакции в Logger не появится
    Note over AUTH: limit_usage не откатывается
```

Ветки компенсации (по коду [`RouteService.java:87-91`](../../../services/switch/src/main/java/com/processing/service/RouteService.java)
и [`AuthServiceImpl.java:223`](../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java)):