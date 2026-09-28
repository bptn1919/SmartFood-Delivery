# AmoMeal backend — Spring Boot port of `backend/` (Django)

This is a **full, faithful rewrite** of `../backend` (Django) into Spring Boot,
targeting the **same PostgreSQL + MongoDB + Redis data** and the **same REST/
WebSocket contract**, so `../FE-admin` keeps working against this backend
without changes. `../backend` is the spec of truth — read its code, not just
this file, before porting any module. This file exists so every session/
subagent reuses the same decisions instead of re-deriving them (saves tokens).

Progress tracking: **`PROGRESS.md`** in this directory. Update it after every
module. Read it first to see what's already done and what convention was used
last, before starting new work.

## 0. Non-negotiables

1. **Business logic must match Django exactly**, including its quirks (see
   §6 "Known quirks to preserve"). This is a port, not a redesign. If you spot
   a real bug in the Django code, port the existing behavior faithfully and
   leave a `// PORT-NOTE:` comment — do not silently "fix" it.
2. **Response envelope contract must match byte-for-byte in shape** (§3) —
   FE-admin depends on it.
3. **Fresh, clean DB schema** (decided with the user — no need to reverse
   engineer Django's exact tables/columns). See §5.
4. **`mongo_chat` is NOT ported** (decided with the user — only the SQL-backed
   `chat` app is). No MongoDB, no Firebase push in this rewrite. `tracking`'s
   websocket route (previously mounted through `mongo_chat/routing.py` in
   Django) gets its own route in the Spring WebSocket config instead.
5. Every module port must **compile** (`./mvnw -q compile`) and have
   **passing tests** before being marked done in `PROGRESS.md`.
6. **Bug policy exception (user decisions so far, all "fix it")**: when a
   Django bug makes a *core user-facing flow never work* (not an obscure
   admin path), don't just preserve it — implement the working behavior
   behind the preserved-bug option if cheap, flag it prominently at the top
   of your report, and let the orchestrator ask the user. Already decided
   "fix": password reset, PayOS payment-confirm outcome, chef withdrawal
   signature, multi-chef escrow, Gemini recipe timeout, meal→dish matching
   threshold 0.6 (NUTRITION_API.md), certificate "food-safety certified"
   badge (Django filters status/type values that don't exist → always false;
   working rule FOOD_SAFETY+ACTIVE is the default). **Post-port user
   decisions (2026-09-25): fix the preserved authorization gaps (order
   read/cancel, voucher create, payment endpoints, cart toggle_select must
   check ownership/role), enforce chef/dish suspension, add a scheduled PayOS
   reconciliation job (done); all the `preserve-*` fixes are confirmed.
   2026-09-25 (later): the user pointed at a NEWER Django reference,
   `backend-edit/Personalized-Food-Delivery-Platform/backend` (git repo; latest
   commits 9939179 outbox+Redis circuit breaker, 1341f0c DB-first refresh
   tokens, 3fa7cdc payment reuse) — port those changes to Spring (Redis-outage
   tolerance is now IN scope, no longer deferred). Use `git log/show` there,
   don't read the whole tree.** Obscure dead code (`restore_ingredient`,
   `restore_menu`) stays preserved.
7. **One module at a time.** Never run `./mvnw test` concurrently with
   another agent in this directory — shared `target/` breaks both builds.
   Before testing, `docker version` must succeed (start Docker Desktop and
   wait if not — Testcontainers needs it).

## 1. Stack & versions

