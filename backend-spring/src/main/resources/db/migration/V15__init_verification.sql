-- Mirrors ../../backend/verification/models.py (ChefVerificationSession, ScheduledS3Deletion).
-- JSON fields are jsonb. Cross-module references are plain columns + FKs (the entity keeps ids only).
CREATE TABLE chef_verification_session (
    id                          BIGSERIAL PRIMARY KEY,
    user_id                     BIGINT NOT NULL UNIQUE REFERENCES app_user (id) ON DELETE CASCADE,

    cccd_status                 VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    cccd_attachment_uids        JSONB NOT NULL DEFAULT '[]'::jsonb,
    cccd_extracted              JSONB,
    cccd_confirmed              JSONB,

    business_status             VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    business_attachment_uids    JSONB NOT NULL DEFAULT '[]'::jsonb,
    business_extracted          JSONB,
    business_confirmed          JSONB,

    food_safety_status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    food_safety_attachment_uids JSONB NOT NULL DEFAULT '[]'::jsonb,
    food_safety_extracted       JSONB,
    food_safety_confirmed       JSONB,

    business_certificate_uid    UUID REFERENCES certificate (uid) ON DELETE SET NULL,
    food_safety_certificate_uid UUID REFERENCES certificate (uid) ON DELETE SET NULL,

    cross_validation_passed     BOOLEAN,
    cross_validation_errors     JSONB NOT NULL DEFAULT '[]'::jsonb,

    verification_code           VARCHAR(20),
    verification_code_expires_at TIMESTAMPTZ,
    selfie_attachment_uid       UUID REFERENCES attachment (uid) ON DELETE SET NULL,
    selfie_extracted            JSONB,

    face_similarity_score       DOUBLE PRECISION,

    risk_flags                  JSONB NOT NULL DEFAULT '[]'::jsonb,
    risk_score                  INTEGER NOT NULL DEFAULT 0,
    decision                    VARCHAR(20),

    status                      VARCHAR(25) NOT NULL DEFAULT 'IN_PROGRESS',

    cccd_number_masked          VARCHAR(20),
    cccd_number_hash            TEXT,
    verified_identity           JSONB,
    verified_at                 TIMESTAMPTZ,

    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_cvs_business_certificate ON chef_verification_session (business_certificate_uid);
CREATE INDEX idx_cvs_food_safety_certificate ON chef_verification_session (food_safety_certificate_uid);

CREATE TABLE scheduled_s3_deletion (
    id             BIGSERIAL PRIMARY KEY,
    attachment_uid UUID NOT NULL,
    s3_bucket      VARCHAR(255) NOT NULL,
    s3_key         TEXT NOT NULL,
    delete_after   TIMESTAMPTZ NOT NULL,
    is_executed    BOOLEAN NOT NULL DEFAULT FALSE,
    executed_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_s3del_attachment ON scheduled_s3_deletion (attachment_uid);
CREATE INDEX idx_s3del_delete_after ON scheduled_s3_deletion (delete_after);
CREATE INDEX idx_s3del_executed ON scheduled_s3_deletion (is_executed);
CREATE INDEX idx_s3del_executed_after ON scheduled_s3_deletion (is_executed, delete_after);
