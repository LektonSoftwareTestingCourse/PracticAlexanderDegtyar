# А6. State-transition

## Чего не хватало в проекте

Диаграмм состояний нет.

## Что сделано

Построены три диаграммы:
1. `Card.status` — переходы и реакция системы на каждое состояние;
2. жизненный цикл транзакции;

## А6.1 Card.status

```mermaid
stateDiagram-v2
    direction LR
    [*] --> ACTIVE: POST /api/cards<br/>(всегда ACTIVE, CardServiceImpl, строка 50)
    [*] --> ACTIVE: POST /api/cards/generate (95%)
    [*] --> INACTIVE: generate (3%)
    [*] --> BLOCKED: generate (2%)

    ACTIVE --> INACTIVE: PATCH status / bulk-update
    ACTIVE --> BLOCKED: PATCH / bulk-update
    ACTIVE --> EXPIRED: PATCH / bulk-update<br/>(только вручную)
    INACTIVE --> ACTIVE: PATCH / bulk-update
    BLOCKED --> ACTIVE: PATCH / bulk-update
    EXPIRED --> ACTIVE: PATCH / bulk-update<br/>guard'а нет
    INACTIVE --> BLOCKED: PATCH
    BLOCKED --> INACTIVE: PATCH
    INACTIVE --> EXPIRED: PATCH
    BLOCKED --> EXPIRED: PATCH

    ACTIVE --> DELETED: DELETE /api/cards/{pan}
    INACTIVE --> DELETED: DELETE
    BLOCKED --> DELETED: DELETE
    EXPIRED --> DELETED: DELETE
    DELETED --> [*]

    note right of ACTIVE
        Authorization: проверки идут дальше
        (срок -> баланс -> лимиты -> reserve)
        CMS reserve: разрешён
    end note
    note right of DELETED
        скрыт @SQLRestriction:
        GET/PATCH/DELETE -> 404,
        авторизация -> 14 CARD_NOT_FOUND
    end note
```

### Реакция системы на состояние

| Состояние                      | `GET /api/cards/{pan}` | Авторизация                          | `POST /{pan}/reserve`         | Код                                                                                                                                                                                                                                                                            |
|--------------------------------|------------------------|--------------------------------------|-------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| ACTIVE (срок не истёк)         | 200                    | дальше по проверкам, `00` при успехе | 200                           | [`AuthServiceImpl.java:77`](../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java)                                                                                                                                          |
| ACTIVE, `expiryDate` в прошлом | 200                    | `54 CARD_EXPIRED`                    | 200 (CMS срок не проверяет)   | [`AuthServiceImpl.java:108`](../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java), [`Card.java:101`](../../../services/card-management/src/main/java/com/processing/cardmanagement/models/Card.java)                      |
| INACTIVE                       | 200                    | `05 CARD_INACTIVE`                   | 409 (`IllegalStateException`) | [`Card.java:261`](../../../services/card-management/src/main/java/com/processing/cardmanagement/models/Card.java), [`GlobalExceptionHandler.java:98`](../../../services/card-management/src/main/java/com/processing/cardmanagement/configuration/GlobalExceptionHandler.java) |
| BLOCKED                        | 200                    | `05 CARD_BLOCKED`                    | 409                           | там же                                                                                                                                                                                                                                                                         |
| EXPIRED                        | 200                    | `54 CARD_EXPIRED`                    | 409                           | там же                                                                                                                                                                                                                                                                         |
| DELETED                        | 404                    | `14 CARD_NOT_FOUND`                  | 404                           | [`CardEntity.java:22`](../../../services/card-management/src/main/java/com/processing/cardmanagement/models/CardEntity.java)                                                                                                                                                   |

---

## А6.2 Жизненный цикл транзакции

```mermaid
stateDiagram-v2
    [*] --> Received: POST /api/transactions
    Received --> Rejected400: Gateway validation<br/>(mti≠0100, amount≤0, PAN≠16)
    Rejected400 --> [*]: HTTP 400, в Logger не пишется

    Received --> Routing: Switch
    Routing --> Declined: BIN не в switch.bin-routing (14)<br/>Authorization недоступен (05)
    Routing --> Authorizing: issuerId найден

    Authorizing --> Declined: статус/срок/баланс/лимит/<br/>CMS недоступна (05/14/51/54/61/96)
    Authorizing --> Reserved: reserve OK -> APPROVED 00

    Reserved --> Publishing: convertAndSend
    Declined --> PublishingDeclined: convertAndSend

    Publishing --> LoggedApproved: broker принял
    Publishing --> RollingBack: AmqpException

    PublishingDeclined --> LoggedDeclined: broker принял
    PublishingDeclined --> LostDeclined: AmqpException<br/>(откат не нужен)

    RollingBack --> RolledBack: rollback 00
    RollingBack --> RollbackFailed: rollback ≠ 00 / null

    LoggedApproved --> [*]: клиенту 00, Logger APPROVED,<br/>CMS reservation RESERVED
    LoggedDeclined --> [*]: клиенту 05/14/51/54/61/96,<br/>Logger DECLINED
    LostDeclined --> [*]: клиенту код отказа,<br/>в Logger записи нет
    RolledBack --> [*]: клиенту 96, в Logger записи нет,<br/>CMS reservation ROLLED_BACK
    RollbackFailed --> [*]: клиенту 96, в Logger записи нет,<br/>CMS reservation остаётся RESERVED
```