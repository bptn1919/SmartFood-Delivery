-- Mirrors ../../backend/review/models.py (Review, ReviewReply). Fresh schema per
-- CLAUDE.md §5 -- field list/constraints/relationships ported faithfully, physical naming is
-- idiomatic snake_case.
--
-- FK cascade behavior mirrors Django's on_delete=:
--   review.dish             CASCADE   -> ON DELETE CASCADE
--   review.order            SET_NULL  -> ON DELETE SET NULL (nullable FK)
--   review.owner            SET_NULL  -> ON DELETE SET NULL (nullable FK)
--   review.attachment       SET_NULL  -> ON DELETE SET NULL (nullable FK)
--   review_reply.review     CASCADE   -> ON DELETE CASCADE (Django OneToOneField)
--   review_reply.owner      SET_NULL  -> ON DELETE SET NULL (nullable FK)
--
-- References already-migrated tables: app_user (V1), attachment (V2), dish (V4), "order" (V10).
--
-- Django's rating validators (MinValueValidator(1)/MaxValueValidator(5)) are Python-level
-- form/serializer validators, never enforced as a DB CheckConstraint in Django's own Meta
-- and never invoked at Review.objects.create() time either (no full_clean() call) -- the real
-- enforcement is ReviewService's explicit range check. No DB CHECK constraint added here either,
-- to stay behaviorally faithful (see Review's class javadoc).

CREATE TABLE review (
    uid            UUID PRIMARY KEY,
    rating         INTEGER NOT NULL,
    comment        TEXT,
    weight         DOUBLE PRECISION NOT NULL DEFAULT 0,
    issue          VARCHAR(100),
    dish_uid       UUID NOT NULL REFERENCES dish (uid) ON DELETE CASCADE,
    order_uid      UUID REFERENCES "order" (uid) ON DELETE SET NULL,
    owner_id       BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    attachment_uid UUID REFERENCES attachment (uid) ON DELETE SET NULL,
    deleted        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Django: Meta.unique_together = [["owner", "dish", "order"]]. Postgres treats multiple
    -- NULLs in a unique index as distinct, matching Django's own Postgres behavior for the
    -- nullable order_uid column.
    CONSTRAINT uk_review_owner_dish_order UNIQUE (owner_id, dish_uid, order_uid)
);

CREATE INDEX idx_review_dish ON review (dish_uid);
CREATE INDEX idx_review_owner ON review (owner_id);
CREATE INDEX idx_review_order ON review (order_uid);
-- Backs ReviewAnalyticsService's chef-scoped issue queries (dish.owner_id join + the
-- deleted/weight>0/issue-not-null/created_at-window predicate ReviewORM.get_chef_issue_stats
-- and AnalyticsService._base_issue_queryset both use).
CREATE INDEX idx_review_issue_window ON review (dish_uid, created_at) WHERE deleted = FALSE AND weight > 0;

-- Django: ReviewReply is a BaseModel too (UUID uid PK), with a OneToOneField(Review, CASCADE) --
-- ported as a UNIQUE NOT NULL FK, same as `payment`'s other 1:1 relations in this port.
CREATE TABLE review_reply (
    uid        UUID PRIMARY KEY,
    review_uid UUID NOT NULL UNIQUE REFERENCES review (uid) ON DELETE CASCADE,
    content    TEXT NOT NULL,
    owner_id   BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_review_reply_owner ON review_reply (owner_id);
