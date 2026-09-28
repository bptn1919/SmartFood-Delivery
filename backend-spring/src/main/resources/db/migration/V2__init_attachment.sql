-- Mirrors ../../backend/attachment/models.py (Attachment, AttachmentType) — a shared
-- file/image attachment record consumed by dish, certificate, profile, review,
-- report, ingredient, recommendation and verification (see cross-app grep notes in
-- com.amomeal.marketplace.attachment package javadoc / PROGRESS.md).
--
-- PORT-NOTE (storage backend, flagged for human review — see PROGRESS.md):
-- Django backs this with S3 (boto3 presigned PUT + head_object) when USE_S3=True,
-- and local filesystem storage (MEDIA_ROOT/MEDIA_URL) when USE_S3=False
-- (../../backend/marketplace/settings.py lines ~329-368). This port defaults to
-- LOCAL filesystem storage, matching Django's non-S3 branch — see
-- com.amomeal.marketplace.attachment.service.AttachmentStorageService (the pluggable
-- interface) and LocalAttachmentStorageService (the only implementation so far).
-- S3 can be added later as a second AttachmentStorageService implementation without
-- touching the entity/service business logic.
--
-- The `upload_token` column below has NO Django equivalent: S3 presigned URLs are
-- self-authorizing (the signature embedded in the URL IS the credential), so Django
-- never needed a separate token. Local storage has no such built-in mechanism, so
-- this port generates its own opaque capability token per attachment and embeds it
-- in the upload URL returned from POST /api/attachments/presigned-url, checked by
-- PUT /api/attachments/{uid}/upload (the local substitute for "PUT straight to S3").

CREATE TABLE attachment (
    uid              UUID PRIMARY KEY,
    type             VARCHAR(255) NOT NULL,
    original_name    TEXT NOT NULL,
    hashed_name      TEXT NOT NULL,
    size             INTEGER NOT NULL,
    content_type     VARCHAR(255) NOT NULL,
    bucket           VARCHAR(255) NOT NULL,
    directory        TEXT NOT NULL,
    is_public        BOOLEAN NOT NULL DEFAULT TRUE,
    public_url       TEXT NOT NULL,
    upload_token     VARCHAR(64),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_file_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    is_deleted       BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at       TIMESTAMPTZ,
    is_completed     BOOLEAN NOT NULL DEFAULT FALSE,
    owner_id         BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    updater_id       BIGINT REFERENCES app_user (id) ON DELETE SET NULL
);

CREATE INDEX idx_attachment_owner_id ON attachment (owner_id);
CREATE INDEX idx_attachment_type ON attachment (type);
CREATE INDEX idx_attachment_active_lookup ON attachment (uid, is_deleted, is_file_deleted);
