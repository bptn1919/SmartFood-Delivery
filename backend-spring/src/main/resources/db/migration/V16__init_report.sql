-- Mirrors ../../backend/report/models.py (ChefReport, ChefSuspension, ChefWarning).
-- FK cascade as Django: reporter/order/dish/evidence/reviewed_by/lifted_by/locked_dish/warned_dish SET NULL,
-- chef CASCADE. unique(reporter, order, dish) = Django's unique_together (NULLs are distinct in Postgres,
-- like Django's own behaviour; the duplicate rule is enforced by the service, as in Django).
CREATE TABLE chef_report (
    id                  BIGSERIAL PRIMARY KEY,
    uid                 UUID NOT NULL UNIQUE,
    reporter_id         BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    chef_id             BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    order_uid           UUID REFERENCES "order" (uid) ON DELETE SET NULL,
    dish_uid            UUID REFERENCES dish (uid) ON DELETE SET NULL,
    evidence_uid        UUID REFERENCES attachment (uid) ON DELETE SET NULL,
    category            VARCHAR(20) NOT NULL,
    description         TEXT NOT NULL,
    credibility_weight  DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    ai_severity         VARCHAR(10),
    ai_food_safety_risk BOOLEAN,
    ai_severity_reason  TEXT,
    ai_analyzed_at      TIMESTAMPTZ,
    status              VARCHAR(12) NOT NULL DEFAULT 'PENDING',
    admin_note          TEXT,
    reviewed_by_id      BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    reviewed_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted             BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT unique_reporter_order_dish UNIQUE (reporter_id, order_uid, dish_uid)
);
CREATE INDEX idx_chef_report_chef_created ON chef_report (chef_id, created_at);
CREATE INDEX idx_chef_report_chef_status ON chef_report (chef_id, status);
CREATE INDEX idx_chef_report_dish_created ON chef_report (dish_uid, created_at);
CREATE INDEX idx_chef_report_reporter_created ON chef_report (reporter_id, created_at);
CREATE INDEX idx_chef_report_created ON chef_report (created_at);
CREATE INDEX idx_chef_report_status ON chef_report (status);

CREATE TABLE chef_suspension (
    id              BIGSERIAL PRIMARY KEY,
    uid             UUID NOT NULL UNIQUE,
    chef_id         BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    suspension_type VARCHAR(12) NOT NULL,
    locked_dish_uid UUID REFERENCES dish (uid) ON DELETE SET NULL,
    reason          TEXT NOT NULL,
    trigger_source  VARCHAR(8) NOT NULL DEFAULT 'SYSTEM',
    trigger_data    JSONB NOT NULL DEFAULT '{}'::jsonb,
    status          VARCHAR(12) NOT NULL DEFAULT 'ACTIVE',
    appeal_text     TEXT,
    appealed_at     TIMESTAMPTZ,
    lifted_by_id    BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    lifted_at       TIMESTAMPTZ,
    lift_note       TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_chef_suspension_chef_status ON chef_suspension (chef_id, status);
CREATE INDEX idx_chef_suspension_chef_created ON chef_suspension (chef_id, created_at);
CREATE INDEX idx_chef_suspension_status ON chef_suspension (status);

CREATE TABLE chef_warning (
    id               BIGSERIAL PRIMARY KEY,
    uid              UUID NOT NULL UNIQUE,
    chef_id          BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    warned_dish_uid  UUID REFERENCES dish (uid) ON DELETE SET NULL,
    warning_type     VARCHAR(16) NOT NULL DEFAULT 'FOOD_QUALITY',
    metrics_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    email_sent       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_chef_warning_chef_created ON chef_warning (chef_id, created_at);
CREATE INDEX idx_chef_warning_chef_type_created ON chef_warning (chef_id, warning_type, created_at);
