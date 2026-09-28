# Running backend-spring locally with FE-admin

## 1. Infra (Postgres + pgvector, Redis; no MongoDB)
```
docker compose -f docker-compose.dev.yml up -d      # host ports 15432 / 16379 (avoid clashing with local installs)
docker compose -f docker-compose.dev.yml down       # stop + discard data (no volumes)
```

## 2. Backend (port 8000)
```
export ATTACHMENT_STORAGE_BACKEND=local CORS_ALLOWED_ORIGINS=http://localhost:5173 \
       DB_URL=jdbc:postgresql://localhost:15432/amomeal REDIS_HOST=127.0.0.1 REDIS_PORT=16379
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```
- Flyway applies V1..V18 on the empty DB (about 3 s). Startup ~20 s.
- The `dev` profile only adds one thing: emails (incl. OTPs) are logged as `[DEV-MAIL] ...` because no SMTP is set.
  Grep the console for `DEV-MAIL` to read the OTP (forgot-password, email change, bank verify, withdraw).
- Leave `PAYOS_*`, `GEMINI_API_KEY`, `S3_*`, `EMAIL_*` unset (see "External credentials" below).
- Local uploads are written to `backend-spring/media/` and served at `/media/**`.

## 3. Seed accounts (backend must be running)
```
bash scripts/dev-seed.sh
```
Registers three users through `/api/auth/register` (so hashes are the app's own Argon2), grants ADMIN via
`scripts/dev-seed.sql`, and turns the chef into a real chef through `/api/auth/upgrade-to-chef`.
Password for all: `Dev@12345`

| account | roles |
|---|---|
| dev_admin@amomeal.test | ADMIN (+CUSTOMER) |
| dev_chef@amomeal.test | CHEF (+CUSTOMER), has chef profile + bank info |
| dev_customer@amomeal.test | CUSTOMER |

## 4. FE-admin (Vite, port 5173)
```
cd ../FE-admin && npm install
VITE_API_BASE_URL=http://localhost:8000 npm run dev
```
Without `VITE_API_BASE_URL` the code defaults to `http://localhost:5173` (the FE itself). Log in with dev_admin.
Note: ingredient Excel import uses a bare `fetch('/api/ingredients/import-excel')`, which only works through the Vite `/api`
proxy (-> 127.0.0.1:8000), not through `VITE_API_BASE_URL`; keep the proxy target at 8000.

## Notes for future sessions
- Dish creation needs an uploaded image (`attachment_uid` is required, as in Django) and a COUNTRY location
  (hierarchy REGION > SUBREGION > COUNTRY). CHEF-only endpoints (`/api/dishes/mine`, `/api/menus/mine`,
  ingredient `search`/`autocomplete`) answer 401 to an ADMIN token, exactly like Django.
- Search params are `query` (ingredients) / `q` (dishes). Validation errors are HTTP 401 `VALIDATION_ERROR` (Django quirk).
- Ports 5432/6379 may already be used by a local Postgres/Redis: that is why compose maps 15432/16379.

## External credentials still needed for full functionality
- PayOS (`PAYOS_*`): online payment link/webhook/return, chef withdrawal payout, reconciliation job.
- Gemini (`GEMINI_API_KEY`): CHEF verification document/selfie analysis, daily-nutrition meal parsing, report severity.
- S3 (`S3_*`, backend default): real image uploads; dev uses `ATTACHMENT_STORAGE_BACKEND=local`.
- SMTP (`EMAIL_*`): real mail delivery; dev logs it instead.
- AI model service (`AI_MODEL_BASE_URL`, PhoBERT): review issue classification (falls back to weight 0).