- Java 21, Spring Boot **4.0.8** (note: Boot 4 renamed/split a lot of things
  vs. Boot 3 — verify unfamiliar API surface by compiling, not by memory,
  a lot of tutorials online are still Boot-3-era and will mislead you.
  Concrete gotchas hit so far, check `~/.m2`/Maven Central for others before
  guessing:
  - `spring-boot-starter-webmvc` not `-web`; `spring-boot-starter-aspectj`
    not `-aop`; each starter has its own `-test` companion artifact, e.g.
    `spring-boot-starter-data-jpa-test`.
  - `spring-retry` is NOT in Boot 4's BOM — pin its version explicitly.
  - **Flyway now needs `spring-boot-starter-flyway`** (a real starter) —
    unlike Boot 3, just adding `flyway-core` to the classpath does NOT
    trigger autoconfiguration anymore; it silently no-ops (Hikari connects,
    Hibernate then fails schema validation with "missing table", and
    `FlywayAutoConfiguration` doesn't even appear in the conditions
    report — that's the tell). Same "starter, not raw dependency" pattern
    is worth checking for other formerly-automatic Boot 3 integrations.
  - Autoconfiguration classes moved to per-feature packages, e.g.
    `org.springframework.boot.hibernate.autoconfigure.HibernateJpaConfiguration`
    instead of living in one monolithic `spring-boot-autoconfigure` jar.
  - `Argon2PasswordEncoder` (used for password hashing, §5/§9) needs
    `org.bouncycastle:bcprov-jdk18on` on the runtime classpath — it is NOT
    pulled in transitively by `spring-security-crypto`, and the failure mode
    is a `NoClassDefFoundError` at first actual use (encode/matches), not at
    startup — so a context-loads-only test won't catch it, you need a test
    that actually calls the encoder (this bit `AuthControllerTest`, already
    fixed).
  - `spring-boot-starter-webmvc` does **not** autoconfigure a
    `RestClient.Builder` bean (injecting one → `NoSuchBeanDefinitionException`).
    For outbound HTTP (AI service, Gemini, PayOS...) build it yourself with
    `RestClient.builder()` inside the client component, behind a small
    interface so tests can swap a fake / embedded JDK `HttpServer` — see
    `review.service.RestClientAiModelClient` and `payment`'s
    `FakePayOsServer` for the two existing patterns. Never call real
    external services from tests.
  - **Boot 4 defaults to Jackson 3, not Jackson 2** — package root is
    `tools.jackson.*`, not `com.fasterxml.jackson.*`. The auto-configured
    bean is a `tools.jackson.databind.json.JsonMapper` (injectable as its
    supertype `tools.jackson.databind.ObjectMapper`) — there is no
    `com.fasterxml.jackson.databind.ObjectMapper` bean unless you
    deliberately add the Jackson-2-compat starter, which we haven't. If a
    class needs to inject an `ObjectMapper`, use the `tools.jackson`
    package. `tools.jackson.core.JacksonException` replaced the
    Jackson-2-era `JsonProcessingException` **and is now an unchecked
    `RuntimeException`** — don't wrap read/write calls in unnecessary
    try/catch out of Jackson-2 habit. `TypeReference` moved to
    `tools.jackson.core.type.TypeReference` (same usage pattern).
- Maven (`./mvnw`, wrapper already configured — no system Maven needed).
- Postgres driver, Spring Data JPA, Spring Data MongoDB, Spring Data Redis,
  Spring Security, Spring WebSocket, Spring Validation, Spring Mail,
  Actuator, Lombok — already in `pom.xml`.
- Add per-module deps only when actually needed (e.g. `pgvector-java` for
  `recommendation`, AWS SDK v2 `s3` for `attachment`, `firebase-admin` for
  `mongo_chat` push notifications, `google-genai` Java SDK or plain HTTP for
  Gemini calls in `recommendation`/`report`/`verification`). Check Maven
  Central for the artifact/version before adding — don't guess coordinates.
- `jjwt` (io.jsonwebtoken, 0.12.x api+impl+jackson) for JWT — added for the
  `security` module.

## 2. Package layout

Base package: `com.amomeal.marketplace`

```
common/
  response/        ApiResponse<T>, ResponseEnvelopeAdvice (ResponseBodyAdvice)
  exception/        ApiException (abstract base), GlobalExceptionHandler
config/            CORS, Jackson, OpenAPI, async/scheduling enablement
security/          JwtService, DjangoCompatiblePasswordEncoder, JwtAuthFilter,
                   SecurityConfig, refresh-token Redis store
<module>/          one package per Django app, e.g. dish/, order/, payment/...
  entity/          JPA @Entity classes (or @Document for Mongo)
  repository/      Spring Data repositories
  service/         business logic (mirrors Django services/*.py)
  web/             @RestController (mirrors Django api.py)
  dto/             request/response DTOs (mirrors Django schemas/*.py)
  exception/       module's ApiException subclasses (mirrors exceptions/<module>.py)
```

Module → Django app name mapping is 1:1 except: `mongochat` ← `mongo_chat`
(Java package names can't have underscores by convention).

## 3. Response envelope contract (READ CAREFULLY — unusual)

Ground truth: `../backend/utils/router/api.py` (`BaseAPI.create_response`) and
`../backend/utils/router/exception.py`.

**Success** (any 2xx from a controller):
- Actual HTTP transport status is **always forced to 200**, even if the
  endpoint's semantic status is 201/204/etc.
- Body: `{"data": <payload>, "message_code": "SUCCESS", "message": "Success",
  "error_code": 0, "current_time": "<iso timestamp>"}` — UNLESS the semantic
  status wasn't 200, in which case `error_code` carries that original status
  (e.g. 201) while `data`/transport status behave the same.

**Error** (an exception was thrown):
- Actual HTTP transport status **is** the real error status (401, 404, 500…).
- Body: `{"data": <detail>, "message_code": "<CODE>", "message": "<msg>",
  "error_code": <same status as transport>, "current_time": "..."}`.

**All JSON keys are snake_case** (`message_code`, not `messageCode`) — global
`spring.jackson.property-naming-strategy: SNAKE_CASE` in `application.yml`
handles this automatically for every DTO/record's normal camelCase Java
fields, both directions (request bodies too, e.g. `phone_number`,
`refresh_token`). **You do not need `@JsonProperty` for a mechanical
camelCase→snake_case mapping** — only add it for a name that doesn't map
that way (an acronym, etc.). This was a real, live bug for a while: the
envelope and the `users` module's DTOs shipped camelCase (matching this
port's own — wrong — expectations, not Django/FE-admin's real contract)
until caught mid-`dish`-port by cross-checking FE-admin source and fixed
project-wide. If you're verifying a new module's JSON shape, check against
`FE-admin/src/services/*.js`, not just against what this backend itself
outputs — self-consistency isn't the same as correctness.

Implementation: `common/response/ApiResponse.java` + a
`ResponseBodyAdvice<Object>` (`ResponseEnvelopeAdvice`) that wraps controller
returns (reading the servlet response's already-applied status, then forcing
it back to 200) and a `@RestControllerAdvice` (`GlobalExceptionHandler`) for
exceptions that returns `ResponseEntity.status(realStatus).body(envelope)`.

**Gotcha already hit once, don't repeat it**: detect "this body is already
an `ApiResponse` envelope" in `beforeBodyWrite` by `instanceof` on the actual
runtime `body`, NOT in `supports()` by inspecting `MethodParameter`. A
handler returning `ResponseEntity<ApiResponse<T>>` erases to
`ResponseEntity.class` at the `MethodParameter` level — `supports()` can
never see the `ApiResponse` generic argument, so a check there silently
never fires and error responses get double-wrapped (real bug, caught by
`AuthControllerTest`, since fixed — see the class javadoc for the full
explanation).

`current_time`: ISO-8601 string (`Instant`/`OffsetDateTime`, Jackson default).
Exact microsecond formatting doesn't need to match Python's — this field is
diagnostic only, not parsed by FE-admin (verify this assumption still holds
for a given screen before relying on it).

## 4. Exceptions

Base class `common/exception/ApiException.java`: abstract, fields
`httpStatus` (Spring `HttpStatus`), `messageCode` (String), `detail` (Object,
nullable) — `message` comes from `RuntimeException.getMessage()`.

Every exception in `../backend/exceptions/<module>.py` → one Java class in
`<module>/exception/` extending `ApiException`, same `error_code` →
`httpStatus`, same `message_code`, same `message` text (Vietnamese strings
stay Vietnamese — this is user-facing FE copy, do not translate). Exceptions
with dynamic `detail` (e.g. `VoucherMinOrderException`,
`NutritionValidationException`, `VerificationDocumentError`) replicate the
same constructor logic that builds the detail payload.

`GlobalExceptionHandler` (in `common/exception/`) handles, in this order:
`ApiException` → its own status/code; Spring's `MethodArgumentNotValidException`
/ `HandlerMethodValidationException` (bean validation failures) →
`message_code="VALIDATION_ERROR"`, detail = `{field: [messages]}`; Spring
Security `AuthenticationException`/`AccessDeniedException` →
`UNAUTHORIZED`/401; anything else → 500 `CONTACT_ADMIN_FOR_SUPPORT`.

## 5. Database — fresh schema (decided with the user)

No live Django data needs to be preserved. Design JPA entities using normal
Java/Spring conventions (Hibernate's default naming strategy is fine —
`camelCase` field → `snake_case` column, class name → snake_case table name).
Use **Flyway** for migrations (`src/main/resources/db/migration/V1__*.sql`,
etc.) — `spring.jpa.hibernate.ddl-auto=validate`, schema changes go through
versioned Flyway scripts, not Hibernate auto-DDL, from the start (this is the
one place we deliberately do better than the Django project, which has no
schema-as-code review gate).

Still read Django's `models.py` for **field list, types, constraints,
relationships, and validation rules** — that part of the spec must be ported
faithfully (which fields exist, nullable/unique/max_length, FK
cascade behavior, index intent). Only the physical naming/schema-ownership
approach is free to be idiomatic Java instead of mirroring Django's table
names.

- Enums: Django `choices=` on a `CharField` → map as a Java `enum` + JPA
  `@Enumerated(EnumType.STRING)` with matching value semantics.
- `recommendation` uses the **pgvector** Postgres extension with raw SQL in
  the Python code (not the ORM) — see `../backend/RECOMMENDATION_FLOW.md`.
  Port those raw-SQL queries via `JdbcTemplate`/`@Query(nativeQuery=true)`,
  using the `pgvector-java` library's `PGvector` type for the vector column;
  add the `CREATE EXTENSION IF NOT EXISTS vector;` statement in a Flyway
  migration.
- No MongoDB in this rewrite (see §0.4) — do not add
  `spring-boot-starter-data-mongodb`.

**Passwords — critical, don't skip**: Django's `PASSWORD_HASHERS` writes
Argon2id-encoded strings (format `argon2$argon2id$v=19$m=...,t=...,p=...$
<b64 salt>$<b64 hash>`) for new users, with PBKDF2-SHA256
(`pbkdf2_sha256$<iterations>$<salt>$<b64 hash>`) as a legacy fallback for
older rows. Existing users in the DB already have one of these two formats
stored in `users_customuser.password`. Spring Security's own
`Argon2PasswordEncoder`/`Pbkdf2PasswordEncoder` do **not** read Django's
encoded string format directly — implement a custom
`PasswordEncoder` (`security/DjangoCompatiblePasswordEncoder.java`) that
parses both formats for `matches()` (so existing users can still log in) and
encodes new passwords as Argon2id in the **same encoded-string shape** Django
uses (so the DB stays readable by either stack during any transition window).
Verify the exact Argon2 parameter defaults Django's `Argon2PasswordHasher`
uses (time_cost/memory_cost/parallelism) against the installed Django version
before hardcoding them — read `django.contrib.auth.hashers` source (via pip
install location in `backend/venv` if present, or Django docs) rather than
guessing.

## 6. Known quirks to preserve (do not "fix" silently)

- Validation errors return HTTP/`error_code` **401**, not 400/422
  (`utils/router/exception.py::_validation_error_handler` hardcodes 401 —
  looks like a bug, but FE-admin may already special-case it).
- Only `chat` (SQL-backed) is ported — `mongo_chat` is dropped (§0.4).
  Firebase push notifications (which lived in `mongo_chat/notifications.py`)
  are **out of scope** for this rewrite unless the user asks for them to be
  re-added on top of `chat`.
- `TrackingConsumer` (chef live location websocket) has **no auth check** at
  all in Django — port this as-is (don't add auth Django doesn't have)
  unless the user asks you to fix it; flag it instead.
- Only **two** Celery tasks exist project-wide: `dish`'s
  `release_expired_stock_holds` (every 30s) and `order`'s
  `send_order_notification_task` (async, retried). Port these as Spring
  `@Scheduled`/`@Async`+`@Retryable` — **no message broker needed**, don't
  over-engineer a queue for this.
  (Post-port, Spring-only third job: `payment`'s scheduled PayOS reconciliation, `app.payment.reconciliation.*`, no Django equivalent.)
- `djangorestframework_simplejwt` is in `requirements.txt` but its
  authentication class is commented out in settings — it is **not** actually
  used. The one real JWT implementation is `users/tokens.py`
  (HS256, custom claims `user_id`/`typ`/`iat`/`exp`) reused by all three
  transports (ninja HTTP, DRF `chat`, Channels websocket). Port this as the
  single `JwtService` — don't build three separate implementations.
- Redis is partitioned into 4 logical DBs by number (0 Channels/pubsub,
  1 refresh tokens, 2 stock-reservation holds, 3 Celery broker — #3 becomes
  irrelevant once Celery is gone, reuse for Spring's needs or drop). Keep
  DBs 0–2 semantically separate the same way.
  **DECIDED (during the `dish` port): one dedicated
  `LettuceConnectionFactory` + `StringRedisTemplate` per logical DB index**,
  not a shared client with `select(n)`. Current allocation (also documented
  in `application.yml`): **DB 0** = refresh tokens (the auto-configured
  `StringRedisTemplate`, used by `security/RefreshTokenService`);
  **DB 1** = dish stock-hold counters (`dish/config/StockRedisClient`,
  index configurable via `app.stock.redis-database`). Follow
  `StockRedisClient` as the template for any future module needing its own
  DB — and note the trap it documents: **do NOT expose the extra
  `LettuceConnectionFactory` as a `@Bean`.** Boot's Redis autoconfiguration
  is `@ConditionalOnMissingBean(RedisConnectionFactory.class)`, so a second
  factory bean silently switches OFF the auto-configured one and breaks
  every other module's Redis access. Keep it as a private field of a
  `@Component` that owns its lifecycle (`afterPropertiesSet()`/`start()` in
  the constructor, `destroy()` in `@PreDestroy`).
  Second trap from the same port: resolve host/port from the injected
  `DataRedisConnectionDetails` bean, **not** from `spring.data.redis.*`
  `@Value`s — Testcontainers' `@ServiceConnection` contributes a
  ConnectionDetails *bean* and never sets those properties, so a
  property-based client silently points at localhost in tests.

## 7. Module status & complexity

See `PROGRESS.md` for live status. Complexity tiers (guides which model to
delegate a module's port to — cheap model for simple/mechanical CRUD,
strongest model for logic-heavy or money/security-sensitive modules):

- **Foundation** (do first, blocks everything): `common`, `security`, `users`
  (core auth surface only — register/login/refresh/logout/OTP).
- **Simple** (CRUD-shaped, low branching): `attachment`, `menu`, `tracking`,
  `certificate`, `chat` (SQL model + basic controller; websocket wiring is
  medium), `voucher` (reservation lifecycle bumps this toward medium).
- **Medium**: `cart`, `profile`, `ingredient`, `review`, `admin` (aggregates
  other modules, do last).
- **Complex — money, inventory, or heavy algorithmic logic; use the
  strongest available model and extra scrutiny/tests**: `dish` (stock
  reservation + search + Bayesian ranking), `order` (full lifecycle state
  machine), `payment` (PayOS + wallet/escrow/payout ledger), `recommendation`
  (pgvector + multi-stage scoring/rerank pipeline — read
  `RECOMMENDATION_FLOW.md` fully first), `verification` (KYC + Gemini OCR +
  cross-validation), `report` (Gemini severity classification + suspension
  state machine).

Recommended port order (respects dependencies — a module's port should come
after modules it imports from): `common`/`security`/`users` → `attachment` →
`ingredient` → `dish` → `menu` → `profile` → `cart` → `voucher` → `order` →
`payment` → `review` → `recommendation` → `certificate` → `verification` →
`report` → `chat` → `tracking` → `admin`.

## 8. Per-module port checklist (use this for every subagent task)

1. Read `../backend/<app>/models.py`, `orm/*.py`, `services/*.py` (or
   `services.py`), `schemas/requests.py`/`responses.py`, `api.py`,
   `signals.py`, `tasks.py` if present, and `../backend/exceptions/<app>.py`.
   Also grep the whole `../backend` tree for cross-app imports of this app's
   models/services (side effects other modules depend on).
2. Check `RECOMMENDATION_FLOW.md`/`ORDER_*` docs etc. in `../backend/` if one
   exists for this module — they're written from actual code, treat as
   accurate context, not fluff.
3. Entities → repositories → services (business logic 1:1, including
   validation order and error precedence) → DTOs → controller → exceptions.
4. Preserve exact endpoint paths/HTTP methods/request-response field names
   used by ninja/DRF (grep `FE-admin/src` for the actual paths it calls, as a
   cross-check against `api.py`).
5. Write unit tests for services (business logic) and a
   `@SpringBootTest`/`MockMvc` test per controller happy-path + key error
   cases. Use Testcontainers for anything touching Postgres/Mongo/Redis.
6. `./mvnw -q compile` and `./mvnw -q test` must pass. **Name every test class
   `*Test.java`/`*Tests.java`** (Surefire's default include pattern) even for
   full-stack/Testcontainers-backed tests — `*IT.java` is a Failsafe-plugin
   convention this project doesn't use (no `failsafe` plugin configured),
   and `mvn test`/`./mvnw test` silently skips anything not matching its
   pattern. A test that compiles but never actually runs is worse than
   having no test — always confirm the class appears in
   `target/surefire-reports/*.txt` with a real "Tests run: N" count, not just
   a green exit code, before marking a module done.
7. Update `PROGRESS.md`: status, model/effort used, anything flagged for
   human review (ambiguities, the two chat-systems question, etc.).

## 8b. Transaction boundaries in shared services (real bug already hit)

`RefreshTokenService`'s `@Modifying` repository queries failed with
"No active transaction for update or delete query" when it relied on the
*caller* (`AuthService`) to provide a `@Transactional` boundary — even
though the caller WAS annotated `@Transactional`. Root cause not fully
isolated (self-invocation/propagation timing, not worth chasing further);
the robust fix, applied and verified by `AuthControllerTest`, is that
**any shared service with its own DB writes puts `@Transactional` on its
own public entry methods**, so it's correct regardless of what the caller
does. Don't rely on an ambient transaction from three layers up — every
module's service layer should be transactionally self-sufficient at its own
public API boundary.

**Sharper corollary, hit during the `users` OTP port — a write-then-throw needs
its OWN transaction, not just *a* transaction.** Django code written under
autocommit routinely does `record.save(...)` and *then* `raise` (the OTP
failed-attempt counter, `users/models.py::UserOTP.verify`, is the canonical
case). Ported literally into Spring, that save joins the caller's
`@Transactional` and the exception rolls it straight back — the counter never
increments and a 3-attempt lockout silently becomes unlimited guesses. Any time
you port a Django "persist, then raise" pair, the persist must go through a
**separate bean** annotated `@Transactional(propagation = REQUIRES_NEW)` (see
`users/service/OtpAttemptRecorder`). Separate bean is not optional: a
`@Transactional` method called from within the same class is self-invocation
and never passes through the proxy, so the annotation does nothing. Grep for
`save(` immediately followed by `throw` when reviewing a port.

## 9. Things intentionally deferred / need a human decision

- `TrackingConsumer` auth gap — port as-is, flag for the user.
- Password hashing: since the DB is fresh (§5), there is **no legacy Django
  hash format to stay compatible with**. Use standard Spring Security
  `Argon2PasswordEncoder` (OWASP defaults) directly — simpler than the
  Django-compatible custom encoder originally planned. Still Argon2id, to
  match the Django project's security posture/intent.

## 10. Running it locally (see DEV.md)
- Local dev = `docker-compose.dev.yml` (ports 15432/16379) + `-Dspring-boot.run.profiles=dev` + `scripts/dev-seed.sh`. The `dev` profile only logs mails/OTPs (`[DEV-MAIL]`).
- Request-body `Instant`s are deliberately lenient (naive datetime-local = UTC, `config/LenientInstantConfig`); unknown routes 404, missing params/bad JSON/bad UUID are 401 `VALIDATION_ERROR` (not 500).
- If `spring-boot:run` shows `NoClassDefFoundError` for an inner class right after a failed compile, run `./mvnw -q clean compile` (stale `target/classes`).
