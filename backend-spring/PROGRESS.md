# Port progress — Django `backend/` → Spring Boot `backend-spring/`

Legend: ⬜ not started · 🔶 in progress · ✅ done (compiles + tests pass) ·
🚩 done but flagged for human review
## Port complete — remaining human decisions

All 19 Django apps are ported (`mongo_chat` dropped by decision). Nothing below blocks running the system; each is a product/security call. Details are in the linked open questions.

**1. Authorization gaps preserved from Django (say the word to add owner/role checks)**
- [x] **RESOLVED 2026-09-25 (user: fix).** `order`: read = order's customer / chef / ADMIN; cancel, checkout edits, place-order, apply shop/platform voucher = owning customer only (403 `HTTP_ERROR` via `OrderHttpException`). Chef-lifecycle checks unchanged.
- [x] **RESOLVED 2026-09-25.** `POST /api/vouchers` is CHEF or ADMIN only (403 `VOUCHER_NOT_OWNED`); update/delete rule unchanged.
- [x] **RESOLVED 2026-09-25.** `toggle_select` 403 `PERMISSION_DENIED` (new `CartPermissionDeniedException`) on an item outside the caller's cart.
- [x] **RESOLVED 2026-09-25.** `payment`: create/cancel = checkout owner; status/info/invoices = owner or ADMIN; chef balance / COD settle = that chef or ADMIN (403 `HTTP_ERROR` via `PaymentHttpException`). Webhook/return untouched; `payos/return` replay made idempotent (an already HOLDING/SUCCESS/RELEASED/REFUND_* payment is no longer re-applied, so a chef-advanced order isn't reset to CONFIRMED_SYSTEM) — it already only acted on PayOS's own status lookup.
- [ ] Chat websocket has no participant check; tracking websocket has no auth at all; `/api/recommendation/users/{id}/profile|features` open to any user — see `chat`, `tracking`, `recommendation`.

**2. Fixes shipped behind `preserve-` flags (default = working; confirm "fix" or flip the flag to restore Django)**
- [ ] `app.report.preserve-order-uuid-bug` (report `order_id` is a UUID string; Django 500s) — `report`.
- [ ] `app.chat.preserve-no-conversation-create-bug` (new `POST /api/chat/conversations/`) — `chat`.
- [ ] `app.tracking.preserve-ws-route-bug`, `app.tracking.preserve-order-tracking-bug` (ws route + order tracking endpoint) — `tracking`. `preserve-zero-avg-rating-bug` defaults to TRUE (Django's always-0 rating kept).
- [ ] `app.certificate.preserve-approved-status-bug` (food-safety badge) — `certificate`.
- [ ] `app.gemini.preserve-recipe-timeout-bug` (Gemini recipe tier) — `recommendation`.
- [ ] `app.recommendation.preserve-dish-match-no-threshold` (0.6 meal-match threshold; already decided "enable", listed for completeness).
- [ ] `app.payment.preserve-django-confirm-outcome-bug` (already decided "fix") — `payment` #1.
- [ ] `admin`: voucher list/create response also returns `discount_type` (Django omits it although FE-admin reads it); `/api/admin/voucher` accepts naive `datetime-local` strings — see `admin` open questions.

**3. Not enforced / not decided**
- [x] **RESOLVED 2026-09-25 (user: enforce).** Chef with `is_accepting_orders=false` or `suspension_level=SUSPENDED` -> checkout and place-order 400 `CHEF_SUSPENDED` (new `order.exception.ChefSuspendedException`); dish with `is_suspended=true` -> add-to-cart / set-quantity(>0) / checkout / place-order 400 `DISH_SUSPENDED` (new `dish.exception.DishSuspendedException`); suspended dishes hidden from customer dish list (`/api/dishes/`), `/api/dishes/search` and `/api/dishes/top` (ADMIN and the owning chef's own list still see them). Report leftovers: FULL_LOCK now clears `Dish.is_suspended` on the DISH_LOCKs it supersedes (else they'd be hidden forever); `lift` now also accepts REJECTED suspensions (no dead end). Follow-up: chef-facing hiding of the chef profile / dishes in `chefs` listings is NOT done (orders are blocked regardless); a chef can still flip `is_accepting_orders` back to true themselves via profile update, but a FULL_LOCK (`suspension_level=SUSPENDED`) keeps blocking.
- [ ] Redis outage: place-order and login fail while Redis is down (stock holds / refresh tokens live in Redis). The user asked about this; behavior not decided.
- [x] Scheduled PayOS reconciliation job — DONE 2026-09-25 (`PaymentReconciliationScheduler`/`PaymentReconciliationService`; see Session log). Was: no scheduled job, a lost webhook was only healed when the return URL / sync endpoint was hit (`payment` #4/#7).


| Module | Status | Model used | Notes |
|---|---|---|---|
| Project skeleton (Maven/pom/Initializr) | ✅ | Sonnet 5 (orchestrator) | Spring Boot 4.0.8, Java 21, group `com.amomeal`, artifact `marketplace` |
| CLAUDE.md conventions | ✅ | Sonnet 5 (orchestrator) | — |
| `common` (response envelope + exception handling) | ✅ | Sonnet 5 (orchestrator) | Verified by AuthControllerTest; caught+fixed a real double-wrap bug (§3 gotcha) |
| `security` (JWT + Argon2 password hashing) | ✅ | Sonnet 5 (orchestrator) | JWT (jjwt), RefreshTokenService (Redis+DB hybrid), SecurityConfig, JwtAuthenticationFilter. Caught+fixed a real transaction-boundary bug (§8b) |
| `users` (full auth surface: roles + OTP) | 🚩 | Opus 5 (subagent) | **Second pass complete.** Real role model ported (Django auth Groups CUSTOMER/CHEF/ADMIN → `CustomUser.roles` + `app_user_role`), Django's Group→Permission matrix ported as `RolePermissions`, real `ROLE_*` authorities wired into `JwtAuthenticationFilter`, all OTP flows (signup/verify/forget-password/reset/email-change) + `/me`, `/is-chef`, `password/change`, PUT `/logout`. Login check-order now verified 1:1 against Django (email → password → is_active). 31 new/changed tests. Flagged for: Django's dead password-reset path preserved, the email-change AttributeError deviation, and the non-Django `/register` endpoint — see Open questions. |
| `attachment` | ✅ | Sonnet 5 (orchestrator) | Full port done, all tests pass. Storage: real S3 (AWS SDK v2) is the default backend, matching Django's actual hard-S3 behavior; local filesystem storage kept as an explicit `app.storage.backend=local` opt-in. See Session log for the resolved storage-decision detail and the LocalStack-based S3 test. |
| `ingredient` | 🚩 | Sonnet 5 (subagent) | Full CRUD/search/alias/favourite-allergy/Excel import-export ported and tested. **ADMIN/CHEF role checks are now the real thing** (`hasRole('ADMIN')`/`hasRole('CHEF')` against Django's auth-Group model, ported in the `users` second pass — the old `isStaff`/no-check stand-in is gone). Still flagged: suggestion approve/reject simplified pending `dish`; a `restore_ingredient` Django bug is preserved as-is (see notes below). |
| `dish` | 🚩 | Opus 5 + Sonnet 5 (subagent, resumed after an Opus rate limit mid-task) | Full port: 6 entities, 6 repos, 8 services (nutrition pipeline, 3-layer validation, stock reservation, search, Bayesian ranking, locations, inventory, @Scheduled sweep), 3 controllers (34 endpoints), 12 exceptions, `V4__init_dish.sql`. 83 new tests, all green. Found the camelCase/snake_case envelope bug (below) — **fixed globally same day**, not just flagged. **ADMIN/CHEF gating and the `has_perm` half of `@require_object_permission` are now real** (ported in the `users` second pass; `DishService.assertCanModify` checks `dish.change_dish` + owner-or-ADMIN). Seams: `DishUserContext` closed by `profile`; **`ExpiredOrderHandler` closed by `order`** (`OrderExpiredOrderHandler`, `@Primary`) — the 30 s sweep now does all three Django steps; **`DishStatsProvider`'s `sold_count` half closed by `order`** (`OrderDishStatsProvider`, `@Primary`), `review_count`/avg-rating half still zero until `review`. `stock_reservation.order_item_id` is now a **real FK** to `order_item` (`V10`). Still flagged: the in-memory `sold_count` sort (see Open questions). |
| `menu` | 🚩 | Sonnet 5 (subagent) | Full CRUD + Menu↔Dish link ported (reuses `dish`'s `Dish` entity/repository, not redeclared). Real CHEF/ADMIN role gating via `RolePermissions`/`hasRole`, matching `dish`'s convention. 41 new tests (27 unit + 14 full-stack). **Found and preserved three genuine Django quirks specific to this module** (all PORT-NOTE'd + regression-tested): (1) `restore_menu` can never actually succeed — same class of dead-code bug as `ingredient`'s `restore_ingredient`; (2) `get_menu`/`get_all_dishes_in_menu` ignore the `deleted` flag entirely (only `status==ACTIVE` gates visibility), so a soft-deleted-but-still-ACTIVE menu stays publicly visible; (3) `add_dish_to_menu` is the one object-level endpoint where an ADMIN who does not own the menu can still succeed, because Django's service method for it never re-checks ownership (every sibling endpoint does, strictly, with no ADMIN bypass — stricter than `dish`). See Open questions for detail. |
| `profile` | 🚩 | Sonnet 5 (subagent) | Full CRUD (chef profile, customer profile, addresses, favourite dishes, chef payment) + the CUSTOMER→CHEF upgrade endpoint (`POST /api/auth/upgrade-to-chef`, built here calling `AuthService.upgradeCustomerToChef`) + the two seam interfaces `dish`/`users` left open (`DishUserContext`, `CustomerOnboardingProvider`), now real. 63 new tests (55 unit/full-stack for this module + the cross-module upgrade-to-chef integration test). Flagged: `ChefPaymentController` duplicate-class dead code, `api_chef_payment.py` never mounted, several address/onboarding Django quirks preserved as-is — see Open questions. |
| `cart` | 🚩 | Sonnet 5 (subagent) | Full port: `Cart`/`CartItem` entities (reusing `dish`'s `Dish`/`DishRepository`/`DishAvailabilityRepository` and `users`' `CustomUser`, none redeclared), repositories, `CartService`, DTOs, `CartController` (6 endpoints), 3 exceptions, `V8__init_cart.sql`. 54 new tests (32 unit + 22 full-stack). **Confirmed cart does NOT reserve stock** (grepped `stock_reservation.py`'s callers — only `dish`'s own inventory paths and future `order` call reserve/confirm/release) — it only reads `DishAvailability.available_quantity` as a soft UX cap. **No voucher seam needed** (grepped `cart/` for any voucher reference — none exist; voucher/pricing preview is entirely `order`'s job). Left a forward seam for `order` — **now consumed by `order`'s `OrderService.checkout`/`placeOrder` (2026-09-23), signatures unchanged**: `CartService.getSelectedCartItemsByUser`/`getDeliveryDatesOfSelectedItems`/`clearSelectedItems`, mirroring Django's `CartORM`/`CartService` methods that `cart`'s own `api.py` never exposes over HTTP either — order's checkout calls these three directly in Django. Confirmed cart's endpoints are **not role-gated** (Django's `api.py` has `auth=AuthBear()` and no `@require_group`/`@require_permission` anywhere) — any authenticated role (CUSTOMER/CHEF/ADMIN) can use their own cart; tested explicitly. Several real Django bugs preserved verbatim, all PORT-NOTE'd — see Open questions. |
| `voucher` | 🚩 | Sonnet 5 (subagent) | Full port: `Voucher`/`AppliedVoucher` entities + 3 enums, repositories (incl. `@Lock(PESSIMISTIC_WRITE)` row-locking for the reservation flows), `VoucherService` (CRUD, order-time validation, reservation lifecycle), `VoucherController` (8 endpoints), 8 exception classes, `V9__init_voucher.sql`. 53 new tests. Confirmed the reservation system is pure Postgres (no Redis) and expiry is a synchronous lazy check, not a scheduled job. Confirmed and tested the ADMIN-group-OR-`is_staff` authorization quirk exactly. Flagged: voucher endpoints have NO role gate at all (any authenticated user, even CUSTOMER, can create a voucher); a real reachable Django bug in the partial unique constraints that blocks re-reserving a shop voucher for an order once it expires — see Open questions. **Forward seam now consumed by `order`** (primitive-ized `applyShopVoucherReservation`/`applyPlatformVoucherReservation`/`calculateNetSubtotal` called as-is; `order` writes RESERVED→USED/CANCELLED/EXPIRED itself via its own `OrderAppliedVoucherRepository` over the same entity). The two `applied_voucher` FKs V9's comment suggests were **deliberately not added** (see Open questions). |
| `order` | 🚩 | Opus 5 (subagent, resumed after a rate-limit pause) | Full port: `Checkout`/`Order`/`OrderItem`/`NotificationIdempotencyKey` + 4 enums, 5 repositories (incl. `OrderAppliedVoucherRepository`, an order-owned second repository over `voucher.AppliedVoucher`), `OrderService` (checkout, place-order with stock reserve/confirm/release compensation, chef lifecycle, cancel, voucher apply + pro-rata discount recalculation), `OrderStateMachine` (1:1), `OrderMapper`, `OrderNotificationService` (`@Async`+`@Retryable`, Django's idempotency-key protocol exactly), `OrderExpiredOrderHandler` + `OrderDishStatsProvider` (`@Primary` over `dish`'s stubs), `OrderPaymentGateway` seam (default no-op, **now displaced by `payment`'s real `@Primary` `PaymentOrderPaymentGateway`**, 2026-09-23 — order source untouched), Ahamove fee estimator (15000 fallback), 3 controllers (19 endpoints), 2 exceptions, `V10__init_order.sql` (incl. the real `stock_reservation.order_item_id` FK). **145 new tests** (511/511 project-wide). Flagged: several real Django authorization gaps (no ownership check on read/cancel/checkout-edit/voucher-apply), the `to_response_with_info` crash for chefs without a `ChefProfile`, COD voucher USED-mark not reverted on failure, `place_order` has no status guard, and the payment/webhook half (T3/T3′ recovery) necessarily waits for `payment` — see Open questions. |
| `payment` | 🚩 | Opus 5.5 (subagent) | Full port: 11 entities (`PaymentTransaction`/`State`/`Event`, `CustomerPaymentInfo`, `InternalWallet`, `WalletTransaction`/`State`, `WithdrawalFailureLog`, `SettlementRecord`, `PayoutLedger`, `ChefCodBalance`), 13 repositories (incl. payment-owned `PaymentOrderRepository`/`PaymentAppliedVoucherRepository` over order's/voucher's entities), PayOS provider over plain JDK `HttpClient` + byte-exact Python signing (`PayOsSignature`/`PyCompat`, pinned to vectors computed with the real Python code + the installed `payos==1.1.0` SDK), `PaymentService` (create/sync/cancel/webhook/escrow confirm), `PaymentStateService` (transition table + HMAC audit chain + `assert_payment_confirmed`), `WalletService` (row-locked credits, ledger chain, integrity), `PaymentRefundService`, `WithdrawalService` (OTP → locked debit → signed PayOS payout → settle/revert), `SettlementService`, `BankInfoService`, the real **`@Primary` `OrderPaymentGateway`**, 13 `/api/payment/*` endpoints + the raw (non-envelope) `payos/webhook` & `payos/return`, `V11__init_payment.sql` (incl. Django's append-only trigger on `wallet_transactions`). **80 new tests (591/591 project-wide).** The three money bugs (#1 every PayOS payment ended cancelled+refunded; #2 withdrawals could never pass the integrity check; #3 multi-chef escrow) were **fixed 2026-09-23 on the user's decision** — flag default flipped, canonical 2-dp wallet hashing + signed new wallets + re-sign after payout, per-order escrow from the existing `wallet_transactions.order_uid` ledger (no migration) — **595/595 project-wide**. Remaining open questions #4-#10 are hardening/authorization/quirk items. |
| `review` | 🚩 | Sonnet 5 (subagent) | Full port: `Review`/`ReviewReply` entities (reusing `dish.Dish`/`order.Order`/`order.OrderItem`/`profile.ChefProfile`/`attachment.Attachment`/`users.CustomUser`, none redeclared), 2 repositories, `ReviewService` (create/update/delete/get review + reply CRUD, dish `avg_rating`/`final_score` + chef `rating` recompute on every write) + `ReviewAnalyticsService` (issue trend/top-complained-dishes/heatmap/chef-issue-report/issue-reviews-by-issue), `AiModelClient` seam (interface + `RestClientAiModelClient` real impl over Spring's `RestClient`, never throws — see below) + a fake for tests, 2 controllers (13 endpoints), 12 exception classes, `V12__init_review.sql`. **33 new tests (628/628 project-wide)**: 20 Mockito (`ReviewServiceTest`) + 6 embedded-`HttpServer` AI-client tests (`RestClientAiModelClientTest`, no real AI service) + 7 full-stack MockMvc/Testcontainers (`ReviewControllerTest`). 🔴 Found a project-wide-class Django bug specific to this file, flagged prominently: EVERY exception in `exceptions/reviews.py` sets `status_code` instead of the attribute `APIException.error_code`/its handler actually reads — so `InvalidRatingException`/`DuplicateReviewException`/`OrderNotCompletedException`/`ReviewNotFoundException`/`ReviewReplyNotFoundException`/`ReviewReplyAlreadyExistsException`/`NotDishOwnerException`/`AIModelUnavailableException`/`AIModelInvalidResponseException`/`DishNotOrderedException` are ALL really 500 CONTACT_ADMIN_FOR_SUPPORT-shaped in Django (transport status + body `error_code`), not the 400/404/403/503/502 they look like — `message`/`message_code` are correctly named and DO carry the real diagnostic text/code. Same bug class as `ingredient`'s already-flagged `NutritionValidationException`. **Ported faithfully per this task's explicit instruction** (all ten classes above use `HttpStatus.INTERNAL_SERVER_ERROR`), each with a javadoc explaining the quirk — did NOT silently upgrade them to the "obviously intended" status codes. Say the word if you want these fixed (their `message_code`s already distinguish the cases for any FE that reads them, but the HTTP status alone does not). **AI-service handling verified against the actual Django code, not assumed:** `_predict_review_label` NEVER raises `AIModelUnavailableException`/`AIModelInvalidResponseException` — both are declared in `review/api.py`'s OpenAPI `exceptions=(...)` tuple but are dead code (grepped the whole tree); every failure (unreachable, non-2xx, non-JSON, non-dict, unparseable weight) is caught and falls back to `(weight=0.0, issue=None)`, and the review write ALWAYS proceeds. Ported exactly: `RestClientAiModelClient.predict` never throws, `ReviewService` never catches an AI exception because none is ever thrown. Config: `app.ai-model.base-url`/`app.ai-model.timeout-seconds` (env `AI_MODEL_BASE_URL`/`AI_MODEL_TIMEOUT_SECONDS`, Django's exact names/defaults). **Hit a real Boot-4 gotcha while wiring `RestClient`**: `spring-boot-starter-webmvc` alone does NOT autoconfigure a `RestClient.Builder` bean (`NoSuchBeanDefinitionException` at context startup) — same "starter, not raw dependency" class of surprise CLAUDE.md §1 already documents for Flyway. Fixed by building the `RestClient` directly via the static `RestClient.builder()` inside `RestClientAiModelClient`'s own constructor instead of injecting the (absent) autoconfigured builder bean — needs no new Maven dependency (`RestClient` itself is already on the classpath via `spring-web`). **Stats seam fully closed**: `review.service.ReviewStatsProvider`/`ReviewStatsProviderImpl` (real `review_count`/system-avg-rating) are now composed into `order.service.OrderDishStatsProvider` — the ONE sanctioned edit to `order`'s main source (one constructor field + two one-line delegating method bodies; `OrderDishStatsProvider`'s own prior javadoc pre-approved exactly this), still exactly one `@Primary DishStatsProvider` bean. Verified end-to-end: `ReviewControllerTest.topDishRanking_reflectsRealReviewRatings_notJustNameOrSoldCount` creates two dishes (named so alphabetical order would mislead), 5 reviews each (5⭐ vs 1⭐) through the real `POST /api/reviews/` endpoint, then asserts `GET /api/dishes/top`'s `review_count`/`avg_rating`/Bayesian `score` are real and correctly ordered. **A real, reachable Django bug preserved (not fixed), flagged:** `create_review`/`update_review` call `AttachmentService.handle_attachment(uid)` for its validation side effect only and then discard the return value — a review created with a perfectly valid `attachment_uid` NEVER actually links the attachment (`attachment_url` is always `null` in the response, no matter what). Not in the CLAUDE.md §0.6 "already decided fix" list, and FE-admin doesn't consume any `review` endpoint at all (grepped, zero matches) so there's no known broken consumer — preserved verbatim and regression-tested, flagging for a decision. **Scope reduction, flagged:** the chef-analytics `menu_uid`/`categories` list-shaped filters (`FilterIssueReviewSchema`) are not ported (would need a cross-module join into `menu.MenuDish` for an endpoint with zero known consumers); `dish_uid`/`search` ARE ported. See Open questions for the full list (including the duplicate-`resolve_owner_info`-definition quirk in `ReviewReplyResponse` and the dead `DishNotOrderedException`/AI-exception classes). |
| `recommendation` | 🚩 | Opus 5.5 (subagent) | Full port: 5 entities (`UserFoodPreferenceFeature`, `UserDailyNutrition`, `DailyMealLog`, `DishTranslationMapping`, `DishRecipeSnapshot`) + 5 enums, `V13__init_recommendation.sql` (`CREATE EXTENSION vector`, the two raw pgvector tables `recommendation_dish/user_vector_index` + ivfflat/hnsw indexes verbatim from Django 0003), `VectorIndexService` / `CandidateGenerator` / `ScoringEngine` / `MmrReranker` / `Explain` / `RecommendationPipelineService` / `RecommendationService` (issue profile, features, `find_better_dish_for_issue`) / `DailyNutritionService` (+ pure `DailyNutritionMath`), `RecommendationController` (14 endpoints under `/api/recommendation`), 2 exceptions. pgvector via JdbcTemplate raw SQL with text literals (`CAST(? AS vector)` / `embedding::text`), NOT the pgvector-java type — see VectorIndexService javadoc (float4 parity). `RECOMMENDATION_CONFIG` ported key-for-key with Django's env var names (`app.recommendation.*`). **Numerics pinned against the real Python**: `src/test/resources/recommendation/gen_vectors.py` runs the untouched Django services in backend/venv (ORM/cursor replaced by fixed rows) → `python_vectors.json`; `PythonParityTest` (20 tests) asserts rounded outputs EXACTLY. Gemini behind `GeminiClient` (`RestClientGeminiClient`, plain REST, `RestClient.builder()`), fake in tests. Cross-module Django signals/decorators re-attached WITHOUT editing other modules: AOP `RecommendationSyncAspect` (sync_user_feature on ingredient/profile services, onboarding daily-nutrition write, order-complete `sync_order_meal_logs`) + Hibernate event listeners `RecommendationEntityListeners` (order→COMPLETED and DishIngredient save/delete → vector refresh after commit). **39 new tests (667/667 project-wide).** Flags: Gemini recipe tier never works in Django (fixed by default behind `GEMINI_PRESERVE_RECIPE_TIMEOUT_BUG`); dish-search always matches → parse-meal lower tiers unreachable (preserved); `@require_admin` bypassed; exception status_code quirk — see Open questions. |
| `certificate` | 🚩 | Sonnet 5 (subagent) | Full port of the 7 mounted routes + service-only create/update/attachments; closes profile's `ChefCertificationProvider` seam (`@Primary` `ChefCertificationProviderImpl`); `V14`; 16 new tests (685/685). Flagged: Django's `status="APPROVED"` bug made the food-safety badge always false — fixed by default behind `app.certificate.preserve-approved-status-bug`; see Open questions. |
| `verification` | 🚩 | Sonnet 5 (subagent) | Full port: `ChefVerificationSession`/`ScheduledS3Deletion` (`V15`), 10 exceptions, `GeminiVisionService` (exact prompts/error tables/model `gemini-2.5-flash-lite`, 429 retry) behind its own `GeminiVisionClient` seam (`RestClientGeminiVisionClient`, `RestClient.builder()`; the recommendation `GeminiClient` is text-only) + `ImageFetcher` (HTTP GET of `public_url`, like Django), `RiskEngine`, `VerificationService` (analyze/confirm x3, cross-validate, selfie code, selfie analyze, status), `VerificationFinalizer` (safe identity: masked number + Argon2id hash t=2/m=64MiB/p=1; PENDING certificates + pages), `S3DeletionService` (+ daily `@Scheduled` = Django's `delete_scheduled_s3` cron), `VerificationCertificateReviewHook` (`@Primary`, **closes certificate's seam**), 10 CHEF-only endpoints under `/api/verification`. **81 new tests (766/766 project-wide).** Flagged: InsightFace face match and CCCD-QR scan have no Java engine (seams with Django's failure-outcome defaults) - see Open questions. |
| `report` | 🚩 | Sonnet 5 (subagent) | Full port of `ChefReport`/`ChefSuspension`/`ChefWarning` (`V16`), `ReportController` (`/api/report`, customer+chef, 6 endpoints) + `AdminReportController` (`/api/admin/reports`, 7 endpoints), 11 exceptions (Django's `exceptions/report.py` uses the correct `error_code`, so statuses are the real 409/400/403/429/404) + a validation exception. Gemini severity through the existing text `GeminiClient` (fake in tests); 5/24h rate limit, evidence, completed+owned order, duplicate, weights, WARNING/DISH_LOCK/FULL_LOCK escalation, appeal/lift/reject all ported 1:1. 103 new tests (869/869). 🔴 Django bug fixed by default behind `app.report.preserve-order-uuid-bug`: `ReportSchema.order_id: Optional[int]` but Order pk is a UUID, so every order-linked report 500s (create AFTER saving, and all lists) - see Open questions. Also flagged: suspensions are never enforced anywhere in Django; a REJECTED appeal is a dead end. |
| `chat` (SQL + websocket) | 🚩 | Sonnet 5 (subagent) | Full port: `Conversation`/`Message` (`V17`), `ChatService`, raw (non-envelope) DRF-shaped `ChatController` (`/api/chat/conversations/`, `/api/chat/{room}/messages/`), raw `WebSocketHandler` + JWT query-string `HandshakeInterceptor` (`/ws/chat/{room}/`) with 4001 force-close at token `exp`; 24 new tests (893/893). 🔴 Django never creates a conversation anywhere, so chat can't start: fixed by default via new `POST /api/chat/conversations/` behind `app.chat.preserve-no-conversation-create-bug`. Flagged: no participant check on the websocket, in-memory (single-instance) group registry - see Open questions. |
| `tracking` | 🚩 | Sonnet 5 (subagent) | Full port: `ChefLocation` (`V18`), `LocationService`, `TrackingController` (`/api/tracking`: POST `chef/location`, GET `chefs/nearby`, GET `order/{uuid}/tracking`), raw `TrackingWebSocketHandler` (`/ws/tracking/chef/{id}/`, no auth, in-memory groups) + own `TrackingWebSocketConfig`; badge via `ChefCertificationProvider`. 9 new tests (902/902). 🔴 Django's ws route regex and order-tracking endpoint never work: fixed by default behind 2 flags; see Open questions. |
| `admin` | 🚩 | Sonnet 5 (subagent) | Full port of all 19 routes of `admin/api.py` under `/api/admin` (users list/activate/deactivate, 6 dashboard aggregations, orders list/detail, platform vouchers create/list, verification review, certificate status, chef+customer bank-account list/verify); no tables/migrations (max stays V18). 403 PERMISSION_DENIED for non-admin (not 401), validation before authz like ninja. 46 new tests. Flagged: FE-admin/Django mismatches and quirks - see `admin` open questions. |

**Dropped from scope (decided with the user, 2026-09-22):** `mongo_chat`
(MongoDB + Firebase push chat system) — only `chat` (SQL-backed) is ported.

## End-to-end run with FE-admin (2026-09-25)

First real run: backend-spring (Postgres pgvector + Redis in docker, `local` attachment storage) driven by replaying FE-admin's
`src/services/*.js` calls with node scripts (no browser tool was available; the Vite dev server boots and serves fine). How to run: `DEV.md`.

**Works (response shapes checked against what the components read):** Flyway V1..V18 clean; login/refresh/logout/me/is-chef; expired access token ->
401 `INVALID_OR_EXPIRED_TOKEN` -> `/api/auth/refresh` -> retry 200 (old refresh token is rotated/revoked); CORS preflight incl. PATCH with
`Authorization` from `http://localhost:5173`; forgot-password -> verify-otp -> reset (OTP readable via the `dev` profile); dashboard
(overview, revenue-chart, payment-methods, order-status, top-chefs, success-orders-by-district, `/api/dishes/top`), users list/filter/
activate/deactivate, admin orders list, admin vouchers create/list, `/api/vouchers` patch/delete, certificates lists, dish-locations CRUD +
tree, bank-accounts lists + verify, ingredients CRUD/list/search/autocomplete/alias/export-template, dish create/get/list/search/patch/soft-delete/
restore/ingredients/preview/availabilities, menus create/add-dish/dishes/mine, attachments presign -> PUT upload -> completed, admin reports list.
Not exercised with real data: order detail, dashboard charts with non-empty data, certificate approve/reject, payments, verification review
(need a customer order flow / PayOS / Gemini).

**Mismatches found and fixed in backend-spring** (covered by `common/FeAdminIntegrationFixesTest`, 6 tests):
1. Unknown route / static-resource miss answered 500 `CONTACT_ADMIN_FOR_SUPPORT`; now 404 `NOT_FOUND`; wrong HTTP method now 405 `METHOD_NOT_ALLOWED`.
2. Missing query param, malformed JSON body, bad UUID/number in path answered 500; now 401 `VALIDATION_ERROR` (Django/ninja quirk) with `data.<field>`.
3. Bean-validation error detail keys were Java camelCase (`availableDate`); now snake_case like ninja.
4. `PATCH /api/vouchers/{uid}` rejected FE-admin's naive `datetime-local` dates (`2026-09-01T00:00`) -> 500. New `config/LenientInstantConfig`
   (Jackson module) makes every request-body `Instant` accept naive/offset/date-only like pydantic (naive = UTC).
5. `/actuator/health` was DOWN whenever SMTP is unset: mail health indicator disabled (`management.health.mail.enabled=false`).
6. Startup logged ~100 Spring Data Redis "could not safely identify store assignment" lines: `spring.data.redis.repositories.enabled=false`.
7. Dev only: `AuthEmailService` logs the mail incl. OTP as `[DEV-MAIL]` when the `dev` profile is active (no effect otherwise).

**FE-admin source changed (1 line):** `pages/Dishes.jsx` ~L141 read `dish.uid` from the create response, but `dishService.createDish` returns the whole envelope, so
ingredients were added to `/api/dishes/undefined/ingredients`; now `dish.data?.uid ?? dish.uid`.

**Mismatches left (same as Django, not changed):** `POST /api/certificates/` (FE ChefCertificates create) has no route in Django either -> 405; `/api/admin/voucher/{uid}/status`
(`TOGGLE_STATUS` constant) has no route and is unused by the FE; rule violations thrown as `IllegalArgumentException` (bad dish-location hierarchy, dish location not a COUNTRY)
are 500 not 4xx (Django `ValueError` parity); `GET /api/menus/mine` is 404 `MENU_DOES_NOT_EXIST` for a chef with no menus; access tokens stay valid after logout
(stateless JWT); `/api/auth/refresh` with an expired Bearer header attached is 401 (FE never attaches it); every `ApiException` is logged at ERROR with a stack trace (noisy in dev);
the mail sender bean exists with an empty host, so each register/forgot logs a swallowed `AuthenticationFailedException`. FE: ingredient import uses a relative `fetch`, works only via the Vite proxy.

**Needs real credentials:** PayOS (payments, withdrawals), Gemini (verification, meal parsing, report severity), S3 (uploads outside dev), SMTP (real mail), AI model URL (review classifier).
Infra gotcha: a local Postgres/Redis on 5432/6379 shadows the docker ones, hence compose maps 15432/16379.

## Part 1 findings — the REAL Django authorization model (investigated 2026-09-22)

Investigated before writing any `users` role code, because both the `ingredient`
and `dish` ports had flagged their ADMIN/CHEF gating as a guess. Sources read:
`../backend/users/{models,queries,services,api,schemas,tokens}.py`,
`../backend/utils/permissions/{decorators,roles}.py`, `../backend/utils/enums.py`,
`../backend/users/management/commands/setup_permissions.py`,
`../backend/admin/{permissions,api,queries,schemas}.py`, `../backend/profile/models.py`,
`../auth_permission.json`, plus a tree-wide grep for `groups`, `is_staff`,
`is_superuser`, `has_perm`, `ChefProfile`, `chef_profile`.

**(a) ADMIN is NOT `is_staff`.** ADMIN = membership in the Django auth Group
named `"ADMIN"`. Every single authorization site uses
`request.user.groups.filter(name=UserTypeEnum.ADMIN).exists()`
(`utils/permissions/decorators.py:82,144,185`, `admin/permissions.py:14,27`,
`certificate/services/__init__.py:209`, `profile/services/__init__.py:66`,
`report/api.py:53`). `is_staff` appears in the ENTIRE Django tree in exactly two
authorization lines — `voucher/services/__init__.py:161,197`,
`is_admin = chef.groups.filter(name="ADMIN").exists() or chef.is_staff` — i.e. a
*secondary OR fallback* on top of the group check, plus one read-only output
field in `admin/schemas.py:636`. `is_superuser` is never referenced for
authorization at all (it still matters implicitly: Django's `ModelBackend.has_perm`
returns True for any active superuser). **So the ported `isStaff` → `ROLE_STAFF`
stand-in was wrong**, not merely approximate: it gated on a flag nothing in the
real app grants.

**(b) CHEF is NOT ChefProfile-row existence.** CHEF = membership in the Group
named `"CHEF"`. `ChefProfile` exists (`profile/models.py:99`, `OneToOneField(User,
related_name="chef_profile")`) and is created in the same transaction as the
role change, but no authorization check anywhere reads it — `users/api.py:130`
(`/api/auth/is-chef`) itself uses `request.user.groups.filter(name='CHEF').exists()`.
`users/services.py::upgrade_to_chef` does three things atomically: create the
`ChefProfile`, create `ChefPaymentInfo`, then
`Query.upgrade_customer_to_chef` → **remove** from CUSTOMER group, **add** to CHEF
group. So the role is group membership, owned by `users`, and `profile` owns only
the profile rows. No `profile`-side seam is needed for role resolution.

**(c) CUSTOMER is a real, checked role.** Assigned on user creation
(`users/queries.py::create_user` → `Group.objects.get_or_create(name='CUSTOMER')`)
and re-asserted after SIGNUP OTP verification (`users/services.py::verify_otp`).
Checked in `users/services.py::upgrade_to_chef` ("Only CUSTOMER can upgrade to
CHEF"), in `admin/queries.py:23,61` and `admin/schemas.py:592` (role filters), and
in `utils/permissions/roles.py::get_user_role`. Note that helper's fallback:
CHEF → CHEF, else CUSTOMER → CUSTOMER, **else ADMIN** — a user with no groups at
all reads as ADMIN there (quirk, preserved).

**(d) There IS a fourth mechanism beyond the three roles, and it is live in the
API layer: Django's Permission system.** `utils/permissions/decorators.py`
exposes `require_permission(codename)` and `require_object_permission(codename,
Model, owner_field=...)`, both of which call `request.user.has_perm(...)` for real.
Actual call sites: `dish/api.py` (13×, `dish.change_dish` / `dish.view_dish`) and
`menu/api.py` (9×, `menu.change_menu` / `menu.delete_menu`). Object permission =
`has_perm(codename)` AND (`obj.<owner_field> == user` OR user in ADMIN group).
Group→permission assignment lives in
`users/management/commands/setup_permissions.py`, which hardcodes numeric
permission IDs; `../auth_permission.json` (repo root, 9 KB) is the raw
`auth_permission` table export those IDs index into — **it is loaded by no code,
fixture, script or compose file anywhere in the repo** (only reference is a
line-count entry in `backend/.VSCodeCounter/`). It is an unused data export that
nevertheless documents what the IDs mean; resolving them gives the real matrix:
  - CUSTOMER (group id 1): `view_dishavailability`, `view_dishingredient`,
    `view_dish`, `view_ingredient`, `view_menu`, `view_menudish`,
    `view_chefprofile`, all 4 `customeraddress`, all 4 `customerprofile`,
    `view_cart`, all 4 `cartitem`, `add/change/view_checkout`, `add/view_order`,
    `add/view_orderitem`.
  - CHEF (group id 2): all 4 `attachment`, `dishavailability`, `dishingredient`,
    `dish`, `ingredient`, `menu`, `menudish`, `chefprofile` — plus
    `change_order`, `view_order`.
  - ADMIN (group id 3): all 4 `dish`, `dishingredient`, `ingredient`,
    `dishavailability`, `menu`, `menudish`, `checkout`, `order`.
    (Notably ADMIN does NOT hold the attachment/profile permissions CHEF has —
    faithful, not a transcription slip.)

**Net effect on the two flagged modules:** for `dish.change_dish` the permission
half of `require_object_permission` is satisfiable by CHEF or ADMIN only (a
CUSTOMER lacks it), and for `dish.view_dish` by any of the three roles — so the
practical rule is "CHEF or ADMIN may modify; only the owner or an ADMIN may touch
a specific dish".

**Conclusion / what got built:** roles are ported as a real `app_user_role` join
table owned by `users` (`CustomUser.roles : Set<UserRole>`), mirroring Django's
`auth_user_groups`, not as a `profile`-dependent seam. Permissions are ported as
a static, code-level `RolePermissions` table transcribed from
`setup_permissions.py` + `auth_permission.json`, rather than as Postgres
`auth_permission`/`auth_group_permissions` tables — the mapping is a constant in
Django too (a management command, not runtime-editable data), and nothing in the
API layer mutates it.

## Open questions for the user (do not resolve unilaterally)

### `admin` — open questions (2026-09-25)

- [x] **No Django bug makes an admin flow never work** (every route traced). Closest: `verify_bank_account` looks in the customer table first, then chef, and the two id spaces overlap, so a chef bank id that also exists as a customer bank id verifies the CUSTOMER row through either route (preserved, tested).
- [x] **Seams closed:** `order`'s "(8) FE-admin uses `/api/admin/orders`", `voucher`'s "`admin` creates PLATFORM_* vouchers" and `certificate`'s admin-review hook are all served by `admin.*` (no other module's source edited; own read repositories over their entities; certificate status goes through the `CertificateReviewHook` seam, verification's `@Primary` bean runs).
- [ ] **Authorization = Django's:** `@require_admin` is `PermissionDeniedError` = 403 PERMISSION_DENIED "Only admin can access this endpoint" (checked in `AdminService.requireAdmin`, not `@PreAuthorize`, which the project maps to 401); no token = 401. ADMIN membership alone counts (`is_staff` does not). Ninja validates before the guard, so bad query params / body are 401 VALIDATION_ERROR even for non-admins (params are parsed by hand for that; `AdminExceptionAdvice` maps unreadable bodies to 401 for the admin controller only). NOTE the report module's `/api/admin/reports` uses `@PreAuthorize` (401 for non-admin), so the two differ.
- [ ] **FE-admin parity:** paths/params/fields match Django's schemas (`page_size` default 50, `content/total_rows/total_pages/current_page/page_size`, snake_case, floats for money, Python-isoformat timestamps, `HH:MM:SS` times). Mismatches that are FE-side/Django-side and were NOT fixed: FE constants list `/api/admin/voucher/{uid}` and `/{uid}/status` (no Django route; the services never call them, edit/delete use `/api/vouchers/{uid}`). FE's Vouchers page filters on `discount_type`, which Django's `VoucherDetailSchema` omits — here the admin voucher response ADDS `discount_type` (additive). FE-admin sends naive `datetime-local` strings ("2026-01-01T10:00"): the admin create accepts them as UTC like pydantic/Django (also ISO with zone, bare dates). **Likely gap in `voucher` (not touched):** `PATCH /api/vouchers/{uid}` (FE edit) takes `Instant`, so a naive datetime there is probably rejected — verify against FE before relying on voucher edit.
- [ ] **Quirks preserved:** unknown order id = 403 "Order not found" (not 404); unknown user id on activate/deactivate = HTTP 200 `data:false`; malformed order/certificate uid, `page<1`, `page_size<=0`, negative `limit`, and `revenue-chart` dates (Django `strptime`) = uncaught 500, while `success-orders-by-district` dates are 401 validation and orders/users date filters are silently ignored when malformed; `user_type` other than customer/chef is ignored; empty result = 1 page; a page past the end = empty content. `admin` certificate route ignores soft-deleted certificates (404 "Certificate not found") and needs no rejection reason. Order detail's `delivery_address` is "street, ward, district, city" (no `address` line) from the CURRENT address row, not the order snapshot. `max_discount_amount: 0` is stored as null. Admin-created vouchers get `chef = the admin user`. Revenue = COMPLETED orders with payment SUCCESS/HOLDING/RELEASED by UTC `created_at` day (native SQL `AT TIME ZONE 'UTC'`); payment-method stats count transaction states (`total_orders` there = collected transactions).
- [ ] **Tie order** for equal counts (Django unspecified): status/payment-method by name, top chefs by chef id, districts by name.

### `chat` — open questions (2026-09-24)

- [ ] 🔴 **Chat can never start in Django — fixed by default behind a flag (CLAUDE.md §0.6).** Nothing in the whole Django tree creates a
      `Conversation` (no endpoint, no signal, no service; only the Django admin site `chat/admin.py`), and the websocket `save_message` needs an
      existing conversation, so for real users the list is always empty and every message is silently lost (but still broadcast live). Default here =
      working: new `POST /api/chat/conversations/` `{"chef_id": N}` get-or-creates the caller's conversation (target must be a CHEF and not the caller;
      404/400 raw `{"error"}` otherwise) and returns the list-item shape. `app.chat.preserve-no-conversation-create-bug=true` (env
      `CHAT_PRESERVE_NO_CONVERSATION_CREATE_BUG`) = Django (POST answers 405). Confirm "fix" and whether the customer/chef roles rule is right.
- [ ] **REST responses are RAW (no envelope) — same as Django (DRF views, not ninja).** List = bare JSON array of `{room_id, partner_id, partner_name,
      latest_message, updated_at}`; history = bare array of `{id, sender_id, message, created_at}` newest first (max 50); errors = `{"error": "..."}`
      with real 403/404. Implemented like payment's webhook: controller methods return `void` and write the servlet response, so
      `ResponseEnvelopeAdvice` never sees a body (nothing in `common/` touched). FE-admin has no chat screen (grepped: no chat service/paths).
      Difference: unauthenticated/bad token is Spring Security's standard 401 envelope, not DRF's `{"detail": ...}`.
- [ ] **Authorization quirks preserved (flag):** the websocket has NO participant check — any authenticated user can join any room id and post to it
      (the message is saved under the real sender when the room exists) and everyone in the group receives it; REST history does check participation (403).
      A message to a non-existent room is not saved (error swallowed) but is still broadcast. `Conversation.updated_at` (`auto_now`) is never bumped by
      new messages (Django's consumer never re-saves the conversation), so the list order never reflects activity. `partner_name` is always the
      username (Django's `hasattr(partner, 'fullname')` is always false). A non-numeric `room_id` on the history URL is a 500 (Django ValueError).
      `is_read` exists but nothing ever sets it.
- [ ] **Websocket protocol parity** (`/ws/chat/<room_id>/?token=<jwt>`, room id `\w+`): connect = join group `chat_<room_id>`; client sends
      `{"message": <text>}` (any client `sender_id` ignored); server persists then broadcasts `{"message", "sender_id"}` to every socket in the group,
      sender included. No join/leave frames exist in Django. Bad/missing/expired token, wrong `typ`, unknown user = handshake refused (403; Django
      closes before accept). Invalid JSON ignored; valid JSON without `message` = Django crashes the consumer, here close 1011. At the token's `exp`
      the socket is force-closed with code 4001 (private `ThreadPoolTaskScheduler`, cancelled on disconnect), even when idle.
- [ ] **LIMITATION: in-memory group registry (single instance only).** Django used a Redis channel layer, so fan-out worked across processes; with two
      Spring instances a room's sockets on different instances do not see each other. Not solved (no Redis pub/sub added). `tracking`'s websocket
      (ported, same limitation) has its own registry.

### `report` — open questions (2026-09-24)

- [ ] 🔴 **Every report linked to an order 500s in Django — fixed by default behind a flag (CLAUDE.md §0.6).** `ReportSchema.order_id` is
      `Optional[int]` but `Order`'s primary key is a UUID (`BaseModel.uid`), so pydantic rejects `ReportSchema(**...)` for any report with an
      order: `POST /api/report` for every order-required category (FOOD_SAFETY/FOOD_QUALITY/HYGIENE/WRONG_ITEM/MISSING_ITEM/PAYMENT_ISSUE/
      REFUND_ISSUE — i.e. the whole customer flow) saves the report + runs Gemini + escalation and THEN answers 500; `GET /api/report/my-reports`,
      `GET /api/report` (chef) and the admin list 500 as soon as one row has an order; dismiss/confirm 500 too. Same root cause: the FINANCIAL
      handler puts `report.order_id` (a UUID) into the `metrics_snapshot` JSONField → `TypeError` → 500 for PAYMENT_ISSUE/REFUND_ISSUE.
      Default here = working (`order_id` = the order UUID string; snapshot stores the string); `app.report.preserve-order-uuid-bug=true`
      (env `REPORT_PRESERVE_ORDER_UUID_BUG`) = Django (regression-tested in `ReportOrderUuidBugPreservedTest`). Confirm "fix". FE contract note:
      `order_id` is now a UUID string, not an int.
- [ ] 🔴 **Suspensions are informational only — nothing in Django enforces them** (grepped the whole backend: `Dish.is_suspended`,
      `ChefProfile.is_accepting_orders` and `suspension_level` are written only by `report` and read by nobody except as output fields). A
      FULL_LOCK'd chef can still appear in search and receive orders; a DISH_LOCK'd dish can still be ordered. No seam existed in another module,
      so none was closed and no other module's source was touched. Enforcing it (hide suspended dishes / refuse orders for `!is_accepting_orders`)
      is a product decision touching `dish` + `order` — say the word.
- [ ] **A rejected appeal is a dead end (preserved).** `reject_appeal` sets `REJECTED` although its docstring says "keep ACTIVE"; `lift` only
      accepts ACTIVE/APPEALING and `get_active_suspension` only ACTIVE/APPEALING, so after a rejection the FULL_LOCK can never be lifted, the chef's
      `is_accepting_orders` stays false, and `/suspension/current` shows `active_suspension: null`. Regression-tested
      (`admin_manualSuspension_fullLock_thenAppealRejected_isADeadEnd`). Also `AppealAlreadySubmitted` is unreachable (an appealed suspension is
      APPEALING, and the status check comes first).
- [ ] **FULL_LOCK closes older DISH_LOCKs with a bulk update that does not clear `Dish.is_suspended`** (preserved): those dishes stay flagged
      forever, since their suspension is already LIFTED and lifting the FULL_LOCK does not touch dishes.
- [ ] **Gemini** — reuses recommendation's text `GeminiClient` + `app.gemini.*` (model `gemini-2.5-flash-lite`, env `GEMINI_API_KEY`). Mirrors
      Django's never-raises contract: empty key / error / null / non-JSON / non-object → severity LOW, food_safety_risk false, confidence low, reason
      "Không thể phân tích tự động." (the report is still created, weight unchanged); only per-minute 429 is retried (3 attempts, 1 s/2 s). Only
      food-quality categories (FOOD_SAFETY/FOOD_QUALITY/HYGIENE) call Gemini. `report/services/local_severity.py` is dead code (not imported) — not ported.
- [ ] **Escalation numbers need production-sized data**: chef metrics need >= 50 completed orders in 30 days (delivery: 20); FULL_LOCK needs >= 10
      reports from >= 5 reporters and combined ratio >= 8%; DISH_LOCK >= 30 dish orders, >= 3 reports, ratio >= 5%; WARNING at 4% (deduped 24 h).
      Constants ported verbatim.
- [ ] **Quirks preserved:** ownership/COMPLETED only enforced for order-required categories (a platform report may carry anyone's order); `chef_id`
      in the body overrides the order's chef; a platform report without order/dish is unique per reporter (the duplicate check treats NULL = NULL,
      so a second one is 409); an unknown `evidence_uid` is silently dropped (then EVIDENCE_REQUIRED for platform categories); `manual_suspension`
      with an unknown dish/chef is an uncaught 500; `/suspension/current` for a chef without a `ChefProfile` is a 500; role checks are the
      project-wide `hasRole` → 401 (ADMIN-only users cannot file reports: customer-or-chef); the emails swallow every failure so
      `ChefWarning.email_sent` is always true after the send attempt; validation failures are the project-wide 401 VALIDATION_ERROR;
      `ADMIN_ALERT_EMAIL` does not exist in Django settings (admin alert never sent there) — here `app.report.admin-alert-email` (env
      `ADMIN_ALERT_EMAIL`, blank by default). Emails are plain text (subjects verbatim), like the other modules.
- [ ] **Transactions (§8b):** `ReportService` is deliberately not `@Transactional` — `create_report` commits before Gemini/analysis/emails
      (Django), each write is its own transaction in `ReportCommandService`; on_commit emails run after the commit.

### `verification` — open questions (2026-09-24)

- [ ] 🔴/ℹ **No Django bug makes KYC "never work"** - the flow was traced end to end (analyze -> confirm -> cross-validate -> code -> selfie ->
      decision -> finalize) and is internally consistent; no preserve-flag was needed. Things that come close, all preserved:
      (a) **a REJECTED (or any COMPLETED) session can never be redone** - `analyze_selfie` raises 409 VERIFICATION_ALREADY_COMPLETED forever and no
      endpoint resets a session, so a rejected chef has no retry path (admin/DB intervention only). (b) The documents can be re-analyzed after
      completion (no status guard) but that changes nothing that matters. (c) `cross-validate` failure does NOT block the selfie step
      (Django never checks `cross_validation_passed`/`AWAITING_SELFIE`); the mismatches just feed the risk score. Say the word to add guards.
- [ ] **Face matching (InsightFace `buffalo_l`) is not ported** - no faithful Java equivalent. `FaceMatcher` seam; default
      `UnavailableFaceMatcher` returns empty = exactly Django's outcome when insightface is missing or finds no face:
      `FACE_NOT_DETECTED` flag (+50 risk), decision PENDING_REVIEW (never auto-rejected on its own). So in production every session goes to
      manual admin review until a real matcher bean is supplied (`@Primary FaceMatcher`, e.g. an ONNX/remote service). Same for
      the **CCCD QR scan** (`CccdQrScanner`, default no-op -> `qr_verified=false`, the QR-vs-OCR mismatch rejection cannot fire).
      `paddleocr.py` is unused by Django's flow (never imported) and was not ported.
- [ ] **Gemini failure modes mirror Django**: empty `GEMINI_API_KEY`, API error, null/invalid/non-object JSON => uncaught => HTTP 500
      CONTACT_ADMIN_FOR_SUPPORT on every analyze endpoint (only per-minute 429 is retried, 3 attempts, 1 s / 2 s). `verify_same_address`
      is fail-safe (any failure or "low" confidence => addresses treated as matching). Config reuses `app.gemini.*` (env `GEMINI_API_KEY`).
- [ ] **Images are fetched over HTTP from `Attachment.public_url`** (like Django's `requests.get`), not through `AttachmentStorageService`
      (which has no read method and is outside this task's edit scope). With `app.storage.backend=local` the local `/media` URL is used.
- [ ] **S3 deletion is gated by the storage backend** (Django `USE_S3`): backend `s3` (default) -> real `DeleteObject`
      (`AwsS3ObjectDeleter`); backend `local` -> `DisabledS3ObjectDeleter`, every delete/schedule is skipped. Django's cron-run
      management command `delete_scheduled_s3` is a daily `@Scheduled` (`app.verification.s3-deletion-cron`, default `0 0 3 * * *`,
      disable with `app.verification.s3-deletion-enabled=false`); `S3DeletionService.executePending(dryRun)` is the callable equivalent.
- [ ] **Transactions**: `VerificationService` is deliberately NOT `@Transactional` (CLAUDE.md 8b): Django autocommit semantics need
      persist-then-raise (failed cross-validation saves its errors then 422; `get_or_create` session survives AttachmentNotFound).
      Finalize steps run as separate transactions with Django's try/except-and-log isolation.
- [ ] Faithful minor quirks: `DocumentNotCompleted`/`SelfieCodeNotGenerated` exist but are never raised (a missing/expired code is
      `SELFIE_CODE_EXPIRED`); validation errors on the request bodies are the project-wide 401 VALIDATION_ERROR; role failures are 401
      (`hasRole('CHEF')`; ADMIN is not CHEF). The `/status` `selfie_url` only exists while PENDING_REVIEW and refs are not yet cleared by the hook.

### `certificate` — open questions (2026-09-24)

- [ ] 🔴 **`is_food_safety_certified` is ALWAYS false in Django — fixed by default behind a flag (CLAUDE.md §0.6).** Django's
      profile (`resolve_is_food_safety_certified`, `profile/orm/profile.py`) and tracking filter certificates by
      `status="APPROVED"` (ORM even uses `certificate_type="SAFETY_TYPE"`), but `CertificateStatusEnum` = PENDING/ACTIVE/EXPIRED/REVOKED
      and `CertificateTypeEnum` = FOOD_SAFETY/BUSINESS_LICENSE, so an admin-approved (ACTIVE) certificate never lights the badge.
      Default here = working (FOOD_SAFETY + ACTIVE); `app.certificate.preserve-approved-status-bug=true`
      (env `CERTIFICATE_PRESERVE_APPROVED_STATUS_BUG`) = Django. Like Django, `deleted` and `expiration_date` are not checked. Confirm "fix".
      `tracking` (not ported) should reuse `CertificateRepository.existsByOwnerIdAndCertificateTypeAndStatus`.
- [ ] **No create/update/add-attachment/remove-attachment HTTP routes (Django removed them on purpose — certificates come from the
      `verification` flow).** FE-admin's `certificateService.js` still calls them (they 404 in Django too). Ported as service methods only
      (`createCertificate`, `updateCertificate`, `addAttachments`, `removeAttachment`) for the `verification` port.
- [ ] **Faithful quirks (PORT-NOTE'd, tested):** admin can set ANY status (no transition rules; PENDING/EXPIRED allowed);
      `rejection_reason` only overwritten when non-empty, never cleared on approve; a certificate with a NULL owner skips the ownership check
      on GET `/{uid}`; `reorder` deletes ALL links and re-creates only the listed ones with position = list index (the request `position` is
      ignored, unlisted attachments are dropped); `restored` on a non-deleted certificate returns 200 `data:false`; `restore` is gated by
      ADMIN so the owner branch is unreachable; only PENDING certificates can be soft-deleted. List order is newest-first (Django's is unordered).
- [x] **CLOSED 2026-09-24 (`verification` port)** - `CertificateReviewHook` is now implemented by
      `verification.service.VerificationCertificateReviewHook` (`@Primary` over the no-op; certificate source untouched): when an admin's
      review leaves no PENDING certificate on the session, CCCD + selfie are scheduled for S3 deletion in 30 days and the session's refs cleared.
      Proven end to end in `VerificationControllerTest.fullKyc_pendingReview_...`.

### `recommendation` — open questions (2026-09-24)

- [ ] 🔴 **Gemini recipe generator (parse-meal tier 4) NEVER works in Django — fixed by default behind a
      flag (CLAUDE.md §0.6).** `_call_gemini_recipe_generator` calls
      `client.models.generate_content(..., timeout=3.0)`; the installed google-genai 1.74.0 has no
      `timeout` kwarg (verified in backend/venv: `TypeError: Models.generate_content() got an unexpected
      keyword argument 'timeout'`), the bare `except` returns None, so every meal reaching that tier is
      `unresolved_meals`. Port default (`app.gemini.preserve-recipe-timeout-bug=false`,
      env `GEMINI_PRESERVE_RECIPE_TIMEOUT_BUG`) = the intended behavior (Gemini with a 3 s timeout, snapshot
      stored); `true` = Django's always-None. Confirm "fix". NOTE: in practice this tier is still almost
      unreachable because of the next item.
- [x] **RESOLVED 2026-09-24 (user decision: enable 0.6) — meal→dish matching threshold.** `DailyNutritionService.matchDish`
      now accepts the top dish-search hit only if its own `search_score` (the weighted fuzzy/exact + rating +
      popularity score `DishSearchService` already computes and returns — no new metric, dish search untouched)
      is >= `app.recommendation.dish-match-threshold` (env `RECOMMENDATION_DISH_MATCH_THRESHOLD`, default 0.6);
      below it, falls through to tiers 2-4. Django's behavior: `app.recommendation.preserve-dish-match-no-threshold`
      (env `RECOMMENDATION_PRESERVE_DISH_MATCH_NO_THRESHOLD`, default false). Note the real similarity of short
      unrelated names is high ("bun bo" vs "banh bao" raw ratio 0.714), so gating on the weighted score (0.8 x
      ratio for fuzzy names + rating boost) is what makes that case fall through (0.571). Tests:
      `DailyNutritionServiceTest.matchDish_gatesOnSearchScore_...` + updated heuristic-parser test.
      Original text: parse-meal tiers 2-4 (translation mapping, USDA ingredient, Gemini recipe) were unreachable whenever
      ANY live dish exists — preserved, NOT fixed.** `_match_dish` takes the top result of
      `DishSearchService.search(limit=1)`, and dish search's `FUZZY_THRESHOLD` is dead code (already flagged
      in `dish` item 4) — every live dish is a candidate, so the best fuzzy dish, however unrelated
      ("cà phê sữa" → some "Phở bò"), is ALWAYS returned and its nutrition logged. Fixing needs a threshold
      decision (NUTRITION_API.md says 0.6; dish's dead constant says 0.1). Tests exercise tiers 2-4 by
      soft-deleting every dish inside a rolled-back transaction.
- [ ] **`GET /api/recommendation/users/{id}/profile` and `/users/{id}/features` are open to ANY authenticated
      user (preserved).** Django puts `@require_admin` ABOVE `@get(...)`; ninja-extra's route keeps the
      undecorated function as `view_func` (verified in venv), so the admin check never runs. Say the word to
      add `hasRole('ADMIN')`.
- [ ] **`exceptions/recommendation.py` sets `status_code` instead of `error_code`** (same bug class as
      `review`): `PreferencesNotFoundException` / `DailyNutritionProfileNotFoundException` are really 500
      (message_code kept). `/me/features` for a user who never touched a preference = 500 PREFERENCES_DOES_NOT_EXIST.
- [ ] **Faithful numeric/semantic quirks (all PORT-NOTE'd):** scoring-time dish vector uses sodium in mg with
      no confidence weighting while the persisted pgvector vectors use grams + weight×confidence, and the
      EMA mixes both scales; `float(x or 1.0)` turns 0 weight/confidence/quantity into 1; one order item
      whose dish was deleted makes the whole history vector fail (→ global mean); `use_pgvector_ann_candidates`
      only relabels the quota; the persisted user vector is only refreshed by signals (1-min cooldown) and a
      DishIngredient edit within 5 min of the last dish refresh is skipped; `find_better_dish_for_issue`
      matches issues by raw substring, not the whitelist; `prioritize_unordered_dishes` has no setting (always
      false). `update/delete` of a missing meal log = Django `ValueError` → 500.
- [x] **RESOLVED 2026-09-24 — dish's `pyRound` now exact Python `round`.** `PyMath` moved to
      `common.util.PyMath`; `DishNutritionService.pyRound` delegates to `PyMath.round` (signature unchanged, so
      DishService/NutritionValidation/PortionWeightOutOfBoundsException untouched). No existing dish test value
      changed (0.125/0.135/41.5/42.5 verified vs real Python: 0.12/0.14/42.0/42.0); added regression
      2.675→2.67, 0.0005→0.001 (Python-confirmed). `PythonParityTest` now asserts dish == PyMath.
      Original text: **dish's `DishNutritionService.pyRound` is not Python's `round`** (rounds the shortest decimal string,
      not the exact binary value: `round(2.675, 2)` → Python 2.67, pyRound 2.68; `round(0.0005, 3)` → 0.001
      vs 0.0). recommendation uses its own exact `PyMath.round`; dish left untouched — fix in a dish pass.
- [x] **Closed: seams other ports left open.** profile's "recommendation-owned onboarding side effects
      omitted" (item 6 above) and order's "`sync_order_meal_logs` hook + vector-refresh signal omitted" are now
      implemented from the recommendation side only (AOP + Hibernate listeners); no other module's source
      was edited. OLLAMA_* settings are referenced only by commented-out Django code — not ported.
- [ ] `order/checkout.delivery_time` is written through Hibernate's `jdbc.time_zone: UTC`, so the raw column is
      JVM-zone-shifted; recommendation reads it through the Checkout entity (correct), but any future raw-SQL
      reader must do the same.


- [ ] **`cart` — `toggle_select` has no ownership check at all in Django**
      (`cart/api.py::toggle_select` receives `request.user` but never passes
      it to `CartService.toggle_select(cart_item_uid)`, which only takes the
      item's uid). Any authenticated user who knows/guesses a
      `cart_item_uid` can toggle another user's cart item — a real,
      IDOR-shaped gap in Django itself, not introduced by this port. Every
      sibling endpoint (`set_quantity`/`remove_item`) IS properly scoped
      (resolved through `CartORM.get_cart_by_user(user)` first). Ported
      faithfully, regression-tested
      (`CartControllerTest.toggleSelect_anyAuthenticatedUser_canToggleAnotherUsersCartItem`).
      Say the word if you want this port to add an ownership check Django
      itself doesn't have.
- [ ] **`cart` — `CartNotFoundException` is dead code in Django** (same class
      of bug as `ingredient`'s `restore_ingredient`/`menu`'s `restore_menu`):
      `CartService.get_cart_by_user` raises it only when
      `CartORM.get_cart_by_user` returns falsy, but that method is
      `Cart.objects.get_or_create(owner=user)`, which never returns `None`.
      Confirmed unreachable via Django's own coverage report (the `raise`
      line is marked "missed"). Ported faithfully + regression-tested to
      document the branch can never fire through the real code path — low
      stakes, no action needed unless you want the dead branch removed.
- [ ] **`cart` — two genuinely-reachable Django crashes (uncaught 500,
      `CONTACT_ADMIN_FOR_SUPPORT`) preserved verbatim, not fixed**:
      (1) `add_item`/`set_quantity` never null-check
      `dish_orm.get_dish_by_uid`'s result before referencing `dish.name` —
      a missing/deleted `dish_uid` crashes with Python `AttributeError`
      before the ninja layer ever gets a chance to 404. Ported as an
      equally-uncaught `NullPointerException` (`CartService.addItem`/
      `setQuantity`, both PORT-NOTE'd), same 500 outcome either way; (2)
      `toggle_select` does the same for a nonexistent `cart_item_uid`
      (`get_cart_item_by_uid` returns `None`, then `cart_item.is_selected`
      is read uncaught). Both regression-tested
      (`addItem_nonexistentDish_isServerError`,
      `toggleSelect_nonexistentUid_isServerError` in
      `CartControllerTest`, plus the Mockito equivalents in
      `CartServiceTest`). Say the word if any of these three should become
      a proper `CartNotFoundException`/`CartItemNotFoundException` 404
      instead — `CartItemNotFoundException` already exists in
      `cart/exception/` (ported from `exceptions/carts.py` since `order`'s
      future checkout flow needs it) and would be the natural fit if you
      want it fixed rather than bug-compatible.
- [ ] **`cart` — `quantity_to_add or 1` falsy-zero quirk preserved.** Django's
      ninja schema declares `quantity_to_add: int` as required with no
      default, yet `CartService.add_item` does
      `quantity_to_add = payload.quantity_to_add or 1` — so a client that
      sends the JSON literal `0` silently gets **1** item added instead of 0
      (Python's `0 or 1` evaluates to `1`). Ported verbatim in
      `CartService.addItem`, regression-tested
      (`addItem_zeroQuantityToAdd_isTreatedAsOne`/`addItem_zeroQuantityToAdd_addsOneAnyway`).
      Low stakes (FE-admin has no cart UI to trigger this from anyway — see
      the cart port's session-log entry), flagging for completeness.
### `tracking` — open questions (2026-09-24)

- [ ] 🔴 **Django's tracking websocket can never connect — fixed by default behind a flag (CLAUDE.md 0.6).** `mongo_chat/routing.py` registers
      `re_path('ws/tracking/chef/<int:chef_id>/', ...)`: a path-converter string used as a regex (and no named group, so `scope['url_route']['kwargs']['chef_id']`
      would KeyError anyway). Default here = working route `/ws/tracking/chef/{chef_id}/` (numeric id). `app.tracking.preserve-ws-route-bug=true`
      (env `TRACKING_PRESERVE_WS_ROUTE_BUG`) registers nothing (404 like Django). Confirm "fix".
- [ ] 🔴 **`GET /api/tracking/order/{order_id}/tracking` always 500s in Django** (`from tracking.models import Order` = ImportError; also `order_id: int` vs UUID pks and
      non-existent `order.delivery_lat/lng`). Default here = evident intent: order owned by the caller, chef's stored location, `delivery_latitude/longitude`
      as destination, `order_id` string UUID; missing order / not the owner = 404 `ORDER_NOT_FOUND`, chef with no location = 404 `CHEF_LOCATION_NOT_FOUND`.
      `app.tracking.preserve-order-tracking-bug=true` (env `TRACKING_PRESERVE_ORDER_TRACKING_BUG`) = permanent 500.
- [x] **`TrackingConsumer` has zero auth — CLOSED as "port as-is" 2026-09-24.** Actual behavior: anyone (no token, no login) can open the socket for any chef_id and
      receive that chef's live location; client frames are ignored. Reproduced exactly (PORT-NOTE in `TrackingWebSocketHandler`). Still a privacy gap if you want it
      restricted (e.g. only customers with an active order from that chef) - say so and it becomes a small follow-up.
- [ ] **Protocol parity**: server-push only. Chef POSTs location (CHEF role, `{latitude, longitude, heading?}`, upsert into `chef_locations`, heading only overwritten
      when sent); after commit the group `tracking_chef_<id>` gets `{"action":"location_update","data":{"latitude","longitude","heading","chef_id"}}` (`heading` = the
      request's, null when omitted). In-memory registry, single instance only (same limitation as chat).
- [ ] **`nearby` quirks**: `avg_rating` is ALWAYS 0.0 in Django (`getattr(profile, "avg_rating", 0.0)`, the model field is `rating`) - preserved by default;
      `app.tracking.preserve-zero-avg-rating-bug=false` (env `TRACKING_PRESERVE_ZERO_AVG_RATING_BUG`) returns `ChefProfile.rating`. Badge = certificate module's
      `ChefCertificationProvider` (FOOD_SAFETY + ACTIVE, honours `app.certificate.preserve-approved-status-bug`). Haversine's `acos` arg is clamped to [-1,1] (Django's raw SQL
      errors at distance 0); top 10, radius default 5 km. Missing lat/lng = 401 VALIDATION_ERROR. Django's unmounted DRF `tracking/views.py` is not ported.
- [ ] **`menu` — `restore_menu` is dead code, the same class of bug as
      `ingredient`'s `restore_ingredient` (preserved as-is, not fixed).**
      `dish`'s `restore_dish` decorator explicitly passes `check_deleted=False`;
      `menu`'s `restore_menu` decorator
      (`@require_object_permission('menu.change_menu', Menu, owner_field='chef')`)
      does NOT, so it keeps the default `check_deleted=True` and the
      authorization pre-check's own object lookup filters `deleted=False` —
      404-ing (`MenuDoesNotExist`) a genuinely soft-deleted menu before the
      "is it actually deleted" logic is ever reached. For a menu that is NOT
      deleted, that lookup succeeds and the very next check
      (`if not menu.deleted: raise MenuIsNotDeleted`) always fires instead. Net
      effect: `PUT /api/menus/{uid}/restore` can never actually restore
      anything, in either direction. Ported faithfully in
      `MenuService.restoreMenu`, unit- and controller-tested for both dead
      ends. Say the word if you want this fixed (the fix mirrors `dish`'s: pass
      an include-deleted lookup for this one endpoint's authorization step).
- [ ] **`menu` — soft-deleting a menu does not hide it from the two customer-facing
      read endpoints (`GET /api/menus/{uid}` and `GET /api/menus/{uid}/dishes`),
      only from the chef's own `/mine` list and the public `/chef/{id}` list.**
      Django's `get_menu`/`get_all_dishes_in_menu` resolve the menu via
      `get_menu_by_uid`, which never filters `deleted` — only
      `status == ACTIVE` gates visibility — while `get_all_my_menus` and
      `get_all_menus_of_chef` both explicitly filter `deleted=False` in their
      own queries. So a chef can soft-delete a menu, see it vanish from their
      own dashboard, while it (and its active dishes) remain fully visible to
      any customer who already has the link or hits the detail/dishes
      endpoints directly. Preserved verbatim, regression-tested
      (`MenuServiceTest.getMenu_softDeletedButStillActive_isStillVisible`,
      `MenuControllerTest.getMenu_draftIs404_activeIsVisible_andRemainsVisibleAfterSoftDelete`).
      Say the word if this should actually filter `deleted` too.
- [ ] **`menu` — `add_dish_to_menu` is the one object-level menu endpoint where an
      ADMIN who does not own the menu CAN succeed**, unlike every sibling
      endpoint (`update`/`soft-delete`/`restore`/`activate`/`deactivate`/
      `activate-dish`/`deactivate-dish`), where the Django service method
      redundantly re-checks ownership with a STRICT, non-ADMIN-bypassing
      comparison (`if user.id != menu.chef.id: raise PermissionDenied`) even
      though the controller decorator already allowed owner-or-ADMIN through.
      `add_dish_to_menu`'s service method is the only one that takes no `user`
      parameter at all, so it never performs that redundant re-check — the
      decorator's owner-or-ADMIN gate is the only one that ever applies. Net
      effect: for every other object-level menu action, ADMIN membership alone
      is NOT sufficient to act on a menu you don't own (a real, and notably
      stricter-than-`dish`, behavior — `dish`'s equivalent check does let
      ADMIN bypass ownership everywhere). Only `add_dish_to_menu` lets ADMIN
      through regardless of ownership. Confirmed this is what Django's actual
      code does (not a porting slip) and ported faithfully; both branches are
      tested in `MenuServiceTest`/`MenuControllerTest`. Flagging because it is
      an easy thing to "fix" into consistency by accident later — don't,
      unless asked.
- [ ] **`menu` — two Django ORM lookups have no `deleted=False` filter on the
      DISH side either**, so a soft-deleted dish can technically be added to a
      menu (`add_dish_to_menu`) or have its active flag toggled
      (`activate_dish_in_menu`/`deactivate_dish_in_menu`); only the chef-facing
      `get_all_dishes_in_menu_for_chef` listing excludes soft-deleted dishes
      (Django's own `.exclude(dish__deleted=True)`). Preserved verbatim, not
      independently flagged as a fix candidate (low-stakes, matches Django).
- [ ] **`menu` — `activate_dish_in_menu`/`deactivate_dish_in_menu` crash to an
      unhandled 500 if the dish was never actually linked to the menu.**
      Django's `MenuORM.activate_dish_in_menu`/`deactivate_dish_in_menu` do
      `MenuDish.objects.get(menu=menu, dish=dish)` uncaught — no defined
      exception code exists for "this dish isn't in this menu" on these two
      endpoints. Ported by letting `Optional.orElseThrow()` raise a plain,
      non-`ApiException` exception, which `GlobalExceptionHandler`'s catch-all
      maps to the same 500 `CONTACT_ADMIN_FOR_SUPPORT` outcome Django's generic
      handler would produce. Tested
      (`MenuControllerTest.activateAndDeactivateDishInMenu_roundTrip_andUnlinkedDishIsAServerError`).
- [ ] **`ingredient` — Django's `restore_ingredient` endpoint is dead code (a real
      bug, preserved as-is, not fixed).** Traced the call chain: `IngredientCommandService.restore_ingredient`
      looks the ingredient up via `IngredientQueryService.get_by_uid`, which filters
      `deleted=False` — so a genuinely soft-deleted ingredient (the only case restore
      is for) always 404s as `IngredientDoesNotExist` before Django's own
      `if not ingredient.deleted: raise IngredientIsNotDeleted` check is ever reached;
      a non-deleted ingredient (the only kind that lookup CAN find) always fails that
      same check instead. Net effect: `PUT /api/ingredients/{uid}/restored` can never
      actually succeed in Django today, and this port replicates that exactly
      (`IngredientService.restoreIngredient`, unit-tested to confirm both dead-end
      paths). Say the word if you want this port to actually fix it (the fix is
      looking the ingredient up WITHOUT the deleted=false filter) instead of staying
      bug-compatible.
- [x] **RESOLVED 2026-09-22 — `ingredient` ADMIN/CHEF role checks are no longer a
      stand-in.** The original flag assumed Django's RBAC was unported and used
      `CustomUser.isStaff` → `ROLE_STAFF` for ADMIN, leaving CHEF endpoints open to any
      authenticated user. The `users` second pass investigated the real model (see
      "Part 1 findings" above): roles are Django auth **Group membership**, and
      `is_staff` is used for authorization in exactly two lines of the whole Django
      tree (an OR fallback in `voucher/services`) — so the stand-in was gating on a flag
      nothing actually grants. `IngredientController` now uses `hasRole('ADMIN')` on the
      13 `@require_group(ADMIN)` endpoints and `hasRole('CHEF')` on the 6
      `@require_group(CHEF)` ones (`/chef`, `/search`, `/autocomplete`,
      `/suggestions/me`, `/suggestions/{uid}/deleted`, and this port's own
      `POST /suggestions`). `IngredientControllerTest`'s fixtures were updated from the
      `isStaff` flip to real role assignment, and `RoleAuthorizationControllerTest`
      asserts the full matrix including the previously-open CHEF endpoints now
      rejecting a CUSTOMER *and* an ADMIN (they are different groups in Django).
      The 401-vs-403 note still stands: `GlobalExceptionHandler` maps
      `AccessDeniedException` to 401 per CLAUDE.md §4, unchanged.
- [ ] **`ingredient` — `IngredientSuggestion` approve/reject flows are simplified
      because `dish` isn't ported yet.** In Django, a suggestion is created by the
      `dish` app (a chef typing a custom ingredient name while building a dish), and
      `approve_new`/`approve_alias` pull nutrition data from the originating
      `DishIngredient` row, then push the resolution back onto every `DishIngredient`
      referencing that suggestion. Since `dish` doesn't exist in this codebase yet
      (ingredient ports first, per CLAUDE.md §7's recommended order): (1) added
      `POST /api/ingredients/suggestions` directly on this controller — no Django
      equivalent — so the moderation queue is creatable/testable now (chefs otherwise
      have no way to create a suggestion before `dish` exists); (2) `approve-new`
      creates a bare `Ingredient` from the suggestion's name/category only (no
      nutrition data — nothing to scale from), skipping Django's
      `DishIngredientNotFoundException` requirement entirely; (3) `approve-alias`
      and `reject` do the full ingredient/alias-side resolution but skip the
      `DishIngredient` sync loop (nothing to sync against yet). All three are
      PORT-NOTE'd in `IngredientSuggestionService`'s class javadoc. Revisit once
      `dish` is ported — likely wants a coordinated pass touching both modules.
- [x] **RESOLVED 2026-09-22 — 🔴 CROSS-MODULE BUG found while porting `dish` —
      the response envelope (and the `users` module's DTOs) were camelCase,
      but Django and FE-admin both use snake_case throughout.** Root cause:
      `common/response/ApiResponse.java` and `users/dto/TokenPairResponse.java`
      etc. were plain Java records with no naming override, so Jackson's
      default (camelCase, matching the Java field names) leaked into the
      wire format — `{"messageCode":..., "errorCode":..., "currentTime":...,
      "accessToken":..., "refreshToken":...}` instead of the real Django/
      FE-admin contract. `attachment`/`ingredient`/`dish`'s own payload DTOs
      already used explicit `@JsonProperty` snake_case (per-field), so only
      the shared envelope + `users` DTOs were actually broken — but that's
      still every single response from every endpoint.
      **Fix applied**: `spring.jackson.property-naming-strategy: SNAKE_CASE`
      in `application.yml`, applied globally — converts every DTO/record's
      normal camelCase Java fields to snake_case JSON automatically, both
      directions (request deserialization too, e.g. `phone_number`,
      `refresh_token`). Existing per-field `@JsonProperty` annotations in
      `attachment`/`ingredient` are now redundant but harmless (explicit
      annotation wins over the strategy — no conflict). Updated all test
      assertions across `users`/`attachment`/`dish`/`ingredient` that had
      been (wrongly) asserting camelCase keys, including `DishControllerTest`'s
      request bodies (`"phoneNumber"` → `"phone_number"`) and token-extraction
      helpers (`"accessToken"` → `"access_token"`) in every controller test —
      several of these "passed" before only because they matched this port's
      own wrong output, not FE-admin's actual expectation. Full suite:
      **124/124 passing** after the fix. **New modules going forward do not
      need per-field `@JsonProperty` for snake_case — it's automatic now;
      only use `@JsonProperty` for a name that doesn't mechanically map from
      camelCase (e.g. an acronym).**
- [x] **RESOLVED 2026-09-23 (`order` port) — the FK now exists**: `V10__init_order.sql`
      runs exactly V4's `ALTER TABLE stock_reservation ADD CONSTRAINT
      fk_stock_reservation_order_item FOREIGN KEY (order_item_id) REFERENCES order_item (id)
      ON DELETE CASCADE`. Consequence: `dish`'s own `StockReservationServiceTest` used
      synthetic item ids, so its `nextItemId()` helper (test-only, one method) now inserts a
      real `order_item` row under a shared parent order — no assertion changed, all 15 still
      green. `stock_reservation.order_uid` stays a denormalized plain column (only
      `OrderService` writes it, always with the item's real parent). Original note: *was a
      UNIQUE column, not yet a real
      FK**, because `order` is not ported (Django has
      `OneToOneField("order.OrderItem", on_delete=CASCADE)`). The semantics the
      reservation logic depends on (at most one ledger row per order item, ever) ARE
      enforced. The `order` port must add the FK in its own migration — the exact
      `ALTER TABLE` is written out in `V4__init_dish.sql`'s comment block. Same for
      `stock_reservation.order_uid`, which this port denormalizes (Django reaches it
      via `reservation.order_item.order_id`) so the expiry sweep can return touched
      order uids standalone. **Confirm this is acceptable before `order` lands.**
- [x] **RESOLVED 2026-09-23 (`order` port) — steps 2 and 3 are implemented** by
      `order.service.OrderExpiredOrderHandler` (`@Primary` over `LoggingExpiredOrderHandler`):
      per touched order, in its own `REQUIRES_NEW` transaction with a `SELECT … FOR UPDATE`
      row lock (`ExpiredOrderCanceller`), DRAFT/PENDING → CANCELLED and its RESERVED/USED
      `AppliedVoucher` rows → EXPIRED; anything already CONFIRMED_SYSTEM+ is left alone; then
      `send_order_notification_task("order-expired:{uid}", CANCELLED_EXPIRED)` after that
      order's commit. Tested end to end through the real `StockHoldSweepScheduler.sweepOnce()`
      in `OrderExpirySweepTest` (incl. sweep-loses-race-to-confirm and already-paid cases).
      Original note: *steps 2 and 3 of the Celery task were not implemented** (they are
      `order`/`voucher` work, neither ported). Django's
      `dish/tasks.py::release_expired_stock_holds` (1) releases the held stock,
      (2) cancels DRAFT/PENDING orders + expires their `AppliedVoucher` rows, and
      (3) enqueues a `CANCELLED_EXPIRED` notification. **Step 1 is fully implemented
      and tested** (the inventory-correctness half). Steps 2-3 go through the
      `ExpiredOrderHandler` interface; the default `LoggingExpiredOrderHandler` only
      logs the order uids. Interval confirmed at **30s** from
      `marketplace/celery.py::beat_schedule` and asserted in
      `StockHoldSweepSchedulerTest`.
- [x] **RESOLVED 2026-09-23 (`profile` port) — the `DishUserContext` half of
      `dish`'s seam pair is now real.** `ProfileDishUserContext`
      (`profile/service/`) reads `CustomerProfile.allergy_mode` and
      `CustomerFavoriteDish` for real and is registered `@Primary` over
      `dish`'s `NoProfileDishUserContext` default, so allergy HIDE mode and
      `is_favorite` are now reachable in practice, not just implemented. Kept
      read-only on purpose (no `get_or_create` side effect from a dish read) —
      see `ProfileDishUserContext`'s javadoc for why, and the still-open item
      below about `users`' `verify_otp` not creating the profile row.
      **UPDATE 2026-09-23 (`order` port): `sold_count` is now REAL** —
      `order.service.OrderDishStatsProvider` (`@Primary`) sums `OrderItem.quantity` over
      COMPLETED orders; its `review_count`/average-rating methods still return 0 until
      `review` lands. **`review`'s port must NOT add a second `@Primary`** (ambiguous-bean
      startup failure) — fill those two methods in or replace the class; its javadoc says so.
      **RESOLVED 2026-09-23 (`review` port): the seam is now fully closed, both halves real.**
      Followed the pre-approved path exactly: added `review.service.ReviewStatsProvider`
      (interface) + `ReviewStatsProviderImpl` (`@Component`, backed by the real `review` table),
      then edited `OrderDishStatsProvider` (the ONE sanctioned edit to `order`'s main source for
      this port — one constructor dependency + two one-line delegating method bodies, nothing
      else in `order` touched) to delegate `reviewCountByDish`/`systemAverageRating` to it. No
      second `@Primary` bean was added — still exactly one `@Primary DishStatsProvider`
      (`OrderDishStatsProvider`), now genuinely composing both modules' data. Every review
      write recomputes and persists `Dish.avgRating`/`Dish.finalScore` (`ReviewService`, mirroring
      Django's `_update_dish_avg_rating`) and `ChefProfile.rating` (`_update_chef_avg_rating`).
      Verified end-to-end in `ReviewControllerTest.topDishRanking_reflectsRealReviewRatings_...`:
      two dishes named so alphabetical/tie-break ordering would be wrong, 5 reviews each (one
      rated 5⭐, the other 1⭐) via the real `POST /api/reviews/` endpoint, then
      `GET /api/dishes/top` shows `review_count`/`avg_rating` populated for real and the
      higher-rated dish's Bayesian `score` genuinely higher — not a coincidence of ordering.
      Original text: **`DishStatsProvider` (`order`/`review` — `sold_count`, `review_count`,
      platform-average rating) is UNCHANGED and still defaults to zeros** —
      those two modules are not ported yet, so the Bayesian top-dish score
      still collapses to the system average (0.0) for every dish and
      `/api/dishes/top` still effectively orders by name. `order`/`review`'s
      own future ports must supply this seam; `profile` had nothing to do
      with it. Each future module registers a `@Primary` bean;
      `@ConditionalOnMissingBean` is deliberately NOT used (it does not fire
      for component-scanned `@Component`s — this cost one debugging cycle,
      noted in the javadoc).
- [ ] **`dish` — sorting by `sold_count` happens in memory, not SQL.** Django sorts by
      the `sold_count` Subquery annotation; that subquery hits `order.OrderItem`, which
      does not exist yet. `DishService.getDishesInternal` therefore loads the whole
      filtered set, sorts it by the provider's map, then slices the page (so paging is
      globally correct, not per-page correct). `PRICE_*`/`RATING_*` still sort and page
      in SQL. Move this branch back into SQL when `order` lands.
      **Still open after the `order` port (2026-09-23):** the provider now returns real sold
      counts so the ordering is correct, but the branch is still in-memory — moving it into
      SQL means editing `dish`'s `DishService`/specifications, which the `order` task was not
      allowed to touch. Correct but O(filtered set) per request; do it in a `dish` pass.
- [x] **RESOLVED 2026-09-22 — `dish` ADMIN/CHEF gating and the permission half of
      `@require_object_permission` are now real.** `create_dish`/`get_all_my_dishes`
      are `hasRole('CHEF')`; `hard_delete_dish` and all three dish-location writes are
      `hasRole('ADMIN')`. More importantly, `DishService.assertCanModify` now ports
      **both** halves of `@require_object_permission('dish.change_dish', Dish,
      owner_field='owner')` — the `user.has_perm('dish.change_dish')` model check
      (via `users/service/RolePermissions`, transcribed from
      `setup_permissions.py` + `auth_permission.json`) and then owner-or-**ADMIN**
      (previously owner-or-`isStaff`). Net new behavior: a CUSTOMER is refused even for
      a dish it owns (it holds only `dish.view_dish`), and an inactive user has no
      permissions at all — both are Django `ModelBackend` semantics and both are
      unit-tested in `DishServiceTest`. The deliberate narrowing where this port uses
      `assertCanModify` for endpoints Django guards with `dish.view_dish` is kept and
      now explicitly PORT-NOTE'd as unreachable-in-practice. The `DishIngredient`
      `created_by`-vs-parent-dish-owner narrowing already flagged is unchanged.
- [ ] **`dish` — Django bugs preserved verbatim (not fixed), each PORT-NOTE'd and
      regression-tested.** Say the word to fix any of them:
      1. **`exceptions/dishes.py` message mix-up.** `DishPermissionDenied` has
         `message` assigned twice, so its real message is `"Dish is not deleted"`
         (the intended `"You can only modify your own dishes"` is shadowed), and
         `DishIsNotDeleted` has no `message` at all, inheriting the base default
         `"Internal server error"` while still returning 400/`DISH_NOT_DELETED`.
         Both asserted in tests.
      2. **`soft_delete_dish` can never raise `DishIsReferenced`** — the
         `has_related_objects()` guard in `DishORM.soft_delete_dish` is commented out,
         so it unconditionally returns True.
      3. **`add_ingredient_to_dish` never raises `DishIngredientAlreadyExists`**
         (despite the endpoint declaring it): when the ingredient is already on the
         dish it silently returns the existing row, with freshly computed
         nutrition/warnings in the RESPONSE but nothing persisted.
      4. **The search fuzzy threshold is dead code.** `DishSearchService.FUZZY_THRESHOLD
         = 0.1` is never applied — both `if score >= FUZZY_THRESHOLD:` lines are
         commented out — so every non-deleted dish enters the candidate set for any
         non-empty query. Exact matches still rank first.
      5. **The exact-alias search stage does not filter deleted dishes** (the only one
         of the four stages missing a `dish__deleted=False` guard), so an alias can
         surface a soft-deleted dish.
      6. **Layers 2 and 3 of the nutrition validation are unreachable.**
         `validate_portion_weight`, `validate_nutrition_outcome` and their
         `validate_on_publish` wrapper are defined but called from nowhere in the whole
         Django tree — only Layer 1 is wired. Ported faithfully, exposed as public
         methods, unit-tested, and — like Django — invoked by no controller path.
         Wiring them up would be a behavior change, not a port.
- [ ] **`dish` — `NutritionValidationException`'s status differs between Django and
      this codebase (pre-existing, in `ingredient`, not introduced here).** Django's
      class sets `status_code`/`default_code` instead of the `error_code`/`message_code`
      the base `APIException` actually reads, so at runtime it is a **500
      INTERNAL_SERVER_ERROR**, not the 400 it looks like. The already-ported
      `ingredient/exception/NutritionValidationException.java` chose **400
      `NUTRITION_INVALID`**. `dish` is the only thing that raises it (the per-100 g
      "vượt ngưỡng vật lý" hard fail), so the difference is now live. Left as-is —
      fixing it means editing `ingredient`, which was out of scope. Decide which
      behavior is wanted.
- [ ] **`dish` — FE-admin checks `LOCATION_HAS_CHILDREN`, backend sends
      `DISH_LOCATION_HAS_CHILDREN`.** `FE-admin/src/pages/AdminDishLocations.jsx:116`
      compares against `'LOCATION_HAS_CHILDREN'`, but `exceptions/dishes.py` defines
      `message_code = "DISH_LOCATION_HAS_CHILDREN"`. This mismatch exists in Django too
      — the FE branch is already dead there. Ported Django's value. Low-stakes, but
      worth fixing on the FE side while someone is in there.
- [x] **`attachment` storage backend — RESOLVED 2026-09-22.** Originally flagged:
      local filesystem vs. S3 (Django's actual `AttachmentService` code hard-requires
      S3 — raises `RuntimeError` at construction if `USE_S3` is false — with no working
      local-storage code path in Django today, despite a dormant, never-wired-up non-S3
      settings branch). **User decision: implement real S3 now**, as the default
      backend, matching Django's actual hard-S3-required behavior; local filesystem
      storage kept as an explicit `app.storage.backend=local` opt-in (costs nothing to
      keep behind the same `AttachmentStorageService` interface). See the follow-up
      Session log entry below for what was built and how it was tested (LocalStack via
      Testcontainers — no real AWS credentials needed to run the suite). The
      `PUT /api/attachments/{uid}/upload?token=...` local-substitute endpoint (no
      Django equivalent) is now only exercised when `app.storage.backend=local`; under
      the default `s3` backend the client PUTs straight to a real presigned S3 URL and
      never touches this endpoint.

- [x] **RESOLVED 2026-09-22 (orchestrator, by explicit user decision) — Django's
      password-RESET flow was dead code; this is now the one deliberate deviation
      from Django's own behavior in the whole port.** `Service.reset_password`
      looked the OTP record up filtering `active=True`, but the mandatory
      preceding step `verify_otp` sets `active=False` in the same `save()` — so
      the record was always invisible to its own query and the flow could never
      complete (structurally identical to the still-preserved `restore_ingredient`
      dead end above). Unlike that admin-only feature, this is FE-admin's main
      forgot-password UX, so the user chose "fix it" over "stay bug-compatible."
      **Fix**: `AuthService.resetPassword` now looks the record up via a new
      `UserOtpRepository.findFirstByResetSessionToken` (no `active` filter),
      gated by `otpVerified`/`isExpired` as before. **Follow-on correctness issue
      caught while making this change** (not present in Django, introduced by
      removing the filter, fixed before shipping): dropping the `active=True`
      filter alone would have made the reset endpoint replay-able — the same
      `reset_session_token` could reset the password repeatedly until the OTP's
      TTL expired, since `otpVerified` was never cleared on success. Fixed by
      also clearing `otpVerified` (not just `active`) once a reset succeeds, so
      the token is genuinely single-use. Test rewritten:
      `AuthOtpControllerTest.passwordReset_confirmMismatchIs400_thenTheVerifiedFlowActuallySucceeds`
      now asserts success + old-password-rejected + new-password-works + replay-rejected.
- [ ] **`users` — `verify_email_change` is ported by INTENT, not verbatim, because
      Django's line cannot run.** `Service.verify_email_change` compares the submitted
      code with `if record.otp != otp`, but `UserOTP` has no `otp` field at all (only
      `otp_hash`) — so the line raises `AttributeError` and
      `POST /api/auth/email-change/verify` always returns 500 in Django today. Porting
      that literally would mean writing a deliberate crash, so this port compares
      against the stored Argon2 hash instead; everything around it is faithful
      (check order record/active/owner → purpose → code → target_email → email-taken,
      and the fact that this path does NOT count attempts or enforce expiry, because it
      bypasses `UserOTP.verify()`). Confirm this is the wanted behavior. The same
      non-existent `otp` field also makes `Query.get_reset_password_otp` dead — that one
      is called from nowhere and was simply not ported.
- [ ] **`users` — `POST /api/auth/register` has no Django equivalent and creates an
      ACTIVE account with no email verification.** It predates the OTP port (foundation
      pass) and is now the bootstrap path for the `attachment`/`ingredient`/`dish`/role
      test fixtures. Django's real front door is `POST /api/auth/signup`, which creates
      the account INACTIVE and requires an emailed OTP — that is now ported and tested.
      Recommend deleting `/register` and moving the fixtures to signup+verify-otp; left
      in place because doing it inside this task would have churned four other modules'
      test suites for no porting gain. It does at least assign the CUSTOMER role now,
      like `Query.create_user`.
- [x] **RESOLVED 2026-09-23 (`profile` port) — `POST /api/auth/upgrade-to-chef` is now
      live.** Built as `profile.web.UpgradeToChefController` (physically in the
      `profile` module, mapped to the exact Django path `/api/auth` so `users`'
      `AuthController` never needed touching) + `profile.service.UpgradeToChefService`,
      which replicates Django's exact call order: check CUSTOMER membership first
      (fail fast, matching Django's guard being outside the `atomic()` block) →
      `ProfileService.createChefProfile` → `ChefPaymentService.createOrUpdatePaymentInfo`
      → `AuthService.upgradeCustomerToChef(user)` last, all inside one
      `@Transactional`. The "Only CUSTOMER can upgrade to CHEF" guard is a bare
      `IllegalStateException` (not an `ApiException`), matching Django's bare
      `raise Exception(...)` → generic 500 CONTACT_ADMIN_FOR_SUPPORT. Verified
      end-to-end over real HTTP in `UpgradeToChefControllerTest`, including the
      cross-module proof this task specifically asked for: a CUSTOMER-role token is
      rejected by CHEF-only endpoints in BOTH `dish` (`GET /api/dishes/mine`) and
      `menu` (`GET /api/menus/mine`) before the upgrade, and accepted by both
      afterward, using the exact same token (no re-login needed —
      `JwtAuthenticationFilter` re-reads roles from the DB per request).
- [ ] **`users` — OTP/notification emails are plain text, not Django's HTML
      templates.** Django renders `templates/email/*.html` (all extending
      `email/base.html`) and attaches them as an HTML alternative. `AuthEmailService`
      ports the subject lines **verbatim**, the purpose→template routing exactly
      (including Django reusing `signup_verification.html` for
      EMAIL_CHANGE/BANK_VERIFY/WITHDRAW_VERIFY and its `ValueError` for anything else)
      and Django's swallow-and-log failure handling, but sends a plain-text body
      carrying the same information. Reimplementing Django template inheritance in a
      different engine has no contract surface (FE never sees these bodies). Also note
      `JavaMailSender` is resolved through an `ObjectProvider`, so the app and the test
      suite boot fine with no SMTP host configured.
- [ ] **`users` — SIGNUP OTP verification still does NOT create the `CustomerProfile`
      row Django creates (only partially resolved by the `profile` port).**
      `Service.verify_otp` does `CustomerProfile.objects.get_or_create(user=user)`
      alongside activating the user and (re-)assigning the CUSTOMER group. The
      `profile` module now EXISTS and its own endpoints (`CustomerService.getOrCreate​CustomerProfile`)
      do the same lazy `get_or_create` Django's other ORM methods use — so a customer
      who visits any `profile` endpoint gets the row exactly like Django. But the
      specific `verify_otp` call site is in `users/service/AuthService`'s OTP flow,
      which this task's boundaries explicitly kept off-limits (`users` is complete/
      tested; the only sanctioned touch was calling `upgradeCustomerToChef`) — so
      that ONE call site is still not ported. Net effect, precisely bounded now:
      `CustomUser.is_onboarded` (via `ProfileCustomerOnboardingProvider`, now the real
      `@Primary` implementation) and `DishUserContext.allergyMode` (via
      `ProfileDishUserContext`) both correctly read "no profile row" as their Django
      fallback (`false` / WARN) for a freshly-signed-up user until they touch a
      `profile` endpoint — a cosmetic ordering difference from Django, not a
      functional gap, since every profile-owned read/write path creates the row lazily
      either way. A future session touching `users` should add the `get_or_create`
      call to `verify_otp` to close this for good.
- [ ] **`users` — Django's permission matrix is ported as a code constant, not as
      `auth_permission`/`auth_group_permissions` tables.** See `RolePermissions`'s
      javadoc and "Part 1 findings" for the reasoning (the matrix is written by a
      management command from hardcoded IDs and never mutated at runtime). If the
      product ever wants runtime-editable permissions, this is the seam to replace.
      Worth knowing: ADMIN genuinely does not hold the attachment/profile permissions
      CHEF holds — that asymmetry is in Django's own list, and is asserted as-is.
- [ ] **`profile` — `profile/api.py` defines the SAME class name
      (`ChefPaymentController`, prefix `"chef-payment"`) TWICE — a genuine
      Django copy-paste bug, not a porting artifact.** Both class objects get
      decorated/registered (django-ninja-extra's `api_controller` registers on
      decoration, independent of the Python name binding the second definition
      overwrites), but Django's URL resolver matches the FIRST-registered
      pattern for an identical path, so the SECOND definition's routes
      (GET without `@require_group(CHEF)`, DELETE instead of PATCH for delete)
      are dead/unreachable. This port implements the FIRST definition (CHEF-
      gated GET, PATCH delete) — consistent with every sibling endpoint in the
      file requiring CHEF — documented in `ChefPaymentController`'s class
      javadoc. Confirm this reading of ninja-extra's registration order is
      right if anyone ever runs the actual Django app to double check; low
      stakes either way since both definitions land on `/api/chef-payment`.
- [ ] **`profile` — `profile/api_chef_payment.py`'s OTP-gated bank-verification
      router is dead code in Django: never mounted.** It's imported into
      `marketplace/urls.py` (`from profile.api_chef_payment import router as
      chef_payment_router`) but the only line that would actually wire it up
      is commented out (`#api.add_router("/chef", chef_payment_router)`). Its
      `save unverified → email OTP → verify → is_verified=true` flow is
      genuinely more useful than the live, unverified `POST /api/chef-payment`
      this port implements — flagging in case the product actually wants it
      wired up (uncomment the Django line and it becomes the real spec; this
      port did not build a Spring equivalent since porting genuinely dead code
      as a live endpoint would be a feature addition, not a port).
- [ ] **`profile` — several Django quirks preserved verbatim, all PORT-NOTE'd and
      regression-tested; say the word to fix any of them:**
      1. **`CustomerAddress` soft-delete never actually hides anything.** None
         of `CustomerORM`'s address read paths (list, get-one, get-one-by-id)
         filter `deleted=False` in Django — only `soft_delete_customer_address`
         ever sets the flag. `GET /api/customer-profiles/addresses` still
         returns a soft-deleted address.
      2. **`get_one_customer_address_by_id` (`GET /addresses/{id}`) ignores the
         requested id whenever the user has ANY selected/default address** —
         it returns that one regardless, only falling back to the requested id
         when no address is marked `selected`. In that fallback branch, a
         missing id is an UNCAUGHT lookup failure in Django (`.get()` raises
         `DoesNotExist`, and the service's own `if not address: raise
         ProfileDoesNotExist` is dead code, unreachable) — ported the same way
         (an uncaught `NoSuchElementException`, mapped by the existing
         catch-all to 500 CONTACT_ADMIN_FOR_SUPPORT), not "fixed" into a
         friendlier 404.
      3. **`set_default_customer_address` deselects every address even when the
         requested id doesn't match any of them**, because Django's write
         commits inside its own `atomic()` block BEFORE the service decides
         whether to raise 404 — ported via a separate `REQUIRES_NEW`-annotated
         bean (`CustomerAddressSelectionWriter`, see its javadoc — this is the
         CLAUDE.md §8b "write-then-throw needs its own transaction" pattern
         again, caught before it shipped this time rather than after).
      4. **`CustomerProfileUpdateSchema.phone` is accepted but never persisted
         anywhere** — `CustomerProfile` has no `phone` field; Django's
         `setattr(profile, "phone", value)` sets a throwaway Python attribute
         `.save()` never writes. Accepted and silently ignored here too.
      5. **Setting `diet_mode=NONE` alone (omitting `diet_level`) while a
         stale non-NONE `diet_level` already exists in the DB crashes with an
         uncaught constraint violation (500), not a friendly 400.** Django's
         own two explicit `HttpError(400, ...)` checks in
         `update_customer_profile` don't cover this specific combination — it
         falls through to the real Postgres `CheckConstraint` on
         `diet_mode`/`diet_level` (also ported, in `V7__init_profile.sql`),
         which fires first. See `CustomerProfile`'s javadoc.
      6. **`recommendation`-owned onboarding side effects are omitted, not
         stubbed.** `onboard_customer_profile`'s `height_cm`/`weight_kg` →
         `UserDailyNutrition` persistence and every `@sync_user_feature`-
         decorated method's `RecommendationService().rebuild_user_feature(...)`
         call are pure `recommendation`-internal cache-rebuild side effects
         with no contract surface this module's own callers observe;
         `recommendation` isn't ported (CLAUDE.md §7), and the task instructions
         for this module explicitly say not to build recommendation logic. The
         ingredient-preference half of onboarding (allergic/favourite ingredient
         rows via the already-ported `ingredient` module, `is_onboarded` flag)
         IS fully implemented.
      7. **[CLOSED 2026-09-24 by `certificate`: `ChefCertificationProviderImpl` is the `@Primary` bean]** **`ChefProfilePublicResponseSchema`/`ChefProfileDetailResponeSchema`'s
         `is_food_safety_certified` needs `certificate`, which isn't ported
         yet** — new seam interface `ChefCertificationProvider`, default impl
         returns `false` for everyone (Django's own no-approved-certificate
         behavior), same pattern as `dish`'s seams. `certificate`'s own future
         port should register a `@Primary` bean over it.
      8. **`exceptions/users.py`'s `BankNameRequired`/`BankAccountNumberRequired`/
         `BankAccountNameRequired` are dead imports in Django** (`users/services.py`
         imports them but never raises them — confirmed by grep). Not
         re-declared in `users` (out of this task's scope to touch) or in
         `profile`; required-ness is enforced the way Django's Pydantic schema
         actually enforces it — `@NotBlank` on `ChefPaymentInfoRequest`'s
         fields — which is the real mechanism, not the three unreachable
         exception classes.

- [ ] **`voucher` — endpoints have NO role gate at all, including `create_voucher`
      (real Django gap, ported faithfully, not fixed).** `voucher/api.py` declares
      `auth=AuthBear()` and has zero `@require_group`/`@require_permission`
      decorators on any route — `create_voucher`'s own docstring says
      "(Chef only)" but nothing enforces it: the service does `chef=request.user`
      unconditionally. A plain CUSTOMER can create a "voucher" under their own id.
      In practice this is low-stakes-but-real: `apply_shop_voucher_reservation`
      looks a shop voucher up by `chef=order.chef`, and a CUSTOMER never owns an
      order as its chef, so a CUSTOMER-created voucher can never actually be
      redeemed against a real order — but it is still a genuine authorization gap
      (a CUSTOMER can pollute the global-uniqueness code namespace, see the next
      item, and can list/see it via `GET /api/vouchers`). Confirmed against
      `api.py` line by line, not assumed. Regression-tested
      (`VoucherControllerTest.createVoucher_plainCustomer_isNotBlocked_noRoleGateExistsInDjangoEither`).
      Say the word if you want `hasRole('CHEF')` added to `create_voucher` as an
      actual fix (would be a behavior change vs. Django, not a straight port).
- [ ] **`voucher` — `create_voucher`'s code-uniqueness check is GLOBAL across all
      chefs, even though the DB's own constraint is per-chef (Django quirk,
      ported verbatim).** `VoucherORM.check_code_exists`/this port's
      `VoucherRepository.existsByCodeIgnoreCase` have no chef filter, so two
      different chefs can never use the same code string, even though
      `unique_together = [["chef", "code"]]` (ported as `uk_voucher_chef_code` in
      `V9__init_voucher.sql`) would otherwise allow it. Combined with the item
      above, a rogue CUSTOMER-created voucher can permanently squat a code any
      real chef might want. Regression-tested
      (`VoucherServiceTest.createVoucher_duplicateCode_throwsEvenAcrossDifferentChefs`).
- [ ] **`voucher` — a real, reachable Django bug in the reservation system's
      partial unique constraints (money-adjacent, preserved verbatim, not
      fixed).** `AppliedVoucher.Meta.constraints`'s `UniqueConstraint(fields=
      ["order", "voucher_type"], condition=Q(voucher_type=SHOP_VOUCHER))` (and
      the two `checkout`-scoped PLATFORM_* equivalents) apply to ALL rows of that
      voucher_type for that order/checkout regardless of `status` — not just
      RESERVED ones. Once a SHOP_VOUCHER reservation for an order EXPIRES (row
      stays, status flips to EXPIRED), a second reservation attempt for that same
      order — even a different voucher code — can never INSERT: the partial
      unique index still sees the stale EXPIRED row occupying that
      `(order_uid, voucher_type)` slot, and the insert fails with an uncaught
      constraint violation (500 `CONTACT_ADMIN_FOR_SUPPORT`, matching Django's
      own uncaught `IntegrityError`). Concretely: a customer who lets a 15-minute
      voucher hold lapse at checkout and then clicks "apply voucher" again on the
      SAME order gets a hard 500, not a clean re-reservation. Confirmed this is
      what Django's own `UniqueConstraint(condition=...)` semantics actually do
      (a partial index, not a composite-with-status one) rather than assumed, and
      reproduced against a real Postgres, not just reasoned about:
      `VoucherReservationServiceTest.applyShopVoucherReservation_afterExpiry_secondAttemptForSameOrder_hitsThePreservedUniqueConstraintBug`.
      This is the single highest-value fix candidate in this module if you want
      one — the fix would be either scoping the constraint to `status IN
      (RESERVED, USED)` (a schema change) or having Django's/this port's apply
      flow re-use/delete a non-RESERVED row for that order before inserting a
      new one (a service-layer change). Not attempted — CLAUDE.md §0.1 says port
      first, fix only on request.
- [x] **RESOLVED 2026-09-23 (`order` port) — seam consumed as designed.** `OrderService`
      calls `calculateNetSubtotal`/`applyPlatformVoucherReservation`/
      `applyShopVoucherReservation` unchanged (passing `checkoutUidHint = order.checkout.uid`
      — required, since the checkout-level recalculation filters AppliedVoucher by checkout)
      and writes every status transition itself (RESERVED→USED on COD place-order,
      RESERVED/USED→CANCELLED on cancel, →EXPIRED in the sweep) through
      `order.repository.OrderAppliedVoucherRepository`, an order-owned second Spring Data
      repository over the same `AppliedVoucher` entity (so `voucher` source stayed
      untouched). **The two `applied_voucher` FKs V9 suggests were NOT added** — see the
      `order` open question below. Original note: *forward seam for `order` (mirrors how `cart` left one, see
      that module's PROGRESS.md entry).** `order` isn't ported yet and comes
      right after `voucher` in CLAUDE.md §7's recommended order, so the two
      reservation-apply methods are "primitive-ized": `applyPlatformVoucherReservation`
      takes a `checkoutUid` + a `PlatformCheckoutSnapshot` (pre-computed
      `netSubtotal`/`deliveryFee`/`checkoutSubtotal`) instead of a live
      `Checkout`, and `applyShopVoucherReservation` takes an `orderUid` +
      `checkoutUidHint` + the order's chef/subtotal instead of a live `Order` —
      same pattern as `dish`'s `StockReservation.orderUid` (plain column, not
      yet a real FK) and `cart`'s exposed-but-unrouted seam methods.
      `calculateNetSubtotal` takes a plain `List<OrderSubtotal>` (uid + sub_total
      per order) rather than a `Checkout` entity — it's 100% `voucher`'s own data
      (sums each order's already-reserved SHOP_VOUCHER discount from
      `AppliedVoucher`), so `order`'s future port just needs to supply the list
      of orders-under-a-checkout with their subtotals, nothing else changes.
      **`order`'s future port must also write the RESERVED→USED and
      RESERVED/USED→CANCELLED transitions itself**, directly via
      `AppliedVoucherRepository` (autowire it, don't add methods to
      `VoucherService` for this) — Django's `order/services/__init__.py` writes
      these transitions with raw ORM `.update(status=...)` calls, and
      `voucher`'s own service NEVER performs them, in Django or here (confirmed
      by grepping every `AppliedVoucher.*status=(USED|CANCELLED)` write site in
      the whole backend tree — 100% inside `order`). The `applyShopVoucherReservation`/
      `applyPlatformVoucherReservation` FK migration comment in
      `V9__init_voucher.sql` has the exact `ALTER TABLE` statements `order`
      should run once its own `order`/`checkout` tables exist.
- [x] **RESOLVED — `voucher`'s duplicated exception-class block in
      `exceptions/vouchers.py` investigated: a plain copy-paste artifact, not
      two meaningfully different sets.** The file defines the same 7 classes
      twice back to back, byte-identical, except the SECOND copy also adds
      `VoucherUsageLimitException` (absent from the first copy). Since Python
      module-level class statements simply rebind the name, the second copy is
      what's actually live either way — but that placement doesn't make
      `VoucherUsageLimitException` reachable: grepped every raise site across the
      whole Django backend tree and there are none. The real "usage limit
      exceeded" path (in `validate_voucher_for_order` AND both reservation-apply
      methods) raises `VoucherInvalidException("Voucher đã hết lượt sử dụng")`
      instead. Also newly confirmed while investigating: `VoucherExpiredException`,
      `VoucherMinOrderException`, and `VoucherChefMismatchException` are ALSO dead
      code — none is raised anywhere; their real-world equivalents are plain
      validation-result tuples inside `validate_voucher_for_order`, not
      exceptions. All 8 classes from `exceptions/vouchers.py` were still ported
      1:1 per CLAUDE.md §4 (`voucher/exception/`), each with a PORT-NOTE javadoc
      identifying whether it's live or dead and, if dead, exactly which real
      code path replaced it.

- [x] **RESOLVED 2026-09-23 (`payment` port) — the payment half is implemented.**
      `payment.service.PaymentOrderPaymentGateway` is the `@Primary` `OrderPaymentGateway`
      (order's `NoPaymentOrderPaymentGateway` stays as the displaced default; no `order` file
      changed). The PayOS webhook (`POST /api/payment/payos/webhook`) now moves orders to
      CONFIRMED_SYSTEM through `PaymentService.syncSuccessfulPayment` (Django's bulk
      `orders.update(...)`, via payment's own `PaymentOrderRepository` — Django does not go through
      `OrderStateMachine` there either), including the T3/T3' late-webhook recovery
      (confirm → not_reserved → re-`reserve()` → confirm, or refund + cancel +
      `CANCELLED_REFUNDED_LATE_PAYMENT`). Django's own code never reaches the "confirmed" branch
      (payment open question #1) — fixed by default since 2026-09-23. Completion now writes the settlement
      and credits the chef's wallet (PayOS) / marks COD SUCCESS; cancellation refunds a paid PayOS
      order into the customer's wallet. Original note: *the payment half of the lifecycle is a seam, not an implementation
      (money-critical; decide with the `payment` port).** Django's `OrderService` calls 8
      `PaymentService` methods. They go through `order.service.OrderPaymentGateway`; the
      default `NoPaymentOrderPaymentGateway` moves no money: COD/PayOS place-order still
      succeed (PayOS returns null `payment_url`/`qr_code`), refunds report
      `success=false` (Django then logs and cancels anyway — same code path), settlement /
      escrow release are no-ops. **Nothing in this port ever moves an order to
      CONFIRMED_SYSTEM** — that is the PayOS webhook (`payment/services.py::
      _sync_successful_payment`), including the late-webhook T3' recovery (confirm ->
      `not_reserved` -> re-`reserve()` -> confirm, or cancel + refund +
      `CANCELLED_REFUNDED_LATE_PAYMENT`). `dish`'s `confirm()` boolean and `reserve()`'s
      reopen-terminal-row behavior are already in place for it, and
      `OrderNotificationService` already supports the `CONFIRMED` /
      `CANCELLED_REFUNDED_LATE_PAYMENT` event types. `payment`'s port must register a
      `@Primary` `OrderPaymentGateway` and own the webhook.
- [ ] **`order` — real Django authorization gaps preserved (ported faithfully, not fixed;
      several are money-adjacent).** `order/api.py` has only `auth=AuthBear()` — no
      group/permission decorators anywhere — and these paths never compare the caller to
      the order/checkout owner: (1) `GET /api/orders/{uid}` — any user reads any order
      (address, phone); (2) **`POST /api/orders/{uid}/cancel` — any user can cancel any
      order still DRAFT/PENDING/CONFIRMED_SYSTEM**, which releases its stock and (with a
      real payment module) triggers the refund; (3) all `PATCH /api/checkouts/{uid}/*`,
      `place-order` and `apply-platform-voucher` take only the checkout uid; (4)
      `POST /api/orders/{uid}/apply-voucher` reserves a shop voucher against any order,
      charged to the caller's per-user quota. Regression-tested
      (`OrderControllerTest.preservedQuirk_anyAuthenticatedUserCanReadAndCancelAnyOrder`).
      The chef actions ARE ownership-checked (strict, no ADMIN bypass, 403 `HTTP_ERROR`).
      Role "authorization" otherwise is data scoping only: `GET /api/orders/` and
      `/api/chef/orders/` return CUSTOMER -> own, CHEF -> cooked-by-me, ADMIN (or a user
      in no group — the `get_user_role` quirk) -> **all orders**. Say the word to add
      owner checks.
- [ ] **`order` — COD place-order marks vouchers USED before the payment/confirm block and
      never reverts that on failure** (Django: the `.update(status=USED)` sits outside the
      `try`). A COD placement that fails afterwards leaves the customer's shop/platform
      voucher consumed while the order is back to DRAFT. Preserved + unit-tested
      (`OrderServiceTest.placeOrder_cod_paymentFailure_releasesEveryHold_butVoucherUsedMarkIsNotReverted`).
      Stock is always correctly compensated in that path (every hold released; any already
      CONFIRMED item goes CONFIRMED -> CANCELLED with `DishAvailability` restored).
- [ ] **`order` — `place_order` never checks the orders are still DRAFT.** Inventory-safe
      (re-placing a placed checkout fails in `reserve()`, which refuses to reopen a
      RESERVED/CONFIRMED row and compensates its own Redis decrement — tested: a double COD
      place-order deducts once), but a CANCELLED order's checkout can be placed again: its
      RELEASED/CANCELLED ledger rows are reopened and the order goes back to PENDING.
- [ ] **`order` — `OrderMapper.to_response_with_info` crashes for a chef with no
      `ChefProfile`** (`chef_lat`/`chef_lng` unbound -> `UnboundLocalError` -> 500). Every
      endpoint returning that shape (order detail, lists, confirm, cancel, lifecycle) 500s
      for such an order. Real chefs always have a profile (upgrade-to-chef creates it), but
      a CHEF assigned by role-edit without one would make their orders unreadable.
      Preserved + tested. Also preserved: that shape's `chef_name` is the **username**
      (Django's `getattr(chef, 'full_name', ...)` always misses), while the checkout
      shape's nested order uses the full name.
- [ ] **`order` — the two `applied_voucher` FKs (-> `checkout`, -> `"order"`, CASCADE) are
      NOT added.** `voucher`'s own Postgres-backed `VoucherReservationServiceTest` reserves
      against synthetic order uids, so the FK would break 5 pre-existing tests in a module
      this task could not edit. The one Django behavior that CASCADE carries on a live path —
      `checkout()` deleting a user's stale DRAFT orders also deletes their AppliedVoucher
      rows (freeing the quota) — is emulated explicitly and tested
      (`recheckout_replacesTheOldDraft_andCascadesItsVoucherRows`). Adding the FKs later =
      fix those voucher test fixtures + one `ALTER TABLE` pair.
- [ ] **`order` — smaller deviations/quirks, all PORT-NOTE'd:** (1) `checkout()` is
      `@Transactional` here (Django: autocommit with a per-order atomic block) — strictly
      safer, no compensation logic depends on it; `place_order` is deliberately NOT
      transactional, mirroring Django's autocommit, because its failure branches compensate
      (one wrapping transaction would roll the compensating `release()` writes back — the
      §8b trap). (2) Django passes "Please add a delivery address before checkout" as the
      `CustomerAddressNotFoundException` **detail**; `profile`'s ported class has no detail
      constructor, so `data` is null here. (3) Django `ValueError`s (illegal transitions,
      customer/chef cancel refusals, PayOS-unpaid confirm) are plain 500
      `CONTACT_ADMIN_FOR_SUPPORT`, not 4xx — preserved. (4) The `mongo_chat` Firebase
      pushes in every `order/api.py` action are dropped (CLAUDE.md §0.4). (5) Order emails
      are plain text like `users`' emails, and — as in Django (`EmailClient.send` swallows
      errors) — an SMTP outage does NOT trigger the notification task's retry; only
      pre-send failures do. (6) `recommendation.sync_order_meal_logs` on-complete hook and
      the order-completed vector-refresh signal are omitted (recommendation not ported).
      (7) Tax is persisted at 2 dp (HALF_EVEN) and responses show the persisted value.
      (8) [CLOSED 2026-09-25: `admin` ported] FE-admin never calls these endpoints (its order screens use
      `/api/admin/orders`, owned by the future `admin` module).

### `payment` — open questions (money correctness first; nothing below was "fixed" silently)

- [x] **RESOLVED 2026-09-23 (user chose "fix") — #1 — In Django, EVERY successful PayOS payment
      ends with the order CANCELLED and the customer refunded into their internal wallet.**
      **Resolution:** `app.payment.preserve-django-confirm-outcome-bug` now defaults to **`false`**
      (application.yml + `PaymentProperties`), i.e. the intended behavior below is what runs; `true`
      is kept only to reproduce Django's bug. Tests: `AbstractPaymentFullStackTest` resets the flag to
      the default (false) after each test; the one test pinning Django's branch
      (`PayOsWebhookTest.djangoBugFlagOn_successWebhook_...`) sets it to `true` explicitly; new
      `PayOsWebhookTest.defaultMode_successWebhook_holdsTheMoney_confirmsEveryItem_andRefundsNobody`
      asserts the default. No `order` test depended on the old default (none posts a webhook), so no
      `order` file changed. Original analysis: `_sync_successful_payment` does
      `outcome = stock_reservation.confirm(...)` and then `if outcome in ("confirmed",
      "already_confirmed"): continue`. But `confirm()` returns a **bool** (`dish/services/
      stock_reservation.py:253`, ported as `boolean` by `dish`), so the test is never true and every
      item goes into the late-webhook "recovery" branch: `reserve()` refuses to reopen the
      just-CONFIRMED row (`ValueError`) → `order_needs_refund` → `handle_order_cancellation_refund`
      (customer credited `order.total_price`, payment REFUNDED) → order CANCELLED →
      `CANCELLED_REFUNDED_LATE_PAYMENT` email. Side effects: the first item's stock stays
      deducted (confirmed, never released — a stock leak) and later items' holds are never confirmed
      (the loop `break`s). Verified by reading the code and **reproduced end-to-end over real HTTP**
      (`PayOsWebhookTest.preservedDjangoBehavior_successWebhook_...`). The genuinely-expired-hold
      case happens to work, because `confirm()` returns False there too.
      **Was ported faithfully as the DEFAULT** (`app.payment.preserve-django-confirm-outcome-bug=true`,
      env `PAYMENT_PRESERVE_DJANGO_CONFIRM_OUTCOME_BUG`). Setting it to `false` gives the behavior
      the code's own comments describe (true → confirmed; false + ledger row CONFIRMED →
      already_confirmed; else not_reserved → re-reserve/refund) — fully tested in
      `PayOsEscrowLifecycleTest` (webhook → HOLDING → chef completes → 90% RELEASE into the chef's
      wallet; late webhook recovered; sold-out late webhook refunded). **Decide which you want; the
      fix is flipping that default** (or deleting the flag and the faithful branch in
      `PaymentService.interpretConfirm`).
- [x] **RESOLVED 2026-09-23 (user chose "fix") — #2 — In Django, a wallet can NEVER pass
      `verify_integrity()`, so withdrawals are impossible.** **Resolution:** every wallet amount is
      hashed/signed as `PaymentHmac.canonicalAmount` = `setScale(2, HALF_UP).toPlainString()`
      (`180000`/`180000.0`/`1.8E+5` → `"180000.00"`, identical to the NUMERIC(15,2) read-back) — in
      the wallet signature and in the ledger chain hash on write (credit, withdrawal debit) and on
      verify; `WalletService` signs a wallet the moment it is created (`signIfCreated`, both the
      locked and unlocked get-or-create); `WithdrawalService` re-signs after the successful payout
      (`pending_balance -= amount`) as it already did after debit/revert. The Django scale
      emulation (`emulateFreshPythonDefaults`) is gone. Payment-event chain hashes (no amounts) are
      unchanged and still byte-identical to Django. Fresh DB → nothing to migrate. Tests:
      `PaymentHashingTest` vectors recomputed with Python `hmac` over the canonical strings;
      `WalletLedgerTest.integrity_passesForNewAndActiveWallets_andStillDetectsTampering`, the
      success-withdrawal test now withdraws TWICE and verifies after each; new full-stack
      `PaymentControllerTest.chefEarnsThroughACompletedOrder_thenWithdraws_andTheWalletStillVerifies`
      (PayOS order → webhook → chef completes → 126000 RELEASE → OTP withdrawal of 100000 over HTTP
      → balance 26000, signature + ledger + chain all valid; no seeded wallet). Original analysis: The wallet signature (`f"{user_id}:{balance}:{pending_balance}"`) and the
      ledger chain hash embed `str(Decimal)`. At write time the new balance is
      `_quantize_amount(...)` → scale 0 (`"180000"`), the old balance is either the NUMERIC(15,2)
      column (`"180000.00"`) or, for a just-created wallet, the Python int default (`"0"`); at
      verify time everything is read back from the DB as scale 2. So the stored signature and every
      chain hash mismatch as soon as a wallet has one transaction, and a brand-new wallet has
      `signature=""` (→ False). `request_withdraw_otp` requires all three checks → always 400
      "Wallet integrity check failed" (fails CLOSED — no money can leave, which is the safe
      direction). Additionally the payout success path does not re-sign the wallet after
      `pending_balance -= amount`. Verified with the Python formulas and reproduced in
      `WalletLedgerTest.preservedDjangoBug_...` / `PaymentControllerTest.withdraw_fromAWalletCredited...`.
      Ported faithfully (the hash strings are byte-identical to Django's — `PyCompat.decimal`, the
      locked row is refreshed from the DB like Django's fresh SELECT, and the created-wallet `0` is
      emulated). The withdrawal logic itself (row lock, balance re-check under lock, pending
      balance, signed payout, same-transaction revert, failure log, retries) IS exercised and
      correct, using a correctly-signed seeded wallet (`seedIntegralWallet`). **Fix when wanted:**
      format every Decimal at 2 dp (`setScale(2)`) before hashing/signing, re-sign after the payout
      success path, and sign new wallets at creation. Fresh DB → no legacy hashes to migrate.
- [x] **RESOLVED 2026-09-23 (user chose "fix") — #3 — Multi-chef checkouts (one PayOS payment,
      several orders) mis-handle money in Django** (fix described after the Part A findings below).
      Original analysis: (a) **only the first completed order's chef is ever paid** —
      completing an order sets the checkout's ONE payment state to RELEASED, and
      `complete_order_with_release` only credits the chef when the state is HOLDING/SUCCESS, so
      every later chef's RELEASE credit is silently skipped (their settlement row is still written);
      (b) **refunds cover only the first cancelled order** — the refund credits
      `order.total_price` then marks the whole payment REFUNDED, so cancelling a second order of the
      same checkout hits the REFUNDED idempotency guard ("ALREADY_REFUNDED") and that customer is
      never refunded for it. Pinned by `PayOsEscrowLifecycleTest.multiChefCheckout_...`. A fix needs
      a per-order escrow state (or per-order refund/release bookkeeping) — a design decision.
      **Part A investigation (2026-09-23, before any code change) — what per-order / per-chef data
      Django ALREADY has.** The earlier conclusion ("escrow is tracked once per payment") is true
      only of the *state machine*; the *money records* are already per order:
      - `PaymentTransaction` is `OneToOneField(Checkout)` (`backend/payment/models.py:37-41`) and its
        mutable state is `OneToOneField(PaymentTransaction)` (`models.py:86-90`) — this is the ONLY
        thing Django's release/refund guards read (`order/services/__init__.py:790` release guard
        `state.status in [HOLDING, SUCCESS]`; `payment/services.py:1608` refund guard
        `state.status == REFUNDED`). That is the bug: a per-checkout flag gating per-order money.
      - **`WalletTransaction.order` = `ForeignKey(Order, SET_NULL, null=True)` (`models.py:397-403`)
        + `transaction_type` REFUND/RELEASE/PAYOUT (`models.py:404`)** — and BOTH money movements
        already fill it with the order: the refund credit passes `order=order`,
        `reference_id=f"refund_{order.uid}"` (`services.py:1616-1628`); the escrow release passes
        `order=order`, `reference_id=f"release_{order.uid}"` (`order/services/__init__.py:790-803`),
        through `credit_internal_wallet(..., order=...)` which stores it (`services.py:403-458`, 442).
        The table is append-only (trigger, migration 0012) and hash-chained, and every credit row's
        state is SUCCESS at insert (`services.py:449-455`). So "has THIS order already been
        refunded / released?" is exactly `EXISTS wallet_transactions WHERE order = X AND type = …`.
        Ported as `wallet_transactions.order_uid` (FK to `"order"(uid)`, V11) — already there.
      - `SettlementRecord.order` = **`OneToOneField(Order)`** + `chef = ForeignKey(User)` +
        `gross_amount`/`platform_fee`/`chef_payout_amount` (`models.py:507-563`); written per order
        at completion by `create_settlement_record` (`services.py:2178-2238`, 10% fee
        `services.py:44`). It is the per-order, per-chef *entitlement* (what the chef is owed) —
        written even when Django then skips the credit (#3a), and for COD too, so it is NOT proof
        that money moved; it is the right source for the RELEASE amount (already used so).
      - `PayoutLedger` lines are per settlement (FK `settlement_record`) and carry `order_uid` +
        `chef_id` (`models.py:578-620`) — double-entry bookkeeping of the same split, per order.
      - `Order` itself has `chef` (`order/models.py:65-73`), per-order `total_price`
        (`order/models.py:118`; the refund amount Django already uses) and a per-order
        `payment_status` column (`order/models.py:124-128`) — but that column is only ever a COPY
        of the checkout-level state (bulk-set HOLDING at `services.py:943`, overwritten from the
        payment state after cancel at `order/services/__init__.py:666`), so it is not evidence.
      - `ChefCODBalance` is `OneToOneField(User)` — per chef, not per order, COD-only and never
        written (`models.py:623-663`; open question #7e). Irrelevant to PayOS escrow.
      - `PaymentTransactionEvent` is per payment (`models.py:116-142`) with a free-form JSON
        `payload`; usable as an audit trail for per-order events, not needed as the guard.
      **Conclusion:** per-order release/refund CAN be driven from existing data — the guard becomes
      "does a RELEASE/REFUND `wallet_transactions` row already exist for this order?", checked under
      the same `SELECT … FOR UPDATE` on the payment row Django already takes for refunds. **No
      migration is needed.** The checkout-level state then only needs to describe the aggregate:
      it stays HOLDING while any order of the checkout still has money in escrow, and moves to
      RELEASED/REFUNDED when the last order is resolved.
      **Fix (built on the findings above, no migration — max is still V11):** new package-private
      `payment.service.EscrowBook` answers `isReleased(order)` / `isRefunded(order)` from
      `wallet_transactions (order_uid, transaction_type)` and `siblings(checkout, order)` (are all
      OTHER orders of the checkout out of escrow — RELEASE/REFUND line, or terminal
      COMPLETED/CANCELLED — and was any of them released). Two new read-only repository queries
      (`WalletTransactionRepository.existsByOrderUidAndTransactionType` / `findOrderUidsHavingType`,
      `PaymentOrderRepository.findUidAndStatusOfCheckout`).
      *Release* (`PaymentOrderPaymentGateway.settleCompletedOrder` → `releaseEscrowForOrder`, still
      inside `complete_order`'s transaction): now takes `SELECT … FOR UPDATE` on the payment row (the
      lock refunds already took, so release/refund decisions per payment are serialized); credits
      THIS order's chef its settlement payout unless the order already has a RELEASE or REFUND line;
      moves the state to RELEASED only when no sibling is left in escrow, otherwise records an
      `ORDER_RELEASED` audit event and leaves it HOLDING.
      *Refund* (`PaymentRefundService`, same lock as before): the idempotency guard is per order
      ("this order already has a REFUND line" → ALREADY_REFUNDED; Django's state==REFUNDED guard kept,
      REFUNDED now means every order is resolved); refuses (success=false, no credit) an order already
      released to its chef; credits exactly `order.total_price`; moves the state only when the last
      order leaves escrow (REFUNDED, or RELEASED if a sibling was paid to its chef), otherwise records
      `ORDER_REFUNDED` and stays HOLDING.
      Single-order checkouts behave exactly as before (credit → RELEASED / REFUNDED, same guards,
      same events). Replayed webhooks still write nothing while HOLDING/after REFUNDED (unchanged
      guard); concurrent refunds stay exactly-once per order (lock + per-order ledger check).
      Known cosmetic limitation: `order`'s cancel copies the checkout-level state onto
      `order.payment_status` (order-owned code, not changed), so a cancelled order whose sibling is
      still in escrow shows `payment_status=HOLDING` although its own refund is done — its REFUND
      wallet line is the source of truth.
      Tests (replacing the one that pinned the bug), `PayOsEscrowLifecycleTest`:
      `multiChefCheckout_bothChefsComplete_eachIsReleasedTheir90PercentShare` (2 chefs, 1 payment of
      280000 → both complete → each wallet 126000, each RELEASE tied to its order and equal to its
      settlement; HOLDING after the first, RELEASED after the second; replayed webhook / refund of a
      released order move nothing; event chain valid);
      `multiChefCheckout_cancelOne_refundsOnlyThatOrder_theOtherIsStillReleasable` (only 140000
      refunded, state stays HOLDING, retried refund ALREADY_REFUNDED, the sibling then completes and
      its chef gets 126000, state RELEASED); `multiChefCheckout_cancelBoth_concurrently_eachOrderRefundedExactlyOnce`
      (8 threads, 4 per order → exactly 2 credits = 280000, state REFUNDED, later HTTP cancels credit
      nothing).
- [ ] **#4 — Webhook replay/idempotency (mirrored exactly, gaps flagged).** Django's guard (state
      HOLDING → "Already processed"; CANCELLED/FAILED/REFUNDED → ignored) runs BEFORE any write,
      so a replayed webhook writes nothing — tested for replays after HOLDING, REFUNDED, CANCELLED
      and RELEASED (the RELEASED replay fails the PENDING-only transition to HOLDING after writing
      only an audit event; no money/stock moves). **No double credit is possible on this path:**
      the only credit it can trigger is the refund, which re-checks REFUNDED under
      `SELECT … FOR UPDATE` (8-thread race test → exactly one credit). Gaps, as in Django: (a) the
      guard is a plain read, not under a lock — two copies of a webhook arriving concurrently can
      both pass it (double events/notifications; stock `confirm()` is idempotent); (b) event-chain
      writes are not serialized per payment, so that race can FORK the audit chain, after which
      `assert_payment_confirmed` refuses every refund for that payment ("audit chain tampered").
      Recommended hardening if wanted: `SELECT … FOR UPDATE` the payment row at the top of
      `handle_payos_webhook`/`sync_payment_by_order_code`.
- [ ] **#5 — Webhook signature verification: ported exactly, including its weaknesses.**
      HMAC-SHA256 over every sorted `data` field with PAYOS_CHECKSUM_KEY (vectors from the real
      Python code in `PayOsSignatureTest`). (a) Compared with plain `lower() ==`, not constant-time
      (Django's own wallet signature uses `hmac.compare_digest`); (b) values are rendered with
      Python `str()` (`True`, Python repr for lists/dicts) where the official PayOS SDK uses
      `true`/JSON — irrelevant for today's flat string/int webhook payloads, would break if PayOS
      ever adds a boolean/array field; (c) the webhook's `amount` is never compared with the
      payment's amount (Django doesn't either; PayOS links are fixed-amount); (d) an invalid
      signature that names a real order code appends a `WEBHOOK_SIG_INVALID` event to that
      payment's chain (forensics; unauthenticated callers can grow it but not break it).
- [ ] **#6 — Refund transaction boundary differs from Django (deliberate).** Django's refund block is
      a nested `atomic` (SAVEPOINT) inside `cancel_order`'s transaction; JPA has no safe savepoint
      rollback (a rolled-back savepoint leaves a dirty, e.g. credited, wallet entity in the
      persistence context), so `PaymentRefundService` runs in `REQUIRES_NEW`. Difference: if
      `cancel_order` fails AFTER the refund committed, the refund stays; a retried cancel then hits
      the REFUNDED guard — never a double credit. The late-webhook path needs this anyway (it must
      see the committed HOLDING state). Everything else in `payment` mirrors Django's boundaries via
      `PaymentTx` (autocommit steps on webhook/sync/create paths; joins `complete_order`'s
      transaction for settlement + RELEASE).
- [ ] **#7 — Other preserved Django bugs/quirks (money-adjacent), each PORT-NOTE'd + tested:**
      (a) `POST /api/payment/{uid}/cancel`'s guard is inverted — an unpaid PENDING payment can never
      be cancelled there, a HOLDING one is moved to REFUND_PENDING (no money moves), a
      SUCCESS/REFUND_PENDING one is cancelled at PayOS; `place_order`'s failure path therefore never
      cancels the PayOS link it just created. (b) A failure/expiry webhook (or sync) runs
      `_cancel_orders_for_payment`, which ADDS the ordered quantity to `DishAvailability` although
      PayOS stock was only held in Redis — committed stock is inflated (the RESERVED hold is also
      later returned by the sweep). (c) The public, unauthenticated `GET /api/payment/payos/return
      ?code=00&orderCode=…` re-runs the escrow confirmation whenever PayOS says PAID — with the
      flag in #1 off (now the default), replaying it bulk-resets that checkout's orders to CONFIRMED_SYSTEM even after
      a chef advanced them. (d) `get_chef_balance_summary.total_settled` double-counts PayOS money
      (LEDGER_RECORDED settlements + the wallet balance that already contains their RELEASE).
      (e) `ChefCODBalance` is never written (its only writer is uncalled), so COD settlement always
      answers "COD balance not found for chef". (f) PayOS order code = MD5(payment uid) mod 10¹³,
      no unique constraint (collision → ambiguous webhook lookup). (g) The SDK timeout message
      "Request timed out" does not contain Django's retry token "timeout", so it isn't retried at
      the service layer (the SDK already retried twice). (h) the payout HTTP call (with retries and
      sleeps) runs INSIDE the DB transaction holding the wallet row lock — a crash after PayOS
      accepted the payout but before COMMIT rolls the debit back although the money left.
- [x] **#8 (RESOLVED 2026-09-25, see checklist) — Authorization gaps in `payment/api.py` (were preserved; Django has only `auth=AuthBear()`):**
      any authenticated user can read/cancel ANY payment by uid, query any PayOS order code, read any
      chef's balance, and trigger any chef's COD settlement. The bank-info routes' `@require_group
      (CUSTOMER)` is commented out in Django. Say the word to add owner/role checks.
- [ ] **#9 — Secrets/PII (as Django):** `WALLET_CHAIN_SECRET` defaults to `""` (Django's own
      settings default; HMAC with an empty key is legal and emulated) — set a real secret in prod.
      `PAYMENT_EVENT_SECRET` is undefined in Django (→ SECRET_KEY); here it falls back to
      `SECRET_KEY`, then the JWT secret. Bank account numbers are plaintext, and the FULL number is
      copied into each payout's `wallet_transaction_states.metadata` (the failure log masks it).
- [ ] **#10 — Deviations / not ported:** (a) missing PAYOS_* credentials fail only the PayOS calls
      (Django raises in `PaymentService()`'s constructor, i.e. breaks every order endpoint);
      (b) dead code not ported: `payout_to_chef` (no caller — the live payout is the wallet
      withdrawal), `create_or_update_customer_payment_info`, provider `download_invoice`/
      `confirm_webhook_url`/`get_payout_info`; `cancel_payment_with_refund` and
      `update_chef_cod_balance` are ported though uncalled; (c) emails are plain text (subject
      verbatim), like every other ported email; (d) test infra: the shared
      `TestcontainersConfiguration` now also registers `FakePayOsServer` (a real in-process HTTP
      PayOS that verifies request signatures and signs payout responses) — required because
      `order`'s own PayOS place-order tests now go through the real gateway; no real PayOS call is
      ever made by the suite.

- [ ] **`review` — 🔴 EVERY exception class in `exceptions/reviews.py` sets the wrong attribute
      name, so all ten are really 500s in Django, not the 400/404/403/503/502 they visually
      claim.** `utils/router/exception.py::APIException`/`_custom_exception_handler` read
      `exc.error_code`; every class in this one Django file sets `status_code` instead (a
      different, never-read attribute) and so falls back to the base class default
      (`HTTPStatus.INTERNAL_SERVER_ERROR`). `message`/`message_code` ARE correct, so any FE
      that switches on `message_code` still works; one that switches on HTTP status/`error_code`
      does not. Same bug class already flagged for `ingredient`'s `NutritionValidationException`
      (which chose to diverge from Django and use the "intended" 400) — **this port went the
      other way for `review` on this task's explicit instruction ("port faithfully and note
      it")**: all ten classes (`ReviewNotFoundException`, `DishNotOrderedException`,
      `DuplicateReviewException`, `OrderNotCompletedException`, `InvalidRatingException`,
      `ReviewReplyNotFoundException`, `ReviewReplyAlreadyExistsException`,
      `NotDishOwnerException`, `AIModelUnavailableException`, `AIModelInvalidResponseException`)
      use `HttpStatus.INTERNAL_SERVER_ERROR` in this port, each with a javadoc explaining why.
      Regression-tested (`ReviewControllerTest.createReview_ratingOutOfRange_isInvalidRating_withFileWideStatusCodeQuirk`
      asserts 500 + `message_code=INVALID_RATING`). Say the word if you'd rather this module use
      the "obviously intended" status codes instead (would make `review` inconsistent with
      Django, but consistent with `ingredient`'s prior choice for the same underlying bug class).
- [ ] **`review` — a real, reachable Django bug preserved verbatim: `attachment_uid` is validated
      but never actually linked to the review.** `create_review`/`update_review` call
      `AttachmentService.handle_attachment(uid)` purely for its side effect (raises
      `AttachmentNotFoundException`/`AttachmentIsNotCompletedException` for a bad/incomplete
      attachment) but then discard its return value — the local `attachment` variable stays
      `None` and is what actually gets passed to `ReviewORM.create_review`/`update_review`. Net
      effect: `attachment_url` in every review response is always `null`, no matter how valid the
      submitted `attachment_uid` was. Not on the CLAUDE.md §0.6 "already decided fix" list, and
      FE-admin does not call any `review` endpoint at all (grepped `FE-admin/src` — zero matches),
      so there is no known consumer relying on either behavior. Ported faithfully
      (`ReviewService.createReview`/`updateReview`, both PORT-NOTE'd) and regression-tested
      (`ReviewServiceTest.createReview_withAttachmentUid_validatesButNeverLinksAttachment`). Say
      the word if you want this fixed instead (the fix is a one-line "assign the return value").
- [ ] **`review` — `ReviewReplyResponse.resolve_owner_info` is defined TWICE in Django**
      (`review/schemas/responses.py`), same class of copy-paste bug as `profile`'s duplicate
      `ChefPaymentController`. Python keeps the second definition, which computes `full_name` as
      a bare `obj.owner.get_full_name()` with NO "or None"/"or email" fallback — unlike
      `ReviewResponse`/`ReviewDetailResponse`'s `resolve_owner_info` (single definition,
      `get_full_name() if ... else None`). So a reply's `owner_info.full_name` can be `""`
      (never `null`) when the chef has no first/last name set, while a review's own
      `owner_info.full_name` would be `null` in the same situation. Ported exactly as the two
      different, real behaviors they are (`ReviewOwnerInfo.of` vs `.ofReply`), not unified into
      one "obviously correct" helper. Not independently regression-tested with an empty-name
      fixture (low stakes, no FE-admin consumer) but the two code paths are visibly different in
      `ReviewOwnerInfo`'s source/javadoc.
- [ ] **`review` — chef-analytics `menu_uid`/`categories` filters not ported (scope reduction).**
      Django's `FilterIssueReviewSchema` (used by the `/chef/analytics/*` and
      `/chef/issue-reviews` endpoints) supports `dish_uid`/`search` (ported, applied in SQL) and
      `menu_uid`/`categories` (list/join-shaped filters — NOT ported). `menu_uid` would need a
      cross-module join into `menu`'s `MenuDish`; skipped because this whole analytics surface
      has zero known consumers (FE-admin doesn't call any `review` endpoint) and the task's
      priorities (create/update/delete/reply, AI handling, the dish/order stats seam, tests) are
      all fully done without it. `ReviewAnalyticsController.chefIssueReviews` still accepts
      `menu_uid`/`categories` as query params (so the contract shape matches Django's), but
      `menu_uid` is silently ignored and `categories` is applied as a post-fetch Java filter
      instead of in SQL. Revisit if/when this analytics surface gets a real consumer.
- [ ] **`review` — `DishNotOrderedException` is dead code, same class as `ingredient`'s
      `restore_ingredient`/`cart`'s `CartNotFoundException`.** Grepped the whole Django tree:
      `review/services/__init__.py` imports it but never raises it — `create_review` actually
      raises `exceptions.dishes.DishNotFoundInOrderException` (via `OrderService.check_dish_in_order`)
      for "dish not in this order", a real, correctly-coded 404. The only real caller of
      `DishNotOrderedException` anywhere in the repo is `recommendation` (not ported). Ported the
      class for completeness (§4) but `ReviewService` never throws it — regression-tested
      indirectly (`ReviewServiceTest.createReview_dishNotInOrder_throwsDishNotFoundInOrderException`
      asserts the REAL exception, not this dead one).

## Session log

- 2026-09-22 — Bootstrapped Maven/Spring Boot 4.0.8 project via Spring
  Initializr (start.spring.io), fixed `.RELEASE` suffix issue (Boot versions
  dropped that suffix). Read Django `ARCHITECTURE_OVERVIEW.md`, `settings.py`,
  `utils/router/api.py`+`exception.py`, `users/tokens.py`, `users/models.py`,
  all `exceptions/*.py` to establish response-envelope/exception/auth
  conventions, written to `CLAUDE.md`.
- 2026-09-22 — User decisions: (1) fresh/clean DB schema via Flyway, no need
  to mirror Django's exact table/column names — cuts a lot of schema
  archaeology work; (2) drop `mongo_chat` entirely, port only `chat` (SQL) —
  no MongoDB/Firebase in this rewrite. `CLAUDE.md` updated accordingly.
- 2026-09-22 — Built and verified the foundation end-to-end: `common`
  (response envelope + global exception handling), `security` (JWT via
  jjwt, Argon2 password hashing, hybrid Redis+DB refresh tokens, Spring
  Security filter chain), `users` core auth (register/login/refresh/logout).
  Hit and fixed several real Spring Boot 4-specific issues along the way
  (all documented in CLAUDE.md §1/§3/§8b so they aren't rediscovered per
  module): Boot 4's dropped `.RELEASE` version suffix, renamed
  `-aop`→`-aspectj` and `-web`→`-webmvc` starters, `spring-retry` not in the
  BOM, Flyway needing its own starter now, Jackson 3 (`tools.jackson.*`)
  replacing Jackson 2 as the default, `Argon2PasswordEncoder` needing
  BouncyCastle explicitly, a real response-envelope double-wrap bug, and a
  real transaction-boundary bug in `RefreshTokenService`. Verified via
  Testcontainers (real Postgres+Redis, Docker) — `./mvnw test`: 4/4 passing.
  Next: `attachment` → `ingredient` → `dish` (see port order in §7),
  dispatching a subagent per module per the checklist in §8, model chosen by
  each module's complexity tier.
- 2026-09-22 — Ported `attachment` (subagent). Read
  `../backend/attachment/{models,queries,services,utils,api}.py`,
  `schemas/{requests,responses}.py`, `../backend/exceptions/attachments.py`,
  and grepped all of `../backend` for cross-app usage — confirmed dish,
  ingredient, certificate, profile, review, recommendation, verification all
  either hold a nullable FK to a completed `Attachment` or call
  `AttachmentService.handle_attachment(uid)` before linking one; none of that
  requires those modules to exist yet. Built: `Attachment` entity +
  `AttachmentType` enum (`attachment/entity/`), `AttachmentRepository`,
  `AttachmentService` (generatePresignedUrl / completedUpload /
  handleAttachment / uploadFileDirectly — the `post_file` equivalent, kept
  for a future `ingredient` port to reuse, not wired to any controller, same
  as Django), `AttachmentController` (`POST /api/attachments/presigned-url`,
  `PUT /api/attachments/{uid}/completed[/{instanceUid}]` — paths/methods
  cross-checked against `FE-admin/src/services/attachmentService.js`, and
  request/response DTOs use `@JsonProperty` snake_case
  (`file_name`/`file_size`/`attachment_type`/`uid`/`url`) to match FE-admin
  exactly, unlike the `users` module's camelCase, which is unverified against
  FE-admin — see that module's 🚩 note), three exception classes 1:1 with
  `exceptions/attachments.py` (`AttachmentNotFoundException`,
  `AttachmentAlreadyCompletedException`, `AttachmentIsNotCompletedException`,
  including the double message+detail quirk in `handle_attachment`'s Vietnamese
  string), `V2__init_attachment.sql` Flyway migration (owner/updater FKs to
  `app_user` with `ON DELETE SET NULL`, matching Django's `on_delete=SET_NULL`).
  **Storage decision (flagged — see "Open questions" above)**: implemented
  local filesystem storage (`AttachmentStorageService` interface +
  `LocalAttachmentStorageService`) as the default, per task instructions,
  even though Django's actual `AttachmentService.__init__` hard-requires S3
  today (`raise RuntimeError` if `USE_S3` is false) — ported the *intent* of
  Django's dormant non-S3 settings branch, not Django's current hard-S3
  behavior; this is a deliberate, explicitly-flagged deviation. Added a new
  `PUT /api/attachments/{uid}/upload?token=...` endpoint (no Django
  equivalent) as the local substitute for "PUT straight to S3 via a
  presigned URL", secured by a per-attachment opaque token
  (`attachment.upload_token` column, no Django equivalent) instead of a
  bearer token — mirrors how an S3 presigned URL's signature is itself the
  credential; endpoint is `permitAll` in `SecurityConfig` (added
  `/api/attachments/*/upload` and `/media/**` to the existing permit list —
  `security` module's config, touched only to add these two path patterns,
  same pattern already used for the payment webhook paths). Added
  `WebConfig` (`config/` package, new) to serve local files at `/media/**`,
  and `app.storage.*` properties (`backend`/`local-root`/`public-base-url`/
  `upload-base-url`) to `application.yml`, with `backend` as the documented
  (not yet wired) switch point for a future S3 implementation. Dish-type
  attachment linking in `completedUpload`'s `instanceUid` param is stubbed
  with a `PORT-NOTE` + log warning (dish module isn't ported yet — attachment
  ports first per §7's recommended order); every other Django code path in
  the upload lifecycle is implemented. Fixed one real bug found via testing:
  the upload URL was being built with a null uid (uid was left to
  `@PrePersist`, but the URL is built and returned before `save()` runs) —
  fixed by assigning `UUID.randomUUID()` explicitly before building the
  upload URL. Tests: `AttachmentServiceTest` (10 unit tests, Mockito, no
  Testcontainers) + `AttachmentControllerTest` (3 full-stack tests, MockMvc +
  `TestcontainersConfiguration`, real Postgres + real local-disk storage
  under a JUnit `@TempDir`) covering the full presigned-url → upload →
  completed lifecycle and all three attachment exceptions plus the new
  invalid-upload-token case. `./mvnw -q compile` and `./mvnw test` both
  green: **17/17 tests passing** (10 new unit + 3 new full-stack + 4
  pre-existing from `users`/root), confirmed via `target/surefire-reports/`
  (not just exit code). Marked 🚩 rather than ✅ solely because of the
  storage-backend decision above needing human sign-off — code itself is
  complete and fully tested, nothing left half-done.
- 2026-09-22 — Ported `ingredient` (subagent). Read
  `../backend/ingredient/{models.py,orm/ingredient.py,services/__init__.py,
  api.py,constants.py}`, `schemas/{requests,responses}.py`,
  `../backend/exceptions/ingredient.py`, and grepped all of `../backend` for
  cross-app usage — confirmed `dish` (heaviest consumer: DishIngredient FK +
  nutrition-scaling on suggestion approval), `recommendation`
  (FavouriteIngredient/AllergicIngredient signals + Ingredient nutrition
  fields for daily-nutrition tracking), and `profile` (mirrors favourite/
  allergic rows) all only need read access to fields this port already
  exposes (`user_id`/`ingredient_uid`/`deleted` on the preference tables,
  the full nutrition field set on `Ingredient`) — none of that blocks their
  future ports. Built: 5 entities (`Ingredient`, `IngredientAlias`,
  `IngredientSuggestion`, `FavouriteIngredient`, `AllergicIngredient`) +
  3 enums (`IngredientCategory`, `IngredientSource`, `IngredientImportStatus`)
  in `ingredient/entity/`, 5 Spring Data repositories (`JpaSpecificationExecutor`
  for the two list/filter endpoints, plus hand-written `@Query` methods
  mirroring `IngredientORM.find_suggestion_candidates`'s exact/prefix stages),
  service layer split to mirror Django's own class boundaries
  (`IngredientService` = query+command CRUD/search/alias, faithfully
  translating `find_suggestion_candidates`/`autocomplete_ingredients`'s
  multi-stage scoring cascades; `IngredientSuggestionService` = moderation
  queue; `IngredientImportExportService` = Apache POI Excel import/export;
  `IngredientPreferenceService` = favourites/allergies), 10 exception classes
  1:1 with `exceptions/ingredient.py` (including the currently-dead-code
  `IngredientAliasNotFoundException` and dish-owned `NutritionValidationException`,
  both ported anyway per CLAUDE.md §4's "one class per Django exception"
  rule), full request/response DTO set with `@JsonProperty` snake_case
  matching `FE-admin/src/services/ingredientService.js` and
  `utils/constants.js::API_ENDPOINTS.INGREDIENTS` exactly, `IngredientController`
  (26 endpoints, paths/methods cross-checked against FE-admin), and
  `V3__init_ingredient.sql` (5 tables, FK cascade behavior mirroring Django's
  `on_delete=` choices field-for-field). Added `org.apache.poi:poi-ooxml:5.5.1`
  to `pom.xml` (checked Maven Central for the current release per CLAUDE.md
  §1) for the Excel import/export feature (`IMPORT_COLUMNS`, header
  validation, per-row error collection, duplicate-name detection, category
  dropdown data validation on the exported template — all ported column-for-
  column from the openpyxl-based Python code).
  **Deliberate scope/porting decisions, all flagged in "Open questions"
  above**: (1) ported Django's `SequenceMatcher`-based fuzzy-search fallback
  algorithm faithfully (wrote a Java Ratcliff/Obershelp `SequenceMatcher`
  port, verified against real CPython `difflib` output via `../backend/venv`
  for exact parity) and always use it, rather than taking a hard dependency
  on the Postgres `pg_trgm` extension for the trigram branch Django only
  takes when `connection.vendor == "postgresql"`; (2) ADMIN/CHEF role checks
  stand in with `isStaff`/no-check pending `users` getting real group/RBAC
  support; (3) `IngredientSuggestion` approve/reject flows are simplified
  pending `dish`; (4) preserved (did not fix) a real Django bug where
  `restore_ingredient` can never succeed. Also fixed one real bug caught by
  testing in this port's OWN new code, not Django's: the `/export-template`
  endpoint initially returned `ResponseEntity<byte[]>`, which
  `ResponseEnvelopeAdvice.supports()` (common module, not touched) fails to
  recognize as raw bytes because of the same generic-erasure trap CLAUDE.md
  §3 documents for `ApiResponse<T>` — it wrapped the Excel bytes in the JSON
  envelope and corrupted the file (500 `ClassCastException` at write time).
  Fixed by returning a plain `byte[]` and setting headers on the injected
  `HttpServletResponse` directly instead — no common-module changes needed.
  Also caught a real Spring Data pitfall while wiring the alias-active
  derived-query method: `findAllByIsActiveTrue...` doesn't resolve (Spring
  Data derives the JavaBeans property name from the `isActive()`/`setActive()`
  accessor pair as `active`, not `isActive`) — renamed to
  `findAllByActiveTrue...`. Tests: `IngredientServiceTest` (8 unit tests,
  Mockito — CRUD, duplicate-name/not-found/referenced/not-deleted error
  paths, and a regression test proving the preserved `restore_ingredient`
  bug's two dead-end paths), `SequenceMatcherTest` (5 tests verifying exact
  parity with CPython `difflib` reference values) and `RemoveAccentsTest`
  (2 tests), plus `IngredientControllerTest` (8 full-stack MockMvc +
  Testcontainers tests: admin CRUD + non-admin-401, duplicate name, alias
  create/list + referenced-ingredient soft-delete block, favourite/allergy
  lifecycle, full suggestion moderation lifecycle create→list→approve-alias→
  reject→not-pending-delete-block, raw-bytes export-template, and Excel
  import happy-path + validation-error rows using a fixture built in-test
  with POI's writer API). `./mvnw -q compile` and `./mvnw -q test` both
  green: **41/41 tests passing** (23 new: 8+2+5 unit + 8 full-stack; 18
  pre-existing from `common`/`security`/`users`/`attachment`/root), confirmed
  via `target/surefire-reports/*.txt` "Tests run" counts, not just exit code.
  Marked 🚩 (not ✅) for the three scope decisions above needing human
  sign-off — code itself is complete and tested, nothing left half-done.
  Did not touch any `attachment`/`users`/`security`/`common` files.
- 2026-09-22 — `attachment` follow-up: user reviewed the local-vs-S3 storage
  flag and chose **real S3, as the default**. Added `software.amazon.awssdk:s3`
  (checked Maven Central for the current stable release: 2.55.2, imported via
  the `software.amazon.awssdk:bom` in a new `<dependencyManagement>` block in
  `pom.xml`) and built `S3AttachmentStorageService` (new
  `AttachmentStorageService` implementation): `S3Presigner.presignPutObject`
  for the upload URL (mirrors Django's `generate_presigned_url(ClientMethod=
  "put_object", ExpiresIn=S3_EXPIRES_IN, HttpMethod="PUT")`), `S3Client
  .headObject` for the completed-upload existence check (mirrors
  `head_object`/`NoSuchKeyException` handling — S3 HeadObject actually
  returns a bare 404 `S3Exception`, not `NoSuchKeyException`, in practice;
  handled both), `S3Client.putObject` for the direct-upload path. Config keys
  under `app.storage.*` mirror Django's `S3_*` env var names exactly
  (`s3-access-key-id`/`s3-secret-access-key`/`s3-bucket-name`/`s3-region`/
  `s3-public-url`/`s3-expires-in` ← `S3_ACCESS_KEY_ID`/etc.). Exactly one
  `AttachmentStorageService` bean is active at a time via
  `@ConditionalOnProperty(prefix="app.storage", name="backend", ...)`:
  `S3AttachmentStorageService` has `havingValue="s3", matchIfMissing=true`
  (the new default), `LocalAttachmentStorageService` has `havingValue=
  "local"` (kept, not deleted — a real dev/demo fallback costing nothing
  behind the shared interface); `WebConfig`'s `/media/**` handler is now
  also gated the same way (irrelevant under S3, which serves files straight
  from the bucket). Extended the `AttachmentStorageService` interface with
  `bucketName()` (so `AttachmentService` can record the right value on
  `Attachment.bucket` — the configured S3 bucket name under the default
  backend, matching Django setting `attachment.bucket = S3_BUCKET_NAME`) and
  widened `storeFile` to take a `contentLength` (S3's `PutObject` needs a
  known Content-Length up front, unlike the local filesystem write).
  `s3-endpoint-override`/path-style addressing has no Django equivalent —
  added purely so the same class can point at LocalStack in tests.
  Testing (no real AWS credentials used or needed): added
  `org.testcontainers:testcontainers-localstack` (version 2.0.5, matching
  the Testcontainers BOM version already pulled in by
  `spring-boot-testcontainers`) and wrote `AttachmentS3ControllerTest` — a
  real LocalStack container, a real presigned S3 PUT URL, an actual
  `java.net.http.HttpClient` PUT of raw bytes straight to that URL (bypassing
  the app entirely, like a real browser-to-S3 upload), a direct S3
  `listObjectsV2`/`headObject` check confirming the object landed in the
  LocalStack bucket, then the full `completed`/`already-completed` lifecycle
  through the app's own controller. Also updated the existing
  `AttachmentControllerTest` to explicitly set `app.storage.backend=local`
  (since `s3` is now the default, that test needed to keep forcing the
  local-storage code path it's actually testing), and `AttachmentServiceTest`
  to stub the new `bucketName()` method and the widened `storeFile` signature.
  Caught and fixed one real bug during this pass: `S3AttachmentStorageService`'s
  constructor originally called `forcePathStyle(true)` on the S3 client
  builder AND `serviceConfiguration(S3Configuration.pathStyleAccessEnabled
  (true))` on both builders — AWS SDK v2 throws
  `IllegalStateException: ForcePathStyle has been configured on both
  S3Configuration and the client/global level` if both are set; fixed by
  using `forcePathStyle` on the client builder only and `serviceConfiguration`
  on the presigner builder only (the presigner has no direct
  `forcePathStyle` method). Also hit a real concurrency hazard worth noting
  for future sessions: this port ran in parallel with the `ingredient`
  module's subagent, whose in-progress, uncommitted intermediate states
  (missing Flyway migration, then a Spring Data derived-query bug) transiently
  broke full-context tests project-wide (`AuthControllerTest`,
  `MarketplaceApplicationTests`) through no fault of this module — resolved
  itself once that module's own work finished; did not touch any
  `ingredient` files to work around it, just waited and re-ran. Final
  verification: `./mvnw -q compile` clean; `./mvnw test` — **41/41 tests
  passing project-wide** (14 in `attachment`: 10 unit + 3 full-stack local +
  1 full-stack S3/LocalStack; the rest pre-existing from
  `common`/`security`/`users`/`ingredient`/root), confirmed via
  `target/surefire-reports/*.txt`, run twice in a row to rule out flakiness.
  `attachment` moved from 🚩 to ✅ — no open items remain for this module.
- 2026-09-22 — Ported `dish` (subagent, Opus 5 — the first "complex" tier module).
  Read all of `../backend/dish/`: `models.py`, `orm/dish.py` (670 lines),
  `services/__init__.py` (1534 lines), `services/search.py`,
  `services/stock_reservation.py`, `services/validation.py`,
  `constants/ingredient_bounds.py`, `schemas/{requests,responses}.py`, `api.py`,
  `signals.py`, `tasks.py`, `management/commands/rebuild_stock_redis.py`, plus
  `exceptions/{dishes,nutrition}.py`, `marketplace/celery.py` (beat schedule) and
  `marketplace/settings.py` (`STOCK_REDIS_URL`, `STOCK_HOLD_TTL_SECONDS`), and
  grepped all of `../backend` for cross-app usage (cart, menu, order, payment,
  profile, recommendation, report, review and `attachment`/`ingredient` all
  import dish's models/ORM/services — noted per consumer, none of it blocks this
  port).
  **Built**: 6 entities + 7 enums (`Dish`, `DishLocation`, `DishIngredient`,
  `DishAvailability`, `DishAlias`, `StockReservation`), 6 repositories,
  `V4__init_dish.sql` (re-checked the max V-number right before writing it — V3
  was still the max), full request/response DTO set with snake_case
  `@JsonProperty` matching `FE-admin/src/utils/constants.js` + `dishService.js` +
  `dishLocationService.js`, 12 exception classes (9 from `exceptions/dishes.py`,
  3 from `exceptions/nutrition.py` — the latter live in `dish/exception/` since
  that file has no Django app of its own and only dish raises them), and three
  controllers (`/api/dishes`, `/api/dishingredients`, `/api/dish-locations`, 34
  endpoints, paths cross-checked against FE-admin).
  **Service layer** (split by Django's own seams, logic 1:1):
  `DishNutritionService` (the whole compute/analyze/confidence pipeline —
  `_ratio_severity`, `_compute_macro_severity`, `_compute_energy_severity`,
  `_compute_outlier_severity`, `_compute_similarity_to_usda_entry`,
  `_compute_completeness_score`, `_compute_data_quality_score`,
  `compute_dish_confidence`, `smart_round`, `build_response_customer/chef`),
  `NutritionValidation` + `IngredientBounds` (the 3 layers and every literal
  threshold), `DishService` (CRUD/search-filter/availability/ingredient
  composition/suggestions), `DishSearchService`, `DishLocationService` (incl. a
  port of `django.utils.text.slugify` and the REGION→SUBREGION→COUNTRY
  `clean()` rules), `DishInventoryService` (`reduce/increase_quantity` with real
  `SELECT … FOR UPDATE`), `StockReservationService`, `StockHoldSweepScheduler`,
  `DishSuggestionCandidateFinder`, `TopDishRanking`.
  **Stock reservation** is the centerpiece and was ported line-by-line, docstring
  rationale included: Redis holds ONLY an atomic per-(dish,date) counter driven
  by a verbatim copy of Django's `_RESERVE_SCRIPT` Lua (seed-if-absent +
  check-and-decrement), lazily seeded from
  `DishAvailability − Σ(RESERVED StockReservation)` so it self-heals after a
  Redis wipe; Postgres holds the durable ledger. Every state transition is a
  conditional `UPDATE … WHERE status = <expected>` (`transitionStatus`), which is
  what makes `confirm()`/`release()` idempotent by construction, and `confirm()`
  returns a boolean the caller MUST check to tell "harmless retry" from "the hold
  expired and nothing was deducted". The `expired=true` vs `expired=false`
  asymmetry in `release()` (the sweep must back off silently when `confirm()` won
  the race, per `ORDER_INVENTORY_FLOW.md` §5.5, instead of refunding a real sale)
  is ported and has its own test. `reserve()` compensates the Redis decrement if
  the durable write fails, and uses `saveAndFlush` so a constraint violation
  surfaces inside that compensating try-block rather than at commit.
  **Redis DB separation decided and recorded** (CLAUDE.md §6 asked for one
  consistent answer): a dedicated `LettuceConnectionFactory` per logical DB index.
  `StockRedisClient` owns its factory as an internal field rather than exposing it
  as a `@Bean`, because Boot's Redis autoconfiguration is
  `@ConditionalOnMissingBean(RedisConnectionFactory.class)` — a second factory
  bean would have silently switched off the auto-configured one and broken
  `security.RefreshTokenService`. Host/port come from the
  `DataRedisConnectionDetails` bean, not `spring.data.redis.*` properties, because
  Testcontainers' `@ServiceConnection` contributes a bean and never sets those
  properties (reading the properties would have pointed the stock counters at
  localhost during tests — caught before it shipped). Added
  `app.stock.{redis-database,sweep-enabled,sweep-batch-size}` to
  `application.yml` alongside the existing `hold-ttl-seconds`.
  **Celery → Spring**: `release_expired_stock_holds` is now
  `StockHoldSweepScheduler.releaseExpiredStockHolds()`, `@Scheduled(fixedDelayString
  = "PT30S")` — interval confirmed from `marketplace/celery.py`'s
  `beat_schedule` (30.0 s) and asserted reflectively in a test so a drift cannot
  go unnoticed. `fixedDelay` not `fixedRate` so a slow tick cannot stack sweeps.
  **Bayesian "top dishes"** ported exactly (`m = 50.0`,
  `score = n/(n+m)·avg_rating + m/(n+m)·system_avg`, ordered
  `-score, -sold_count, -review_count, name`) as a pure, separately-tested
  function.
  **Two real bugs caught by writing the tests** (both in this port's own new
  code, not Django's): (1) `@ConditionalOnMissingBean` on the three
  component-scanned default seam beans never fires — it is only evaluated for
  auto-configuration `@Bean` methods — which blew up the whole application
  context with `NoSuchBeanDefinitionException: DishUserContext`; replaced with
  plain `@Component`s that a future module displaces via `@Primary`, and
  documented in each javadoc. (2) `NutritionValidation`/`PortionWeightOutOfBounds`
  originally rounded with `Math.round` (half-up) where Django uses Python's
  `round()` (half-to-EVEN) — switched both to the shared
  `DishNutritionService.pyRound`, which every confidence/nutrition value now goes
  through.
  **Found, but deliberately NOT fixed — flagged in "Open questions" as the
  highest-priority item**: the shared `common` response envelope serializes
  `messageCode`/`errorCode`/`currentTime` (plain record component names) where
  Django and FE-admin both use `message_code`/`error_code`/`current_time`.
  FE-admin reads the snake_case keys on dish screens specifically
  (`DishForm.jsx`, `Dishes.jsx`, `AdminDishLocations.jsx`) plus auth/ingredient
  services, so those comparisons silently evaluate against `undefined` today.
  `common/` was out of scope for this task and the fix touches every module's
  tests, so `DishControllerTest` pins the CURRENT behavior with an inline comment
  instead.
  **Django bugs preserved verbatim** (all PORT-NOTE'd and regression-tested):
  the `DishPermissionDenied`/`DishIsNotDeleted` message mix-up, `soft_delete_dish`
  never raising `DishIsReferenced`, `add_ingredient_to_dish` never raising
  `DishIngredientAlreadyExists` (silently returning the existing row without
  persisting the recomputed nutrition), the commented-out search fuzzy threshold
  (so every live dish enters the candidate set), the exact-alias search stage
  missing its `deleted=False` guard, nutrition Layers 2/3 being defined but called
  from nowhere, and the running-max `severity["ratio"] > 0.4` check sitting inside
  the per-field loop (so one bad field makes every later field emit a warning too).
  **Tests: 83 new, `*Test.java` throughout.** `DishNutritionServiceTest` (18) and
  `NutritionValidationTest` (9) assert against values computed by running the
  original Python formulas, not this port's output — severity curves, the
  completeness/quality/confidence product, `pyRound`'s half-to-even behavior, the
  full pipeline end-to-end (0.763 for a clean USDA match, 0.338 for a custom
  ingredient), all Layer-1 category bounds, Layer-3 skip-on-missing-data and the
  per-serving divisor. `TopDishRankingTest` (6) checks the Bayesian formula
  against hand-computed values including the m-is-the-halfway-point and
  shrinkage-beats-a-1-review-5-star cases. `StockReservationServiceTest` (15) runs
  against **real Redis + real Postgres** (no mocks): lazy seeding, reseeding after
  a wipe, the reopen-terminal/refuse-active rule, confirm idempotence,
  release-before/after-confirm, the sweep-vs-confirm race, the expiry sweep, TTL
  handling, `rebuildRedisCounters`, and a **40-thread contention test against
  capacity 10** proving exactly 10 succeed, 30 are rejected, the counter lands on
  0 and the ledger holds exactly 10 rows — i.e. no oversell.
  `StockHoldSweepSchedulerTest` (6) pins the 30 s interval and the batch size.
  `DishServiceTest` (9, Mockito) covers ownership and the preserved quirks.
  `DishControllerTest` (20, MockMvc + Testcontainers) covers the full CRUD /
  availability / ingredient-composition / suggestion / search / top-dishes /
  location-hierarchy surface plus the error paths (404 DISH_NOT_FOUND, 403
  DISH_PERMISSION_DENIED, 400 DISH_NOT_DELETED, 422
  INGREDIENT_WEIGHT_OUT_OF_BOUNDS with its detail payload, 400
  DISH_LOCATION_HAS_CHILDREN, 500 on the hierarchy/COUNTRY `ValidationError`
  paths, 401 for non-staff and anonymous).
  `./mvnw -q clean compile` clean; `./mvnw test` — **124/124 passing
  project-wide** (83 new + 41 pre-existing from
  `common`/`security`/`users`/`attachment`/`ingredient`/root), confirmed via
  `target/surefire-reports/*.txt` "Tests run" counts and run twice in a row to
  rule out flakiness in the concurrency test. Marked 🚩 (not ✅) for the open
  questions above — the envelope bug especially needs a human decision — but the
  module itself is complete: nothing was left half-done or stubbed beyond the
  three documented cross-module seams. Did not touch any
  `attachment`/`ingredient`/`users`/`security`/`common` source file.
- 2026-09-22 — Fixed the camelCase/snake_case envelope bug the `dish` port
  found (orchestrator, immediately after review — this was a real,
  live cross-module bug, not just a flag to leave for later). Verified the
  exact expected keys against `FE-admin/src/services/authService.js` (reads
  `message_code`/`data.access_token`/`data.refresh_token` directly, and
  *sends* `refresh_token` in request bodies too) before touching anything.
  Root cause: `common/response/ApiResponse.java` and
  `users/dto/TokenPairResponse.java` had no naming override, so Jackson's
  default (matching Java field names) leaked into the wire format. Fix:
  `spring.jackson.property-naming-strategy: SNAKE_CASE` globally in
  `application.yml` — one line, applies to every module automatically going
  forward, both serialization and deserialization. Updated stale test
  assertions/fixtures across `users`, `attachment` (x2 test classes),
  `ingredient`, and `dish` (`DishControllerTest`'s pinned-bug comment/
  assertions, request-body `phoneNumber`→`phone_number`, and every
  controller test's token-extraction helper `"accessToken"`→
  `"access_token"`) — several had been silently asserting their own wrong
  output rather than the real contract. Full suite re-verified: **124/124
  passing**, confirmed via `target/surefire-reports/*.txt`. CLAUDE.md §3
  updated with the fix and a note that `@JsonProperty` is no longer needed
  for plain camelCase→snake_case mapping. `dish`'s open question resolved
  (marked `[x]`); its other flags (seam interfaces, ADMIN/CHEF stand-in)
  remain open, tracked separately below.
- 2026-09-22 — **`users` second pass: real roles/permissions + OTP flows, and the
  ADMIN/CHEF stand-in removed from `ingredient` and `dish`** (subagent, Opus 5).
  Started by investigating the authorization model rather than assuming it — the
  full write-up is the "Part 1 findings" section above, and the headline is that
  the previous stand-in was wrong in kind, not just in degree: **ADMIN/CHEF/CUSTOMER
  are Django auth Group memberships**, and `is_staff` appears in exactly two
  authorization lines in the entire Django tree (an OR fallback in
  `voucher/services`). CHEF is *not* `ChefProfile`-row existence — the profile row
  is created in the same transaction as the group change, but nothing reads it for
  authorization, including `/api/auth/is-chef` itself. There is also a fourth,
  genuinely live mechanism: Django's Permission system, via
  `require_permission`/`require_object_permission` → `user.has_perm(...)`, used 22×
  across `dish/api.py` and `menu/api.py`. `auth_permission.json` at the repo root is
  referenced by no code/fixture/script anywhere (only a `.VSCodeCounter` line-count
  entry) — an unused export, but an accurate ID→codename map for the hardcoded IDs
  in `setup_permissions.py`, which is how the real matrix was recovered.
  **Built (users)**: `UserRole` enum + `CustomUser.roles` as an EAGER
  `@ElementCollection` over the new `app_user_role` join table (EAGER deliberately —
  `JwtAuthenticationFilter` loads the user outside any transaction and needs the
  roles immediately); `RolePermissions`, the Group→Permission matrix as a code
  constant including Django `ModelBackend`'s inactive-has-nothing and
  superuser-has-everything rules; `UserOtp`/`OtpPurpose` + `V5__users_roles_and_otp.sql`
  (with a backfill so existing rows get CUSTOMER, and ADMIN for anyone carrying the
  old `is_staff` stand-in); `OtpService` (Argon2 params copied exactly from Django's
  `PasswordHasher(time_cost=2, memory_cost=65536, parallelism=1, hash_len=32,
  salt_len=16)` — with a 4-digit code the hash cost *is* the security control);
  `AuthEmailService`; `CustomerOnboardingProvider` seam; 4 new exceptions; 12 new
  DTOs; and 10 new endpoints on `AuthController` (`/signup`, `/verify-otp`,
  `/password/forget`, `/password/reset`, `/password/change`, `/me` GET+PUT,
  `/email-change/request`, `/email-change/verify`, `/is-chef`), plus `/logout`
  corrected to Django's `PUT` + optional body + auth-required (FE-admin calls
  `api.put('/api/auth/logout')` with no body at all). `SecurityConfig`'s blanket
  `/api/auth/**` permitAll was replaced by the enumerated public set, because Django
  declares the controller `auth=None` and opts individual endpoints back in.
  **Fixed in `ingredient`/`dish`**: `hasAuthority('ROLE_STAFF')` → `hasRole('ADMIN')`
  everywhere (19 endpoints), `hasRole('CHEF')` added to the 8 endpoints Django gates
  with `@require_group(CHEF)` and this port had left open to any authenticated user,
  and `DishService.assertCanModify` upgraded from "owner or isStaff" to the full
  `@require_object_permission` semantics (`has_perm('dish.change_dish')` **then**
  owner-or-ADMIN). Both modules' fixtures moved off the `isStaff` flip onto real role
  assignment.
  **Two real bugs caught, both by writing the tests, both in this port's own new
  code**: (1) the OTP **lockout did not work at all** — Django increments `attempts`
  and then raises, which is durable under autocommit, but in Spring the save joined
  the caller's `@Transactional` and the `InvalidOtpException` rolled it straight back,
  giving unlimited guesses at a 4-digit code. Fixed with `OtpAttemptRecorder`, a
  separate bean with `REQUIRES_NEW` (separate *bean* because a `@Transactional` method
  called from inside the same class is self-invocation and never hits the proxy) —
  this is a sharper instance of the CLAUDE.md §8b lesson: it is not enough to own a
  transaction, a write-then-throw needs its *own* one. (2) `verify_otp`'s
  already-verified branch is unreachable (verification also clears `active`, and the
  lookup filters `active=True`) — ported anyway and tested by handing the branch a
  record directly.
  **Django bugs found and preserved/flagged** (all in "Open questions"): the
  password-RESET flow is structurally dead for the same reason as
  `restore_ingredient` (flagged as the one worth fixing soon — FE-admin has a full
  forgot-password UI that can never succeed); `verify_email_change` compares against a
  field that does not exist, so it 500s in Django and is ported by intent instead;
  `/api/auth/is-chef` always returns `chef_id: null` because it guards on a
  `related_name` no model declares; `update_me` dumps the whole `full_name` into
  `first_name`; `SignUpSchema.password_confirm` is never compared to anything;
  `Query.create_user` derives a UNIQUE username from the email local part, so
  `a@x.com` and `a@y.com` collide.
  **Tests: 31 new/changed, `*Test.java` throughout.** `OtpServiceTest` (10, Mockito —
  4-digit generation and hash-not-stored, single-use consumption, wrong-code attempt
  bookkeeping, the 3-attempt lockout including the right-code-no-longer-helps 4th
  call, expiry per purpose against `OTP_EXPIRY_MINUTES`, the unreachable
  already-verified branch); `RolePermissionsTest` (7 — the CHEF-can-change /
  CUSTOMER-can-only-view asymmetry `dish` depends on, ADMIN's real list including its
  missing attachment/profile permissions, union across roles, inactive→nothing,
  superuser→everything, and `primaryRole`'s no-groups-means-ADMIN quirk);
  `AuthOtpControllerTest` (8, MockMvc + Testcontainers — full signup→inactive→
  403 ACCOUNT_DEACTIVATED→verify→active+CUSTOMER→login, re-signup reuse, 409 on an
  active email, the lockout end to end, forgot-password incl. 404 and the 400
  confirm-mismatch, the pinned password-reset dead end, `/me` GET+PUT, password
  change, the email-change round trip incl. 409, `/is-chef` both ways);
  `RoleAuthorizationControllerTest` (4 — the cross-module ADMIN/CHEF/CUSTOMER matrix
  over `ingredient` and `dish`, explicitly asserting that the previously-open CHEF
  endpoints now reject both a CUSTOMER and an ADMIN). `DishServiceTest` gained 2
  (CUSTOMER denied on a dish it owns; inactive owner denied) and its fixtures moved to
  roles. The plaintext OTP is never returned by any endpoint, so the full-stack tests
  mint it through the same `OtpService` the controller uses and then drive the real
  HTTP endpoint — only the delivery channel is short-circuited.
  `./mvnw -q compile` clean; `./mvnw test` — **155/155 passing project-wide**
  (124 pre-existing + 31), confirmed from `target/surefire-reports/*.txt` "Tests run"
  counts across all 20 test classes and run twice in a row. Did not touch
  `attachment`/`common` source; `security` touched only for the role authorities and
  the public-path split (steps explicitly in scope); `profile` not started — only the
  `CustomerOnboardingProvider` seam and the `upgradeCustomerToChef` role transition it
  will call.
- 2026-09-23 — Ported `menu` (subagent, Sonnet 5). Read
  `../backend/menu/{models.py,orm/menu.py,services/__init__.py,api.py}`,
  `schemas/{requests,responses}.py`, `../backend/exceptions/menus.py`, and
  grepped all of `../backend` for cross-app usage of `Menu`/`MenuDish` —
  confirmed this is a genuinely self-contained app (only `exceptions/menus.py`
  is referenced from outside `menu/`, by `menu` itself). Cross-checked
  endpoint paths/fields against `FE-admin/src/utils/constants.js`
  (`API_ENDPOINTS.MENUS.*`) and `FE-admin/src/services/menuService.js`.
  **Built**: `Menu` entity (`BaseModel`-equivalent: uid/created_at/updated_at)
  + `MenuStatus` enum, `MenuDish` entity (Django declares it as a plain
  `models.Model`, NOT a `BaseModel` — ported with a default auto-increment
  `Long id`, not a `uid`, unlike every other entity so far), two repositories,
  `MenuService` (full CRUD + Menu↔Dish lifecycle), 4 request/response DTOs,
  `MenuController` (16 endpoints), 4 exception classes 1:1 with
  `exceptions/menus.py`, and `V6__init_menu.sql` (re-checked the max V-number
  immediately before writing it — V5 was still the max, confirmed again right
  before this session's `./mvnw test` run in case something else landed one
  concurrently — nothing had). **Reused `dish`'s already-ported `Dish` entity
  and `DishRepository` for the Menu↔Dish relationship** (not redeclared, per
  task instructions) and `dish`'s own `DishResponse.of(...)` factory for the
  customer-facing "dishes in menu" listing.
  **Role gating**: `create_new_menu`/`get_all_my_menus`/
  `get_all_dishes_in_menu_for_chef` are `hasRole('CHEF')`; `hard_delete_menu`
  is `hasRole('ADMIN')` (Django's `require_permission('menu.delete_menu')` +
  `require_group(ADMIN)` collapse to "ADMIN only" since ADMIN's permission set
  already includes `menu.delete_menu` — same simplification `dish`'s
  `hardDeleteDish` already uses). `RolePermissions` already carried the
  `menu.*` entries from the `users` second pass, so no changes to that file
  were needed. The eight `@require_object_permission`-guarded endpoints
  (`update`/`soft-delete`/`restore`/`activate`/`deactivate`/`activate-dish`/
  `deactivate-dish`/`add-dish`) are implemented as two service-level helpers,
  `assertOwnerOnly`/`assertOwnerOrAdmin`, that collapse Django's
  decorator-level has_perm+deleted-filtered-lookup+owner-or-admin check with
  each service method's own redundant strict-ownership re-check — the same
  collapsing pattern `DishService.assertCanModify` already established for
  `dish`'s equivalent decorator. **Investigated closely rather than assumed**,
  because doing so surfaced three genuine, `menu`-specific quirks not present
  in `dish` (all PORT-NOTE'd, regression-tested, and written up in "Open
  questions" above): (1) `restore_menu` can never actually succeed (dead code,
  same class of bug as `ingredient`'s `restore_ingredient` — `menu`'s decorator
  omits the `check_deleted=False` `dish`'s `restore_dish` passes explicitly);
  (2) `get_menu`/`get_all_dishes_in_menu` ignore the `deleted` flag entirely,
  unlike the chef/public list endpoints, so a soft-deleted-but-still-ACTIVE
  menu stays visible through the two detail-style endpoints; (3)
  `add_dish_to_menu` is the only object-level endpoint whose Django service
  method never re-checks ownership at all, so it's the only one where an
  ADMIN who doesn't own the menu can still succeed — every sibling endpoint is
  strictly owner-only with NO ADMIN bypass, which is actually stricter than
  `dish` (whose `assertCanModify` lets ADMIN bypass ownership everywhere).
  Also preserved two smaller, lower-stakes quirks: `activate_dish_in_menu`/
  `deactivate_dish_in_menu` crash to an unhandled 500 (`MenuDish.DoesNotExist`
  uncaught in Django) when the dish was never linked to the menu, and neither
  that pair nor `add_dish_to_menu` filter `deleted=False` on the Dish side.
  **Tests: 41 new, `*Test.java` throughout.** `MenuServiceTest` (27, Mockito —
  every branch above plus the plain create/list/update/soft-delete/hard-delete
  paths) and `MenuControllerTest` (14, MockMvc + Testcontainers, registering
  through the real `/api/auth/register` endpoint and assigning roles via the
  repository exactly like `DishControllerTest`/`IngredientControllerTest`
  already do) — including explicit real-role-based-authorization coverage
  (a CUSTOMER-role token is rejected with 401 from every CHEF-only endpoint:
  create, `/mine`, `/all-dishes` — not merely tolerated as "any authenticated
  user", the mistake this project has now avoided a third time) and the
  ADMIN-succeeds-at-add-dish / ADMIN-denied-at-update asymmetry end to end
  over real HTTP. `./mvnw -q compile` and `./mvnw -q test-compile` both clean;
  `./mvnw test` — **196/196 passing project-wide** (41 new + 155
  pre-existing), confirmed via `target/surefire-reports/*.txt` "Tests run"
  counts across all 22 test classes, run twice in a row to rule out
  flakiness. Note for future sessions: this sandbox's Docker Desktop engine
  was not running at the start of this task (Testcontainers failed with
  "Could not find a valid Docker environment" on the first run) — started it
  and waited for the engine before the Testcontainers-backed tests would pass;
  worth checking first if a future full-stack test run fails the same way.
  Marked 🚩 (not ✅) solely because the three `menu`-specific quirks above
  need human sign-off on whether to keep them bug-compatible or fix them —
  the module itself is complete, nothing left half-done. Did not touch
  `attachment`/`ingredient`/`dish`/`users`/`security`/`common` source.
- 2026-09-23 — Ported `profile` (subagent, Sonnet 5). Read
  `../backend/profile/{models.py,orm/profile.py,orm/chef_payment.py,
  services/__init__.py,api.py,api_chef_payment.py,schemas/{requests,responses,
  chef_payment}.py}`, `../backend/exceptions/profiles.py`,
  `../backend/marketplace/urls.py` (to check how `api_chef_payment.py` is
  actually mounted — it isn't; see Open questions), `../backend/users/{api.py,
  services.py,schemas.py}` for `upgrade_to_chef`/`CustomerToChefSchema`, and
  `AuthService.upgradeCustomerToChef`'s javadoc in the already-ported `users`
  module (written specifically for this port to call). Grepped `dish`/
  `ingredient` for "profile"/"seam" cross-references to find every interface
  left open for this module.
  **Built**: 5 entities (`CustomerProfile`, `CustomerFavoriteDish`,
  `ChefProfile`, `ChefPaymentInfo`, `CustomerAddress`) + 4 new enums
  (`DietMode`, `DietLevel`, `ChefSuspensionLevel`, `VietnamBank` — reusing
  `dish.entity.AllergyMode` for `CustomerProfile.allergyMode` rather than
  redeclaring it, since `dish`'s own `DishUserContext` seam already speaks that
  type), 5 repositories, 3 services (`ProfileService` = chef profile,
  `CustomerService` = customer profile/addresses/favourites/onboarding,
  `ChefPaymentService` = bank info + all its Pydantic-validator-equivalent
  normalize/validate logic) + 2 small supporting beans
  (`CustomerAddressSelectionWriter` for the CLAUDE.md §8b write-then-throw
  transaction split, `UpgradeToChefService` for the upgrade flow), 4
  controllers (`ChefProfileController`, `CustomerProfileController`,
  `ChefPaymentController`, `UpgradeToChefController`), 6 exception classes (3
  from `exceptions/profiles.py`, `PermissionDeniedException` mirroring the
  shared `utils.exceptions.PermissionDeniedError` `menu` already duplicates
  per-module, `HttpBadRequestException` mirroring ninja's generic
  `HttpError(400, ...)` Django raises at two call sites, and
  `ChefPaymentValidationException` mirroring the 401 VALIDATION_ERROR shape
  Pydantic field_validator failures produce), and `V7__init_profile.sql`
  (re-checked the max V-number immediately before writing it and again right
  before the final test run — V6 was still the max both times, nothing else
  landed one concurrently). Reused `dish`'s `Dish`/`DishRepository`/
  `DishService` (favourite-dish FK + `getDishEntity`/`getDishByUid`) and
  `attachment`'s `Attachment`/`AttachmentService` throughout, per task
  instructions — neither redeclared.
  **The CUSTOMER→CHEF upgrade, end to end**: `POST /api/auth/upgrade-to-chef`
  (built in `profile.web`, mapped to Django's exact `/api/auth` prefix so
  `users.web.AuthController` never needed touching) → `UpgradeToChefService`,
  which checks CUSTOMER membership first (fail fast, matching Django's guard
  living outside its `atomic()` block), then in one `@Transactional` calls
  `ProfileService.createChefProfile` → `ChefPaymentService.createOrUpdatePaymentInfo`
  → `AuthService.upgradeCustomerToChef(user)` last — the exact Django call
  order. `AuthService.upgradeCustomerToChef`'s signature was not touched.
  **Seams closed**: `ProfileDishUserContext` (`@Primary` over `dish`'s
  `NoProfileDishUserContext` — real `allergy_mode`/`CustomerFavoriteDish`
  reads, read-only, no `get_or_create` side effect from a dish read) and
  `ProfileCustomerOnboardingProvider` (`@Primary` over `users`'
  `NotOnboardedCustomerOnboardingProvider` — real `CustomerProfile.is_onboarded`
  read). Both PROGRESS.md rows for `dish`/`users` updated to `[x]` /
  precisely-scoped-remaining-gap (see Open questions — `users`' `verify_otp`
  still doesn't create the profile row, since that call site is `users`
  source this task was not allowed to touch; the practical impact is bounded
  to a cosmetic lazy-creation-order difference, not a functional gap, because
  every `profile`-owned endpoint does its own `get_or_create` exactly like
  Django's other ORM methods do). Added one NEW seam of `profile`'s own,
  `ChefCertificationProvider` (for `is_food_safety_certified`, which needs the
  not-yet-ported `certificate` module), same pattern.
  **Investigated, not guessed**: `profile/api.py` genuinely defines
  `ChefPaymentController` TWICE (a real Django copy-paste bug, not a porting
  slip) and `api_chef_payment.py`'s OTP-gated bank-verification router is
  imported into `marketplace/urls.py` but never actually mounted (the
  `add_router` line is commented out) — both written up in detail in Open
  questions with the reasoning for which behavior this port implements.
  **Real Django quirks preserved (all PORT-NOTE'd + regression-tested)**:
  `CustomerAddress` soft-delete never hides anything from any read path;
  `get_one_customer_address_by_id` ignores the requested id whenever a
  selected/default address exists, and uncaught-500s on a genuinely missing
  id in the fallback branch; `set_default_customer_address` deselects every
  address even on a nonexistent target id (own `REQUIRES_NEW` bean, same
  CLAUDE.md §8b pattern `OtpAttemptRecorder` established); `phone` in
  `CustomerProfileUpdateSchema` is accepted but never persisted (no such
  model field in Django either); setting `diet_mode=NONE` alone while a stale
  non-NONE `diet_level` remains crashes with an uncaught constraint violation
  (the real Postgres `CheckConstraint` is ported too, in `V7`, so this
  reproduces by construction, not extra code). `exceptions/users.py`'s three
  `Bank*Required` classes are confirmed dead imports in Django (never raised)
  — not re-declared anywhere; `@NotBlank` on `ChefPaymentInfoRequest` is the
  real enforcement mechanism, matching what Django's Pydantic schema actually
  does.
  **Tests: 63 new, `*Test.java` throughout.** `ChefPaymentServiceTest` (12,
  Mockito — every field_validator port including the raw-value-before-
  stripping account-name order and the empty-string-skips-validation quirk on
  citizen_id/tax_code), `CustomerServiceTest` (16 — diet mode/level HttpError
  branches, onboarding ingredient validation, both address lookup quirks, the
  favourite-dish lifecycle), `ProfileServiceTest` (10 — idempotent chef-profile
  create/overlay, owner/admin/stranger gating, the empty-table-404-but-empty-
  page-doesn't pagination quirk), `ProfileDishUserContextTest` (4),
  `ProfileCustomerOnboardingProviderTest` (3), `UpgradeToChefServiceTest` (2 —
  the Django call order via `InOrder`, and the non-CUSTOMER fail-fast with
  zero writes). Full-stack (MockMvc + Testcontainers, real Postgres):
  `ProfileControllerTest` (12 — chef profile CRUD/role-gating incl. the
  no-role-gate-on-update quirk, customer profile/diet validation, onboarding,
  the full address lifecycle incl. the preserved quirks, favourite dishes,
  chef payment incl. the 401 VALIDATION_ERROR path) and
  `UpgradeToChefControllerTest` (4) — **the designated cross-module
  integration point**: a CUSTOMER-role token is proven rejected by CHEF-only
  endpoints in BOTH `dish` (`GET /api/dishes/mine`) and `menu`
  (`GET /api/menus/mine`) before the upgrade, and accepted by both
  immediately afterward using the SAME token (no re-login — roles are
  re-read from the DB per request by `JwtAuthenticationFilter`), plus the
  already-CHEF re-upgrade rejection and the avatar-resolution path.
  Docker Desktop's engine was already running at this session's start
  (checked via `docker version` first, per task instructions).
  `./mvnw -q compile` clean; `./mvnw -q test` — **259/259 passing
  project-wide** (63 new + 196 pre-existing), confirmed via
  `target/surefire-reports/*.txt` "Tests run" counts across all 30 test
  classes, run twice in a row to rule out flakiness. Marked 🚩 (not ✅) for
  the duplicate-controller-class/dead-router findings and the several
  preserved quirks above needing human sign-off — the module itself is
  complete and fully tested, nothing left half-done. Did not touch
  `attachment`/`ingredient`/`dish`/`menu`/`users`/`security`/`common` source
  (the one sanctioned cross-module call, `AuthService.upgradeCustomerToChef`,
  was used exactly as its javadoc specified, signature unchanged).
- 2026-09-23 — Ported `cart` (subagent, Sonnet 5). Read
  `../backend/cart/{models.py,orm/cart.py,services/__init__.py,api.py,
  schemas/{requests,responses}.py}`, `../backend/exceptions/carts.py`, and
  grepped all of `../backend` for cross-app usage of `Cart`/`CartItem`/the
  two cart exceptions. Confirmed the only outside consumer is the
  not-yet-ported `order` module (`order/services/__init__.py::OrderService`
  imports `CartORM`/`CartService` directly and calls
  `get_selected_cart_items_by_user`/`get_delivery_dates_of_selected_items`/
  `clear_selected_items` at checkout — none of which `cart/api.py` exposes
  over HTTP itself). Also checked `dish/services/stock_reservation.py`'s
  actual callers (grep, not assumed) — confirmed `cart` never calls
  `reserve`/`confirm`/`release`, only `dish`'s own inventory paths and
  future `order` do; and grepped `cart/` for any `voucher` reference —
  none exist, so no voucher seam was needed (voucher/pricing preview is
  entirely `order`'s concern). Checked FE-admin for a cart consumer — none
  exists (FE-admin is the admin/chef dashboard, not the customer ordering
  app), so no FE-admin field-shape cross-check was possible for this module
  specifically; DTO field names were instead cross-checked directly against
  `cart/schemas/{requests,responses}.py` and the Django integration test
  suite (`integration_tests/integration/test_cart.py`), which exercises
  every endpoint's happy/error paths and was used as the executable spec.
  **Built**: `Cart`/`CartItem` entities (reusing `dish.entity.Dish` +
  `DishRepository`/`DishAvailabilityRepository` and `users.entity.CustomUser`
  — none redeclared), `CartRepository`/`CartItemRepository`, `CartService`,
  6 DTOs, `CartController` (6 endpoints matching `api.py`'s paths/methods
  exactly: `GET /`, `GET /count`, `POST /add`, `PUT /cart-items/{uid}/toggle`,
  `PUT /items/{dish_uid}`, `DELETE /items/{dish_uid}`), 3 exceptions 1:1 with
  `exceptions/carts.py`, and `V8__init_cart.sql` (re-checked the max
  V-number immediately before writing it, and again right before the final
  test run — V7 was still the max both times).
  **Confirmed, not assumed, that cart's endpoints are NOT role-gated**:
  Django's `cart/api.py` declares `auth=AuthBear()` on the whole controller
  with no `@require_group`/`@require_permission` on any individual route —
  "my cart" is scoped by ownership (the authenticated principal), not by
  role. `anyAuthenticatedRole_canUseTheirOwnCart` in `CartControllerTest`
  proves a CHEF- and an ADMIN-role account can each use their own cart same
  as a CUSTOMER, rather than assuming (as the task brief warned might be the
  case) that "my cart" implies a CUSTOMER-only gate — it doesn't in Django.
  **Two Spring-specific correctness points worth recording for future
  modules**: (1) a null `Dish`/entity parameter passed into a Spring Data
  derived-query method (`findByDishAndAvailableDate(dish, date)`,
  `findByCartAndDishAndDeliveryDate(...)`, `deleteByCartAndDishAndDeliveryDate(...)`)
  is auto-translated to `IS NULL` at bind time, exactly like Django's own
  `.filter(dish=None)` — confirmed by testing rather than assumed, and this
  is what makes `removeItem`/`setQuantity`'s zero/negative-quantity branch
  a safe no-op for an invalid `dish_uid` in both stacks; (2) boolean field
  `selected` (not `isSelected`) on `CartItem`, following the `ingredient`
  port's already-documented Spring Data JavaBeans-property-derivation
  gotcha (`findAllByActiveTrue`, not `IsActiveTrue`) — verified the derived
  query `findByCartOwnerAndSelectedTrue` actually resolves rather than
  trusting the pattern by analogy alone.
  **Django bugs preserved verbatim (all PORT-NOTE'd + regression-tested,
  all written up in "Open questions" above)**: `toggle_select`'s complete
  absence of an ownership check (any authenticated user can toggle any
  other user's cart item if they know its uid); the dead
  `CartNotFoundException` branch (`get_or_create` never returns falsy,
  confirmed unreachable via Django's own coverage report showing that
  `raise` line as "missed"); the null-dish `AttributeError`-crash-to-500 in
  `add_item`/`set_quantity`/`toggle_select` (ported as an equally-uncaught
  `NullPointerException`, same 500 `CONTACT_ADMIN_FOR_SUPPORT` outcome);
  and the `quantity_to_add or 1` falsy-zero quirk (sending `0` silently adds
  1 item).
  **Tests: 54 new, `*Test.java` throughout.** `CartServiceTest` (32,
  Mockito — delivery-date validation, the falsy-zero quirk, availability
  capping on both add/set paths (new item and accumulation), the
  null-dish/null-cart-item NPE crashes, the cross-delivery-date toggle
  rejection and its same-date success path, the dead `CartNotFoundException`
  branch forced via a mocked `get_or_create`, and the three `order`-facing
  seam methods) and `CartControllerTest` (22, MockMvc + Testcontainers,
  registering through the real `/api/auth/register` endpoint and building
  fixture dishes/availabilities through the real `dish`-module HTTP
  endpoints exactly like `menu.web.MenuControllerTest`'s pattern) — full
  happy-path/error-path coverage for all 6 endpoints, the two dead-end/crash
  quirks over real HTTP, the toggle-select ownership gap demonstrated end to
  end, the not-role-gated confirmation, and per-user cart isolation. One
  real test bug caught and fixed during this pass (not an application bug):
  the initial cross-delivery-date toggle test extracted the wrong cart
  item's `uid` from the second `add` response's JSON (a `"uid"` substring
  search found the FIRST item's uid, which — once the cart holds two items
  grouped by ascending `delivery_date` — is no longer necessarily the
  just-added one); fixed by adding an `extractLastField` test helper for
  the case where an earlier-dated item's fields legitimately precede a
  later one's in the response body.
  Docker Desktop's engine was already running at this session's start
  (checked via `docker version` first, per task instructions).
  `./mvnw -q compile` clean; `./mvnw -q test` — **313/313 passing
  project-wide** (54 new + 259 pre-existing), confirmed via
  `target/surefire-reports/*.txt` "Tests run" counts across all 32 test
  classes, run twice in a row to rule out flakiness. Marked 🚩 (not ✅) for
  the toggle-select ownership gap and the other preserved-quirk findings
  above needing human sign-off — the module itself is complete and fully
  tested, nothing left half-done. Did not touch
  `attachment`/`ingredient`/`dish`/`menu`/`profile`/`users`/`security`/
  `common` source.
- 2026-09-23 — Ported `voucher` (subagent, Sonnet 5). Read
  `../backend/voucher/{models.py,orm/voucher.py,services/__init__.py,api.py,
  schemas/{request,response}.py}` and `../backend/exceptions/vouchers.py` in
  full, and grepped all of `../backend` for cross-app usage of
  `Voucher`/`AppliedVoucher`/the 8 voucher exceptions — confirmed `order`
  (not yet ported: reads `AppliedVoucher` directly for checkout-total
  calculation and writes the USED/CANCELLED status transitions via raw ORM
  `.update()` calls, never through `voucher`'s own service) and `admin` (not
  yet ported: `AdminService.create_voucher` for admin-created PLATFORM_* rows,
  and the `/api/admin/voucher` endpoints FE-admin's `voucherService.js`
  actually calls for create/list — `voucher/api.py`'s own `/api/vouchers/*`
  only covers get/update/delete/validate/list-by-chef, cross-checked against
  FE-admin) are the only two consumers, neither of which blocks this port.
  Investigated (not assumed) two things the task brief specifically flagged:
  (1) `exceptions/vouchers.py`'s literal duplicate class-definition block — a
  plain copy-paste artifact (both blocks are byte-identical except the second
  adds `VoucherUsageLimitException`), and confirmed by grep that
  `VoucherUsageLimitException` is never actually raised anywhere (nor are
  `VoucherExpiredException`/`VoucherMinOrderException`/`VoucherChefMismatchException`
  — all four are dead code, their real equivalents being plain
  validation-result tuples inside `validate_voucher_for_order`); (2) whether
  the reservation system uses Redis like `dish`'s stock holds — it does not:
  grepped `voucher/services/__init__.py`/`voucher/orm/voucher.py` top to
  bottom for any Redis/cache import and found none. It's pure Postgres
  (`select_for_update()` row lock + a durable `AppliedVoucher` ledger row with
  a `reservation_expires_at` TTL column), and expiry is a synchronous lazy
  check (`expire_old_reservations`, called inline at the start of every apply
  attempt) — confirmed against `marketplace/celery.py`'s `beat_schedule`
  (still exactly the two tasks CLAUDE.md §6 already accounts for) that there
  is no scheduled/Celery job for voucher expiry anywhere; no new `@Scheduled`
  job was added.
  **Built**: `Voucher`/`AppliedVoucher` entities (`Voucher` = Django
  `BaseModel`, UUID uid; `AppliedVoucher` = a plain Django `models.Model`,
  BIGSERIAL id — Django declares it that way, not ported inconsistently) + 3
  enums (`VoucherType`, `VoucherDiscountType`, `VoucherReservationStatus`),
  `VoucherRepository`/`AppliedVoucherRepository` (the former with
  `@Lock(PESSIMISTIC_WRITE)` query methods mirroring Django's
  `select_for_update()`), `VoucherService` (CRUD, `validateVoucherForOrder`,
  the two reservation-apply flows, `calculateNetSubtotal`), 6 request/response
  DTOs, `VoucherController` (8 endpoints matching `api.py`'s paths/methods
  exactly), 8 exception classes 1:1 with `exceptions/vouchers.py`'s
  de-duplicated class list, and `V9__init_voucher.sql` (re-checked the max
  V-number immediately before writing it — V8 was still the max). Reused the
  already-ported `users.entity.CustomUser` throughout (chef ownership, the
  reservation's `user` FK); `profile.entity.ChefProfile` was read but not
  needed as a dependency — voucher's chef scoping is entirely by `CustomUser`
  identity (Django's own model does the same, an FK straight to `User`, not to
  `ChefProfile`).
  **The ADMIN-group-OR-`is_staff` quirk, the task's specific focus, ported and
  tested exactly**: `voucher/services/__init__.py:161,197`'s
  `chef.groups.filter(name="ADMIN").exists() or chef.is_staff` is the ONE
  place in the whole Django backend where `is_staff` is a genuine secondary
  authorization fallback (every other module uses the ADMIN group alone, per
  the "Part 1 findings" section above) — ported as
  `chef.isAdmin() || chef.isStaff()` in both `VoucherService.updateVoucher`
  and `.deleteVoucher`, and tested both ways over real HTTP: a plain
  ADMIN-group member with no relation to the voucher succeeds
  (`updateVoucher_plainAdminGroupMember_bypassesOwnership`), AND a CHEF with
  `is_staff=true` and NO ADMIN group membership at all also succeeds
  (`updateVoucher_chefWithIsStaffTrue_bypassesOwnership_evenWithoutAdminGroup`),
  plus the Mockito-level equivalents for both `update`/`delete` in
  `VoucherServiceTest`.
  **Reservation/usage-limit correctness (the money-adjacent focus of this
  task)**: `applyShopVoucherReservation`/`applyPlatformVoucherReservation`
  port Django's exact check order for a NEW reservation (total usage-limit
  quota -> validity window -> per-user quota -> compute discount -> insert)
  and the separate reuse-existing-RESERVED-row path (recalculate discount,
  skip all quota checks — Django does this too, since re-applying to the same
  order/checkout shouldn't consume a second unit of quota). Proved the
  `SELECT ... FOR UPDATE` row lock on `voucher` actually serializes concurrent
  quota checks with a **25-thread contention test against a usage_limit of
  5** on a real Postgres (`VoucherReservationServiceTest`, mirroring `dish`'s
  `StockReservationServiceTest` pattern) — exactly 5 succeed, 20 are rejected,
  and the ledger holds exactly 5 RESERVED rows, i.e. no over-redemption.
  **Found and preserved a real, reachable Django bug in the reservation
  system's own DB constraints** (see "Open questions" above for the full
  writeup): the partial unique indexes backing "1 SHOP_VOUCHER reservation per
  order" apply regardless of `status`, so once a reservation EXPIRES, a second
  attempt for that same order can never INSERT — reproduced against a real
  Postgres, not just reasoned about, in
  `applyShopVoucherReservation_afterExpiry_secondAttemptForSameOrder_hitsThePreservedUniqueConstraintBug`.
  Flagged as the single highest-value fix candidate in this module.
  **Real Django gap preserved, not fixed**: `voucher/api.py` has NO
  `@require_group`/`@require_permission` on ANY endpoint, including
  `create_voucher` (docstring says "Chef only", nothing enforces it) — a plain
  CUSTOMER can create a voucher. Confirmed against `api.py` line by line
  (not assumed, since the task brief's own framing as "Chef creates and
  manages their own vouchers" could easily have been mistaken for an implicit
  role gate), and regression-tested over real HTTP.
  **Other Django quirks preserved verbatim, each PORT-NOTE'd**: `create_voucher`'s
  code-uniqueness check is GLOBAL across chefs despite the DB constraint being
  per-chef; `update_voucher`'s percentage-bounds re-validation is 100% dead
  code (compares `voucher_type`, which is never "PERCENTAGE", and
  `UpdateVoucherSchema` has no `voucher_type`/`code` field to trigger the
  adjacent code-conflict branch either) — `discount_value` can be PATCHed to
  any value with zero validation; `get_voucher_usage_count_by_user` (used by
  `validate_voucher_for_order`/`get_available_vouchers_by_chef`) counts ALL
  `AppliedVoucher` rows including CANCELLED/EXPIRED ones, while the
  reservation-apply flows' own per-user quota check is status-filtered
  (RESERVED+USED only) — two genuinely different counting rules for the same
  "has this user used up their limit" question, kept as two different
  repository methods rather than unified; `apply_voucher_to_order` (the older,
  non-reservation apply path) was confirmed dead/broken in Django itself (it
  calls `self.orm.create_voucher_usage(...)`, a method that doesn't exist
  anywhere in `VoucherORM` — would `AttributeError` if ever actually invoked,
  and nothing calls it) and was not ported, since porting it faithfully would
  mean deliberately writing a call to a nonexistent method for no behavioral
  gain, and it also needs a live `order.Order` this codebase doesn't have yet.
  **Tests: 53 new, `*Test.java` throughout.** `VoucherServiceTest` (38,
  Mockito — create/update/delete validation and precedence, the
  ADMIN-or-is_staff ownership bypass both ways, `validateVoucherForOrder`'s
  full precedence chain including the Vietnamese min-order-amount message
  formatting, `Voucher.calculateDiscount`/`calculateShippingDiscount`'s
  FIXED_AMOUNT/PERCENTAGE/cap branches, both reservation-apply flows' reuse-
  vs-create branching and quota/validity check order, `calculateNetSubtotal`),
  `VoucherReservationServiceTest` (5, MockMvc-free full-stack against a real
  Postgres via the shared `TestcontainersConfiguration` — the 25-thread
  contention proof, the reuse-not-duplicate path, per-user limit enforcement,
  wrong-chef 404, and the preserved unique-constraint bug reproduction),
  `VoucherControllerTest` (10, MockMvc + Testcontainers, registering through
  the real `/api/auth/register` endpoint and assigning roles/`is_staff` via
  the repository exactly like `cart`/`menu`/`profile`'s controller tests) —
  full CRUD + validate + chef-listing happy paths, duplicate-code 400,
  not-found 404, the no-role-gate-on-create quirk, and both ownership-bypass
  paths over real HTTP.
  Docker Desktop's engine was already running at this session's start
  (checked via `docker version` first, per task instructions).
  `./mvnw -q compile` clean; `./mvnw -q test` — **366/366 passing
  project-wide** (53 new + 313 pre-existing), confirmed via
  `target/surefire-reports/*.txt` "Tests run" counts across all 35 test
  classes, run twice in a row to rule out flakiness in the concurrency test
  (both runs green). Marked 🚩 (not ✅) for the no-role-gate-on-create finding
  and the preserved unique-constraint bug above needing human sign-off — the
  module itself is complete and fully tested, nothing left half-done. Did not
  touch `attachment`/`ingredient`/`dish`/`menu`/`profile`/`cart`/`users`/
  `security`/`common` source.
- 2026-09-23 — Ported `order` (subagent, Opus 5; paused once by a rate limit after the
  entities/DTOs and resumed from disk). Read `../backend/ORDER_INVENTORY_FLOW.md`,
  `ORDER_FLOW_ONBOARDING.md`, `ORDER_FLOW_WALKTHROUGH.md` in full, then all of
  `../backend/order/` (`models.py`, `orm/order.py`, `services/{__init__,order_state_machine,
  shipping_service}.py`, `schemas/*`, `api.py`, `interfaces.py`, `signals.py`, `tasks.py`),
  `exceptions/orders.py`, `dish/tasks.py`, the voucher/profile/cart ORM methods order calls,
  `utils/router/exception.py` (ValueError/HttpError rendering) and `EmailTemplate`.
  **Built**: see the `order` table row. `V10__init_order.sql` (max was V9, re-checked before
  writing) creates `checkout`, `"order"` (quoted, as V9's comment spells it), `order_item`,
  `notification_idempotency_key` and adds the real `stock_reservation.order_item_id` FK.
  **Lifecycle, end to end**: `POST /api/checkouts/` reads the selected cart items via
  `CartService`'s seam -> one DRAFT order per chef, prices snapshotted, tax 10%, ship 15k
  (>=200k) / 30k; no stock touched. Vouchers can then be reserved (shop per order, platform
  per checkout) and discounts re-allocated from the `AppliedVoucher` ledger (shop first,
  platform-subtotal pro-rata by net subtotal, platform-shipping pro-rata by fee).
  `place-order` RESERVES every item through `dish`'s `StockReservationService` (Redis Lua +
  RESERVED ledger row; any failure releases the holds taken so far). COD: vouchers -> USED,
  orders -> PENDING, every hold CONFIRMED immediately (permanent `DishAvailability`
  decrement), cart cleared; any failure releases everything (confirmed items restored) and
  reverts PENDING -> DRAFT. PayOS: orders -> PENDING, holds stay RESERVED for the (future)
  webhook or the TTL sweep. Chef: confirm (COD@PENDING / PayOS@CONFIRMED_SYSTEM) ->
  start-processing -> start-delivery -> complete (settlement via the payment seam).
  Cancel/reject: refund (seam) -> CANCELLED -> `release(expired=false)` per item
  (RESERVED -> RELEASED + Redis credit, or CONFIRMED -> CANCELLED + `DishAvailability`
  restored) -> vouchers CANCELLED. TTL sweep: `dish` expires the holds, then
  `OrderExpiredOrderHandler` cancels still-DRAFT/PENDING orders (row-locked, own
  transaction each), expires their vouchers and fires the idempotent `CANCELLED_EXPIRED`
  email.
  **One real bug caught by the tests, in this port's own code**: the stale-DRAFT cleanup in
  `checkout()` uses a `@Modifying(clearAutomatically=true)` delete, which detached the cart
  items already loaded -> `LazyInitializationException` (500) on every re-checkout. Fixed by
  re-reading the selected items after the cleanup (also the faithful order — Django's
  queryset is lazy and only iterated after the delete).
  **Cross-module touch (sanctioned)**: `dish`'s `StockReservationServiceTest.nextItemId()`
  now inserts a real `order_item` row (the new FK), assertions unchanged. No `dish`/`cart`/
  `voucher`/`profile`/`users`/`security`/`common` main source was modified.
  **Tests: 145 new, `*Test.java` throughout.** `OrderStateMachineTest` (92 — all 64
  from/to pairs against an independently transcribed table + every helper),
  `OrderServiceTest` (21, Mockito — `InOrder` proofs of place-order/cancel sequencing and
  every compensation branch, checkout grouping/fees, the discount allocation math),
  `OrderNotificationServiceTest` (6), `OrderNotificationIdempotencyTest` (4, real Postgres
  + real `@Async`/`@Retryable`: transient failure -> retried -> sent once; redelivery after
  success skipped; permanent failure -> 4 attempts, key released; 16-thread claim race -> 1
  winner; 8 concurrent deliveries -> 1 send), `OrderControllerTest` (17, MockMvc + real
  Postgres + Redis: full COD lifecycle with ledger/`DishAvailability`/Redis assertions,
  chef-ownership 403s incl. ADMIN, role-scoped lists, cancel-after-confirm restores stock,
  6-way contention for the last unit -> exactly 1 order, multi-item rollback, double
  place-order deducts once, PayOS verified-chef gate + RESERVED holds + cancel credit,
  shop/platform voucher lifecycle and pro-rata math, checkout edits incl. the Ahamove
  fallback, error paths, preserved quirks), `OrderExpirySweepTest` (5, through the real
  `StockHoldSweepScheduler.sweepOnce()`). Docker Desktop's engine had stopped during the
  pause — restarted and waited before testing. `./mvnw -q compile` clean;
  `./mvnw -q test` — **511/511 passing project-wide** (145 new + 366 pre-existing) across
  41 test classes, confirmed from `target/surefire-reports/*.txt`, run twice. Marked 🚩 for
  the Open questions above (payment seam, preserved authorization gaps, COD voucher-USED
  non-revert) — nothing left half-done within this module's own scope.
- 2026-09-23 — Ported `payment` (subagent, Opus 5.5). Read `../backend/PAYMENT_WALLET_INTERVIEW_PREP.md`
  and `WALLET_SECURITY_DOCS.md` in full, then all of `../backend/payment/` (`models.py`, the single
  `services.py` (2 399 lines), `providers/payos.py`, `schemas/*`, `api.py`, migrations 0011/0012/0015),
  the payment call sites in `order/services/__init__.py`, `dish/services/stock_reservation.py`, the
  OTP code in `users/`, `marketplace/{settings,urls}.py`, and the INSTALLED `payos==1.1.0` SDK's
  `_client.py`/`_crypto/provider.py`/payout types (Django's payouts go through the SDK, so its
  exact request signing, `x-idempotency-key`, retry policy and response-signature check are part of
  the spec). There is no `exceptions/payment.py` in Django (payment raises `users` exceptions,
  `HttpError`s and bare `ValueError`s) — ported as `PaymentHttpException` (HTTP_ERROR shape, like
  `order`) and `PaymentValueError` (→ 500). No Django tests exist for payment (grepped).
  **Built**: see the table row. `V11__init_payment.sql` (max was V10, re-checked before writing;
  Django's `db_table` names kept; Django migration 0012's append-only trigger copied verbatim).
  **PayOS integration**: no Java SDK — plain JDK `HttpClient` against exactly Django's endpoints
  (`/v2/payment-requests` create/info/cancel/invoices on `PAYOS_API_URL`, `/v1/payouts` on the SDK's
  `PAYOS_BASE_URL`). Every signature is reproduced byte-for-byte: Django's hand-written
  `_create_signature` (5 sorted fields for payment links; ALL sorted `data` fields, Python `str()`
  rendering, for webhooks) and the SDK's header signature (deep-sorted, JSON-encoded lists,
  `urllib.parse.quote`d) — `PyCompat` re-implements the handful of Python conversions involved
  (`str`/`repr`/float repr/`json.dumps`/`quote`/scale-preserving `str(Decimal)`), and
  `PayOsSignatureTest`/`PyCompatTest`/`PaymentHashingTest` pin them to vectors computed by running
  the ORIGINAL Python (Django provider + installed SDK) in `../backend/venv`.
  **Webhook/return**: plain `void` handlers writing straight to the servlet response (Django mounts
  them as raw views, not ninja) — so no envelope; webhook always 200 + empty body; return answers
  Django's raw `JsonResponse` shapes. **Transactions**: Django mixes autocommit, `atomic` blocks and
  "whatever the caller has" inside single methods; `PaymentTx` (programmatic REQUIRED /
  REQUIRES_NEW / NOT_SUPPORTED templates) reproduces each boundary without self-invocation traps,
  incl. §8b save-then-raise (`STATE_REJECTED` event written, then the ValueError thrown outside the
  template). Row locks mirror every Django `select_for_update()` (wallet credit/withdraw, refund
  payment row, COD balance).
  **What testing found**: two Django bugs that make the money flows non-functional as written
  (open questions #1 every PayOS payment ends refunded/cancelled; #2 withdrawals can never pass the
  integrity check) and a multi-chef checkout payout/refund bug (#3) — all confirmed by reading the
  Python, then reproduced by tests rather than assumed; the intended behavior of #1 sits behind a
  documented flag and is fully tested too. One real bug in this port's own first draft, caught by
  the first `order` test run: `payout_ledger.chef_id` INTEGER vs the entity's Long (schema
  validation) — fixed to BIGINT.
  **Cross-module touches (sanctioned)**: only the `@Primary` `OrderPaymentGateway` (new class in
  `payment`) and the shared test `TestcontainersConfiguration` (registers `FakePayOsServer` +
  `app.payos.*`/`app.payment.*` test properties, needed because `order`'s existing PayOS tests now
  reach the real gateway). No `order`/`dish`/`voucher`/`users`/`profile`/`common`/`security` main
  source changed; `order`'s 145 tests pass unchanged.
  **Tests: 80 new, `*Test.java` throughout** — `PayOsSignatureTest` (8), `PyCompatTest` (9),
  `PaymentHashingTest` (9: wallet/event/ledger HMACs, order code, transition table, retry/mask
  helpers), `PayOsProviderTest` (15: real HTTP against `FakePayOsServer` — request signatures,
  error mapping, webhook verification incl. tampering/forgery/malformed payloads, payout signing,
  response-signature rejection, SDK retry counts), `WalletLedgerTest` (11: chained credits with
  Django's exact hash strings, the preserved integrity bug, balance tampering detection, 10-thread
  credit race → no lost update/no fork, append-only trigger, withdrawal success/revert/retries/
  insufficient/no-bank, 5-thread withdrawal race → exactly one payout, balance never negative),
  `PayOsWebhookTest` (11: gateway bean, PayOS place-order, raw 200 contract, forged webhook,
  preserved #1 end-to-end + replay, failure webhook + stock inflation quirk, return endpoint,
  unpaid cancel), `PayOsEscrowLifecycleTest` (8: full escrow lifecycle to the chef's wallet with
  replays at HOLDING and RELEASED, 8-thread refund race → one credit, tampered audit chain blocks
  the refund, return-URL reconciliation, late webhook recovered / sold-out refunded, COD
  completion, multi-chef #3), `PaymentControllerTest` (9: bank-info OTP flow incl. normalization,
  validation, replay, foreign OTP; withdraw OTP → payout over HTTP incl. replay and the 3-attempt
  cap; validation order; create/status/info/invoices/inverted cancel guard; chef balance + COD
  settlement). Docker engine was running (checked first). `./mvnw -q compile` clean;
  `./mvnw -q test` — **591/591 passing project-wide** (80 new + 511 pre-existing) across 49 test
  classes, confirmed from `target/surefire-reports/*.txt`; the suite was run three times (590/590
  twice before the multi-chef test was added, then 591/591). Marked 🚩 for the payment Open
  questions — nothing left half-done within the module's scope.
- 2026-09-23 — **`payment` money-bug fixes (#1-#3), subagent (Opus 5.5), on the user's "fix"
  decisions.** Docker engine checked first (28.1.1). **Part A first (user asked to verify the
  earlier "escrow is per payment, fix is a design decision" claim before coding):** re-read Django
  `payment/models.py`, `payment/services.py` (`credit_internal_wallet`,
  `handle_order_cancellation_refund`, `create_settlement_record`), `order/models.py` and
  `order/services/__init__.py` (`complete_order_with_release`, `cancel_order`). Finding (written into
  open question #3 before any code, with file:line citations): the escrow *state* is per payment,
  but the *money records* are already per order — `WalletTransaction.order` (FK) + type
  REFUND/RELEASE is written by both the refund and the release credit; `SettlementRecord` is
  one-to-one per order with the chef and the 90% payout; `PayoutLedger` lines carry order_uid/chef_id;
  `ChefCODBalance` is per chef and COD-only; `Order.payment_status` is only a copy of the checkout
  state. So "has THIS order been released/refunded?" is an EXISTS on `wallet_transactions` — no
  migration needed (V11 still the max). **Part B:** #1 flag default flipped to false (yml +
  properties), bug-pinning test switches it on explicitly; #2 canonical 2-dp amounts at every
  wallet hash/sign site, new wallets signed at creation, re-sign after successful payout; #3
  `EscrowBook` + per-order release/refund guards under the payment row lock, checkout state moves
  only when the last order leaves escrow (details in open question #3). Only `payment` main/test
  sources, `application.yml` and this file changed — no `order` (or other module) source or test
  touched. Tests: 4 net new (PaymentHashingTest +1, PayOsWebhookTest +1 default-mode webhook,
  PaymentControllerTest +1 earn-then-withdraw full stack, PayOsEscrowLifecycleTest +1: the single
  multi-chef bug test became three fix tests); several existing tests re-pointed from "Django bug
  preserved" to "fixed" (WalletLedgerTest integrity + double withdrawal, PaymentControllerTest
  new-wallet withdrawal now fails only on balance). `./mvnw -q compile` clean; `./mvnw -q test`
  **595/595 passing across 49 test classes** (payment: 84), confirmed from
  `target/surefire-reports/*.txt`.
- 2026-09-23 — Ported `review` (subagent, Sonnet 5). Read `../backend/review/{models.py,
  orm/,services/,schemas/,api.py}` and `../backend/exceptions/reviews.py` in full, plus
  `order/interfaces.py`+`order/services/__init__.py` (`check_dish_in_order`,
  `get_order_by_uid_not_transfer`) and `dish/orm/dish.py`/`dish/service/DishStatsProvider.java`'s
  javadoc for the stats seam. Docker checked first (28.1.1). Built: `Review`/`ReviewReply`
  entities + 2 repositories (reusing `dish.Dish`, `order.Order`/`OrderItem`, `profile.ChefProfile`,
  `attachment.Attachment`, `users.CustomUser` — none redeclared), `ReviewService` + a separate
  `ReviewAnalyticsService`, the `AiModelClient` seam (interface + `RestClientAiModelClient` +
  `AiModelProperties`) over Spring's `RestClient`, 2 controllers (`ReviewController` 9 endpoints +
  `ReviewAnalyticsController` 4 endpoints), 12 exception classes (`review/exception/` — 10 from
  `exceptions/reviews.py` + a module-local `PermissionDeniedException` mirroring
  `utils/exceptions.py`, same pattern `menu`/`profile` already use), `V12__init_review.sql`.
  **Key findings, all written up as PROGRESS.md open questions above:** (1) every one of the ten
  `exceptions/reviews.py` classes sets `status_code` instead of `error_code` — a repo-wide,
  file-scoped instance of the bug already flagged for `ingredient`'s `NutritionValidationException`
  — so they are ALL really 500s in Django; ported faithfully (not "fixed" into the visually-intended
  codes) per this task's explicit instruction, each class's javadoc explains it; (2)
  `_predict_review_label` never actually raises `AIModelUnavailableException`/
  `AIModelInvalidResponseException` (dead code, confirmed by grep) — every AI failure mode
  (unreachable/non-2xx/non-JSON/non-dict/bad-weight) falls back to `(weight=0.0, issue=None)` and
  the review write always proceeds; `RestClientAiModelClient.predict` reproduces this exactly and
  never throws; (3) a real, reachable bug where `attachment_uid` is validated
  (`AttachmentService.handleAttachment`) but its result is discarded, so a review's attachment
  link never actually gets set — preserved, not fixed (no known consumer: FE-admin calls zero
  `review` endpoints); (4) the `ReviewReplyResponse.resolve_owner_info` duplicate-definition bug
  (same class as `profile`'s duplicate `ChefPaymentController`) makes a reply's `full_name`
  behave differently from a review's own — ported as two genuinely different helpers
  (`ReviewOwnerInfo.of`/`.ofReply`); (5) `DishNotOrderedException` is dead code — the real
  "dish not in this order" exception `create_review` raises is `dish.exception.
  DishNotFoundInOrderException` (reused, not redeclared) via `OrderItemRepository.
  existsByOrderUidAndDishUid` (already existed, built for `order`'s own seam). **Stats seam
  closed**: `review.service.ReviewStatsProvider`/`ReviewStatsProviderImpl` (real `review_count` +
  platform-average rating) now compose into `order.service.OrderDishStatsProvider` — edited that
  one file in `order` (one constructor field + two delegating method bodies, pre-approved by its
  own prior javadoc) rather than adding a second `@Primary` bean; nothing else in `order`/`dish`
  touched. Every review write recomputes `Dish.avgRating`/`Dish.finalScore`
  (`ReviewService.updateDishAvgRating`) and `ChefProfile.rating` (`updateChefAvgRating`), matching
  Django's `_update_dish_avg_rating`/`_update_chef_avg_rating` exactly (including the `round(x,2)`
  rounding and the "recompute even if only the comment changed, because weight may have changed"
  behavior on update). **Hit and fixed a real Boot-4 gotcha**: `spring-boot-starter-webmvc` alone
  does not autoconfigure a `RestClient.Builder` bean (confirmed via a real
  `NoSuchBeanDefinitionException` at context startup, the same "starter, not raw dependency" class
  of surprise CLAUDE.md §1 already documents for Flyway) — fixed by building the `RestClient`
  directly via the static `RestClient.builder()` inside `RestClientAiModelClient`'s constructor,
  no new Maven dependency needed. Scope reduction, flagged: chef-analytics `menu_uid`/`categories`
  filters not ported (zero known consumers — FE-admin calls no `review` endpoint at all, confirmed
  by grep). Tests: `ReviewServiceTest` (30, Mockito — rating 1-5 validation incl. exact boundaries,
  dish/order not found, order-not-completed, dish-not-in-order, duplicate, AI-unavailable-still-saves,
  attachment-discarded-bug, update/delete ownership, reply create/update ownership incl. the
  `NotDishOwnerException`-vs-plain-`PermissionDeniedException` distinction, reply-already-exists),
  `RestClientAiModelClientTest` (6, a real embedded JDK `HttpServer` — no real AI service — covering
  unreachable/non-2xx/non-JSON/invalid-weight/invalid-issue/success), `ReviewControllerTest` (7,
  full-stack MockMvc + Testcontainers — full create-review flow with AI unreachable proving the
  review still saves, the rating/dish-not-in-order/order-not-completed/duplicate rejections, the
  reply owner/already-exists rules, and the top-dish-ranking seam test: two dishes named so
  alphabetical order would mislead, 5 reviews each at 5⭐/1⭐ through the real endpoint, then
  `GET /api/dishes/top` shows the correct `review_count`/`avg_rating` and a genuinely
  rating-driven Bayesian `score` ordering). `./mvnw -q compile` clean; `./mvnw -q test` —
  **628/628 passing project-wide** (33 new: 20 `ReviewServiceTest` + 6
  `RestClientAiModelClientTest` + 7 `ReviewControllerTest`), confirmed from
  `target/surefire-reports/*.txt`. Marked 🚩 for the Open questions above (the file-wide
  status-code quirk, the discarded-attachment bug, and the analytics scope reduction) — nothing
  left half-done within this task's scope.
- 2026-09-24 — Ported `recommendation` (Opus 5.5 subagent, resumed once after a rate limit). Read all
  RECOMMENDATION_*/NUTRITION/DAILY_MEAL_LOGS/FIND_BETTER docs + every recommendation file. Built V13 (pgvector),
  5 entities, the full vector→candidates→scoring→MMR→explain pipeline, issue profile, better-for-issue, daily
  nutrition (Gemini via `GeminiClient`, fake in tests), controller (14 endpoints), AOP/Hibernate hooks replacing
  Django's cross-app signals/decorators. Numerics: `gen_vectors.py` runs the real Django code in backend/venv →
  `python_vectors.json`, asserted exactly by `PythonParityTest`. Tests: PythonParityTest 20, RecommendationPipelineTest 5
  (real Postgres+pgvector), DailyNutritionServiceTest 7, RecommendationControllerTest 5, RestClientGeminiClientTest 2
  = 39 new; **667/667 project-wide**. See recommendation open questions.
- 2026-09-24 — Two small fixes (Sonnet 5 subagent). (1) `recommendation` meal→dish threshold: `matchDish` gates the
  top dish-search hit on `search_score >= app.recommendation.dish-match-threshold` (0.6), flag
  `preserve-dish-match-no-threshold` restores Django; `DishSearchService` untouched. New test
  `matchDish_gatesOnSearchScore_...` (unrelated "Bánh bao" falls through, "Bún bò Huế" = exactly 0.6 matches, flag=true old
  behavior); `parseMeal_geminiFailureOrNoKey_...` updated ("trà đá" now unresolved, 2 Gemini calls). (2) `PyMath` moved to
  `common.util`; dish `pyRound` delegates to it (no existing dish test value changed; new regression 2.675→2.67,
  0.0005→0.001, Python-confirmed in backend/venv). Both open-question items resolved. Note: an earlier backgrounded
  test run was lost; Docker Desktop had to be restarted (daemon was down though `docker version` printed a client).
  `./mvnw -q compile` clean; `./mvnw -q test` — **669/669 passing** (667 + 2 new), from `target/surefire-reports/*.txt`.
- 2026-09-24 — `certificate` port (Sonnet 5 subagent). Entities `Certificate`/`CertificateAttachment` + 2 enums, repos (Specification-based
  filters), `CertificateService`, `CertificateController` (7 endpoints Django actually mounts: GET `/`, `/all`, `/{uid}`, PATCH
  `/{uid}/status`, `/{uid}/deleted`, `/{uid}/restored`, `/{uid}/attachments/reorder`), 2 exceptions, `V14__init_certificate.sql`.
  Admin checks are service-level 403 PERMISSION_DENIED (Django's `require_admin` raises PermissionDeniedError, not 401).
  **Seam closed:** `ChefCertificationProviderImpl` (`@Primary` over profile's `NoCertificateChefCertificationProvider`, profile source
  untouched); new seam `CertificateReviewHook` (no-op default) for Django's `_cleanup_selfie_if_fully_reviewed` -> `verification` should
  provide a `@Primary`. Tests: `CertificateServiceTest` 11 + `CertificateControllerTest` 5 (incl. profile seam end-to-end) = 16 new;
  `./mvnw -q compile` clean; `./mvnw -q test` — **685/685 passing** (from `target/surefire-reports/*.txt`).
- 2026-09-24 — `verification` port (Sonnet 5 subagent). Read models/api/schemas/services (verification.py, gemini.py, engine.py, face.py, qr.py)/
  the delete_scheduled_s3 command/exceptions + certificate's `_cleanup_selfie_if_fully_reviewed`. Built `V15__init_verification.sql`, 2 entities
  (id-only cross-module refs, jsonb columns), 10 exceptions, Gemini vision layer (own client seam + fake), risk engine, orchestrator,
  finalizer, S3 deletion service (+cron), the `@Primary CertificateReviewHook`, controller (10 endpoints, CHEF-only), application.yml keys.
  Tests: GeminiVisionServiceTest 14, RiskEngineTest 7, VerificationServiceTest 34, S3DeletionServiceTest 10, VerificationCertificateReviewHookTest 6,
  VerificationControllerTest 10 (real Postgres/Redis, fake Gemini/fetcher/face/S3; incl. the certificate-hook end-to-end test) = 81 new.
  `docker version` ok; `./mvnw -q compile` clean; `./mvnw -q test` in the foreground — **766/766 passing** (from `target/surefire-reports/*.txt`).
- 2026-09-24 — `report` port (Sonnet 5 subagent). Read models/queries/api/schemas/services (report_service, analysis, gemini_severity, local_severity)/
  exceptions/report.py/FLOW.md + email templates; grepped Django for `is_suspended`/`suspension_level`/`is_accepting_orders` consumers (none — no seams to
  close). Built `V16__init_report.sql`, 3 entities + 6 enums (scalar read-only FK columns so DTOs never touch lazy proxies), 5 repositories (two
  report-owned read views over order/review entities, no edits to those modules), `ReportAnalysisService` (constants/weights/metrics/decisions),
  `GeminiSeverityService`, `ReportCommandService` (transactional writes) + `ReportService` (orchestrator, deliberately non-transactional),
  `ReportEmailService`, `ReportMapper`, 2 controllers, 12 exceptions, `app.report.*` config. Tests: GeminiSeverityServiceTest 10, ReportAnalysisServiceTest 15,
  ReportCommandServiceTest 37, ReportServiceTest 24, ReportControllerTest 14 (real Postgres/Redis, fake Gemini; incl. end-to-end WARNING -> FULL_LOCK ->
  appeal -> lift and DISH_LOCK -> lift), ReportOrderUuidBugPreservedTest 3 = 103 new. `docker version` ok; `./mvnw -q compile` clean; `./mvnw -q test`
  in the foreground — **869/869 passing** (from `target/surefire-reports/*.txt`).
- 2026-09-24 — `chat` port (Sonnet 5 subagent). Read chat models/serializers/views/urls/consumers/routing/authentication/middleware/migration + `utils/channels/jwt_expiry.py`
  + asgi/urls mounting; grepped Django (no Conversation creator) and FE-admin (no chat usage). Built `V17__init_chat.sql`, 2 entities, 2 repositories,
  `ChatService` (+ `ChatRawException`), `ChatController` (raw JSON via servlet response, payment-webhook style), `ChatHandshakeInterceptor`,
  `ChatWebSocketHandler` (in-memory groups, private scheduler for exp close), `ChatWebSocketConfig`. No changes to `common/`, `security/` or other modules.
  Tests: ChatServiceTest 11 (Mockito), ChatControllerTest 6 (full-stack MockMvc, register via /api/auth/register), ChatWebSocketTest 7 (real Tomcat random port +
  StandardWebSocketClient: broadcast to both sockets, sender_id spoofing ignored, room isolation, invalid JSON, missing key, no-participant-check quirk,
  unknown-room, invalid/missing/forged/expired tokens rejected, forced 4001 close at exp) = 24 new. `docker version` ok; `./mvnw -q compile` clean;
  `./mvnw -q test` in the foreground — **893/893 passing** (from `target/surefire-reports/*.txt`).
- 2026-09-24 — `tracking` port (Sonnet 5 subagent). Read tracking models/consumers/api/views/schemas/service/migration + mongo_chat/routing.py + asgi/urls; found the dead ws route regex and
  the always-500 order-tracking endpoint (open questions above). Built `V18__init_tracking.sql`, `ChefLocation`, `ChefLocationRepository` (native haversine), `LocationService`,
  `LocationBroadcaster` seam, `TrackingController`, `TrackingWebSocketHandler`/`TrackingHandshakeInterceptor`/`TrackingWebSocketConfig` (separate `WebSocketConfigurer` next to chat's;
  chat source untouched), 2 exceptions, `app.tracking.*` flags. Tests: LocationServiceTest 4 (Mockito), TrackingControllerTest 3 (full-stack MockMvc), TrackingWebSocketTest 2 (real Tomcat,
  update persisted + pushed to same-chef watchers only, unauthenticated, other chef isolated, null heading, frames ignored) = 9 new. `docker version` ok; `./mvnw -q compile` clean;
  `./mvnw -q test` in the foreground — **902/902 passing** (from `target/surefire-reports/*.txt`).
- 2026-09-25 — `admin` port (Sonnet 5 subagent; session cut by a rate limit once and resumed). Read all of `backend/admin/*.py` + FE-admin services/pages/constants. Built `com.amomeal.marketplace.admin`: `AdminController` (19 routes), `AdminService`, `AdminDashboardQueries` (JPQL + one native UTC-day query), `AdminCustomerBankQueries`, read-only Spec repositories over users/orders/chef-bank, `AdminDtos`, 3 exceptions, `AdminExceptionAdvice`, `AdminParams`/`Specs`. No migration (V18 still max). Tests: AdminServiceTest 18 (Mockito), AdminAccessMatrixTest 5 (every endpoint x no token/CUSTOMER/CHEF/ADMIN + validation-before-authz), AdminDashboardTest 6 (fixtures with known totals: revenue windows/UTC boundaries, payment methods, statuses, districts, top chefs), AdminOrdersTest 5, AdminManagementTest 12 (users, vouchers, certificate + KYC hook, verification review, bank accounts) = 46 new. `docker version` ok (Docker Desktop had to be started); `./mvnw -q compile` clean; full `./mvnw -q test` in the foreground: see total in the report below.
- 2026-09-25 — Authorization + suspension enforcement (post-port user decisions, Sonnet 5 subagent). `order`: `OrderService.assertCanReadOrder/assertOwnsOrder/assertOwnsCheckout` called from `OrderController`/`CheckoutController` (403 `OrderHttpException`, 404 first for unknown uid; `checkout()`/`placeOrder()` now call `assertChefAcceptingOrders`/`assertDishNotSuspended` before anything is reserved). `voucher`: `createVoucher` CHEF-or-ADMIN. `cart`: `toggleSelect(user, uid)` + `requireNotSuspended` on add/set-quantity. `payment`: `PaymentService.assertCheckoutAccess/assertPaymentAccess/assertOrderCodeAccess` + chef self-or-ADMIN on balance/settle; guard runs BEFORE `createPayment`'s catch-all (which would otherwise turn the 403 into `success=false`); return-URL sync idempotent. `dish`: `DishSpecifications.notSuspended()` in the list, `retrieveCandidates` filter in search, filter in `getTopDishes`. `report`: `applyFullLock` clears superseded dishes' flag, `lift` accepts REJECTED. Not touched: Redis-outage/`reserve()` (deferred), Django `backend/`, webhook/return authentication. **Tests rewritten** (old behavior pinned): `OrderControllerTest.preservedQuirk_anyAuthenticatedUserCanReadAndCancelAnyOrder` (removed, superseded by `OrderAuthorizationAndSuspensionTest`), `CartControllerTest.toggleSelect_anyAuthenticatedUser_canToggleAnotherUsersCartItem` (now 403 + 401 + owner ok), `CartServiceTest.toggleSelect_*` (new `user` argument), `VoucherControllerTest.createVoucher_plainCustomer_isNotBlocked...` (now 403; chef/admin ok; anon 401), `VoucherServiceTest` create tests (fixture users given CHEF role), `PaymentControllerTest.chefBalance_andCodSettlement` (now self/admin ok, others 403), `ReportCommandServiceTest.lift_onlyActiveOrAppealing_...` and `ReportControllerTest.admin_manualSuspension_fullLock_thenAppealRejected_isADeadEnd` (REJECTED can now be lifted). **Tests added:** `OrderAuthorizationAndSuspensionTest` 9, `PaymentControllerTest.paymentEndpoints_requireTheCheckoutOwner...`, `PayOsEscrowLifecycleTest.returnUrlReplay_...`, cart 4 (403 unit, 2 suspended unit, suspended full-stack), voucher 1 unit, report 2 unit, dish 1 full-stack. `docker version` ok; `./mvnw -q compile` clean; `./mvnw -q test` in the foreground — **966/966 passing** (from `target/surefire-reports/*.txt`; baseline 948).
- 2026-09-25 — Scheduled PayOS reconciliation job (post-port user decision, Sonnet 5 subagent). New, Spring-only (Django only syncs on demand). `payment/service/PaymentReconciliationService.reconcileOnce()`
  selects PENDING PayOS payments with an order code created in `[now-max-age, now-min-age]` (`PaymentTransactionRepository.findReconciliationCandidates`, id-ordered, `batch-size` per tick, in-memory
  round-robin id cursor so long-unpaid links do not starve the rest; no migration) and calls the EXISTING `PaymentService.syncPaymentByOrderCode` per payment (same path as webhook/return URL: PAID ->
  HOLDING + stock confirm + late-webhook recovery, CANCELLED/EXPIRED -> CANCELLED/FAILED). Not transactional itself (CLAUDE.md 8b); one payment's exception or `success=false` is caught/logged and the batch
  continues. `PaymentReconciliationScheduler` (`@Scheduled` fixedDelay + initial delay = interval; `@ConditionalOnProperty` like dish's sweep). Config `app.payment.reconciliation.*`
  (`PaymentProperties.Reconciliation`): `enabled` `PAYMENT_RECONCILIATION_ENABLED`=true, `interval` `PAYMENT_RECONCILIATION_INTERVAL`=PT60S, `min-age` `PAYMENT_RECONCILIATION_MIN_AGE`=PT2M,
  `max-age` `PAYMENT_RECONCILIATION_MAX_AGE`=PT24H, `batch-size` `PAYMENT_RECONCILIATION_BATCH_SIZE`=50. Tests switch the timer off via new shared `src/test/resources/application.properties`.
  **Race found by the concurrency test and fixed:** webhook + sync of the same PENDING payment both passed the unlocked guards and both wrote `STATE_CHANGED -> HOLDING` (money/stock stayed exactly-once:
  `confirm()` and notifications are idempotent, but the audit trail got a duplicate). `syncSuccessfulPayment` now claims PENDING -> HOLDING under `PaymentTransactionRepository.findForUpdateById`
  (row lock, state re-read after the lock; loser only refreshes the payload; a rejected transition still commits its STATE_REJECTED event and is rethrown). Residual (unchanged, flagged): `recordEvent`
  itself is still unserialized (Django's preserved event-chain fork race) and the lock is per DB row so multiple app instances are also safe for the claim, but the job itself has no leader election
  (two instances would just both poll PayOS; harmless, idempotent). Tests: `PaymentReconciliationServiceTest` 3 (Mockito: window/paging, error isolation, cursor wrap), `PaymentReconciliationTest` 8
  (full stack + `FakePayOsServer`, which gained `setFailLookup`: PAID -> HOLDING/CONFIRMED_SYSTEM/stock CONFIRMED like the webhook, too young/too old untouched, still-pending untouched, CANCELLED/EXPIRED,
  PayOS 500 for one payment doesn't stop the next, tick vs webhook race x4, tick after webhook no-op) = 11 new. `docker version` ok; `./mvnw -q compile` clean; `./mvnw -q test` in the foreground —
  **977/977 passing** (from `target/surefire-reports/*.txt`).
