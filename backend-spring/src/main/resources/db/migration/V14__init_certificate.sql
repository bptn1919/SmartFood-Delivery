-- Mirrors ../../backend/certificate/models.py (Certificate, CertificateAttachment).
-- FK cascade: certificate.owner / verified_by SET_NULL; certificate_attachment.* CASCADE.
CREATE TABLE certificate (
    uid              UUID PRIMARY KEY,
    name             TEXT NOT NULL,
    description      TEXT,
    issued_by        TEXT NOT NULL,
    issue_date       DATE NOT NULL,
    expiration_date  DATE,
    owner_id         BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    certificate_type VARCHAR(50) NOT NULL,
    status           VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    verified_by_id   BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    verified_at      TIMESTAMPTZ,
    rejection_reason TEXT,
    name_no_accent   TEXT NOT NULL DEFAULT '',
    deleted          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_certificate_owner ON certificate (owner_id);
CREATE INDEX idx_certificate_status ON certificate (status);

CREATE TABLE certificate_attachment (
    id              BIGSERIAL PRIMARY KEY,
    certificate_uid UUID NOT NULL REFERENCES certificate (uid) ON DELETE CASCADE,
    attachment_uid  UUID NOT NULL REFERENCES attachment (uid) ON DELETE CASCADE,
    position        INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT unique_certificate_attachment UNIQUE (certificate_uid, attachment_uid),
    CONSTRAINT unique_certificate_position UNIQUE (certificate_uid, position)
);
