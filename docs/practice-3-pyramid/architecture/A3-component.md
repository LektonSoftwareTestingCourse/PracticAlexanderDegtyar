# А3. C4 Level 3 — Component (Authorization, Card-Management)

## Что сделано

Схемы собраны по пакетам
[`services/authorization/.../authorization`](../../../services/authorization/src/main/java/com/processing/authorization/)
и [`services/card-management/.../cardmanagement`](../../../services/card-management/src/main/java/com/processing/cardmanagement/).
Для каждого компонента указан тип зависимости: HTTP-клиент, JPA-репозиторий, AMQP.

---

## А3.1 Authorization

```mermaid
graph TB
    subgraph "Вызывающие сервисы"
        SW["Switch / Router<br/>Порт: 8082"]
    end

    subgraph "Authorization Service"
        subgraph "Controllers"
            CTRL["AuthControllerImpl<br/>POST /api/internal/authorize<br/>POST /api/internal/rollback"]
            HC["HealthControllerImpl<br/>GET /health"]
        end
        subgraph "Business Logic"
            SVC["AuthServiceImpl<br/>@Transactional authorize()<br/>статус, срок, баланс, лимиты,<br/>RRN, reserve / rollback()"]
            DO["DeclineOutcome<br/>reason + ISO-код"]
            EVT["AuthorizationEventNotifierImpl<br/>события -> лог"]
            CLN["CleanupServiceImpl<br/>@Scheduled чистка limit_usage"]
        end
        subgraph "Clients"
            CMSC["CardManagementClientImpl<br/>HTTP-статус → исключение"]
            BINC["BinLookupClient<br/>ошибка -> Optional.empty()"]
        end
        subgraph "Repositories"
            LUR["LimitUsageRepository<br/>upsert лимитов, fetchRrnBlock"]
        end
    end

    subgraph "Зависимости"
        CMS["Card Management<br/>Порт: 8081"]
        BIN["Bin Lookup Service<br/>Порт: 8096"]
        DB["PostgreSQL<br/>limit_usage, rrn_seq"]
    end

    SW -->|"HTTP"| CTRL
    CTRL --> SVC
    SVC --> DO
    SVC --> EVT
    SVC --> CMSC
    SVC --> BINC
    SVC --> LUR
    CLN --> LUR
    CMSC -->|"HTTP GET card, reserve, rollback"| CMS
    BINC -->|"HTTP GET /api/bin/{bin}"| BIN
    LUR -->|"JDBC"| DB
```

| Компонент                        | Роль                                             | Код                                                                                                                                                 |
|----------------------------------|--------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------|
| `AuthControllerImpl`             | REST, маппинг `declineReason` → HTTP-статус      | [`AuthControllerImpl.java:38,76,90`](../../../services/authorization/src/main/java/com/processing/authorization/controller/AuthControllerImpl.java) |
| `AuthServiceImpl`                | вся бизнес-логика авторизации и отката           | [`AuthServiceImpl.java:42-185`](../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java)           |
| `DeclineOutcome`                 | таблица «причина → код»                          | [`DeclineOutcome.java`](../../../services/authorization/src/main/java/com/processing/authorization/constants/DeclineOutcome.java)                   |
| `CardManagementClientImpl`       | HTTP к CMS, 400/402/404/409/500/503 → исключения | [`CardManagementClientImpl.java`](../../../services/authorization/src/main/java/com/processing/authorization/client/CardManagementClientImpl.java)  |
| `BinLookupClient`                | HTTP к Bin Lookup, graceful degradation          | [`BinLookupClient.java:59`](../../../services/authorization/src/main/java/com/processing/authorization/client/BinLookupClient.java)                 |
| `LimitUsageRepository`           | атомарный SQL-upsert лимитов                     | [`LimitUsageRepository.java:89`](../../../services/authorization/src/main/java/com/processing/authorization/repositories/LimitUsageRepository.java) |
| `AuthorizationEventNotifierImpl` | события → логи                                   | [`events/`](../../../services/authorization/src/main/java/com/processing/authorization/events/)                                                     |

---

## А3.2 Card-Management

```mermaid
graph TB
    subgraph "Вызывающие сервисы"
        IN["Gateway / Authorization /<br/>Terminal Simulator"]
    end

    subgraph "Card Management Service"
        subgraph "Controllers"
            CC["CardController<br/>/api/cards: CRUD, reserve,<br/>rollback, bulk-update"]
            GC["CardGeneratorController<br/>POST /api/cards/generate"]
            BC["BinIssuerController<br/>/api/bins"]
            GEH["GlobalExceptionHandler<br/>исключение -> HTTP-статус"]
        end
        subgraph "Business Logic"
            CS["CardServiceImpl<br/>create, patch, delete,<br/>reserve, rollback"]
            GS["CardGeneratorService<br/>статусы 95/3/2 %"]
            BS["BinIssuerServiceImpl"]
            DOM["Card, Reservation<br/>доменные инварианты"]
            PG["PanGenerator + LuhnValidator"]
            TR["TransactionRunnerImpl<br/>граница БД-транзакции"]
        end
        subgraph "Outbox"
            NOTIF["CardEventNotifierImpl<br/>событие → outbox_event"]
            OEP["OutboxEventProcessorImpl<br/>публикация, PROCESSED / FAILED"]
            OP["OutboxProcessor<br/>@Scheduled каждую 1s"]
        end
        subgraph "Repositories"
            REPO["Card / Reservation adapters<br/>SELECT … FOR UPDATE,<br/>скрытие DELETED"]
            OREPO["OutboxJpaAdapter"]
        end
    end

    subgraph "Infrastructure"
        DB["PostgreSQL<br/>cards, reservations, outbox_event"]
        RMQ["RabbitMQ<br/>smp.card-events"]
    end

    IN -->|"HTTP"| CC
    IN -->|"HTTP"| GC
    CC --> CS
    GC --> GS
    GS --> CS
    BC --> BS
    CS --> DOM
    CS --> PG
    CS --> TR
    CS --> BS
    CS --> REPO
    CS -->|"событие в той же транзакции"| NOTIF
    NOTIF --> OEP
    OEP --> OREPO
    OP --> OREPO
    OP --> OEP
    OEP -.->|"AMQP publish card.*"| RMQ
    REPO -->|"JDBC"| DB
    OREPO -->|"JDBC"| DB
```

| Компонент                          | Роль                                                                               | Двойник в unit-тесте                                        | Код                                                                                                                                                                                                                                                                            |
|------------------------------------|------------------------------------------------------------------------------------|-------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `CardController`                   | REST + Bean Validation (`@Pan`, `@Valid`)                                          | `@WebMvcTest`                                               | [`CardController.java:49-256`](../../../services/card-management/src/main/java/com/processing/cardmanagement/controllers/CardController.java)                                                                                                                                  |
| `CardServiceImpl`                  | сценарии над картой                                                                | объект тестирования; репозитории — mock                     | [`CardServiceImpl.java`](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java)                                                                                                                                          |
| `Card`, `Reservation`              | доменные инварианты: статус ACTIVE для резерва, баланс, лимиты, повторный rollback | **чистый unit**, без моков                                  | [`Card.java:101,199,261`](../../../services/card-management/src/main/java/com/processing/cardmanagement/models/Card.java), [`Reservation.java:51`](../../../services/card-management/src/main/java/com/processing/cardmanagement/models/Reservation.java)                      |
| `PanGenerator`, `LuhnValidator`    | генерация PAN с контрольной цифрой                                                 | чистый unit                                                 | [`services/`](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/)                                                                                                                                                                         |
| `CardRepositoryPersistenceAdapter` | JPA, пессимистическая блокировка, скрытие DELETED                                  | реальная БД (integration)                                   | [`CardJpaRepository.java:20`](../../../services/card-management/src/main/java/com/processing/cardmanagement/repositories/CardJpaRepository.java), [`CardEntity.java:22`](../../../services/card-management/src/main/java/com/processing/cardmanagement/models/CardEntity.java) |
| `CardEventNotifierImpl`            | запись события в outbox в той же транзакции                                        | mock `OutboxEventProcessor`                                 | [`CardEventNotifierImpl.java:17`](../../../services/card-management/src/main/java/com/processing/cardmanagement/events/CardEventNotifierImpl.java)                                                                                                                             |
| `OutboxEventProcessorImpl`         | публикация + переходы PENDING → PROCESSED / FAILED                                 | mock `RabbitTemplate` (unit); реальный брокер (integration) | [`OutboxEventProcessorImpl.java:35,48`](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxEventProcessorImpl.java)                                                                                                                  |
| `OutboxProcessor`                  | планировщик                                                                        | integration (Awaitility)                                    | [`OutboxProcessor.java:21`](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxProcessor.java)                                                                                                                                       |