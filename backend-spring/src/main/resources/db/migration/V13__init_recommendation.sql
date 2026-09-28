-- Mirrors ../../backend/recommendation/models.py + migrations 0001-0007 (incl. the raw-SQL
-- pgvector tables of 0003_pgvector_ann_indexes.py). Fresh schema per CLAUDE.md §5 — field
-- list / constraints / FK cascade behavior ported faithfully, physical naming idiomatic.
--
-- FK cascade behavior mirrors Django's on_delete=:
--   user_food_preference_feature.user   CASCADE (OneToOneField)
--   user_daily_nutrition.user           CASCADE
--   daily_meal_log.daily_nutrition      CASCADE
--   daily_meal_log.dish                 SET_NULL (nullable)
--   recommendation_dish_vector_index    dish_uid -> dish(uid) ON DELETE CASCADE (raw SQL in Django too)
--   recommendation_user_vector_index    user_id  -> app_user(id) ON DELETE CASCADE
--
-- References already-migrated tables: app_user (V1), dish (V4).

CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------------------
-- UserFoodPreferenceFeature (BaseModel: uid/created_at/updated_at)
-- `embedding` (ArrayField(FloatField)) is dead in Django — never read or written anywhere
-- (the real vectors live in recommendation_user_vector_index below). Kept for field-list parity.
-- ---------------------------------------------------------------------------
CREATE TABLE user_food_preference_feature (
    uid                     UUID PRIMARY KEY,
    user_id                 BIGINT NOT NULL UNIQUE REFERENCES app_user (id) ON DELETE CASCADE,
    allergic_ingredient_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    diet_mode               VARCHAR(17) NOT NULL DEFAULT 'NONE',
    diet_level              VARCHAR(16) NOT NULL DEFAULT 'NONE',
    allergy_mode            VARCHAR(16) NOT NULL DEFAULT 'WARN',
    favorite_ingredient_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    favorite_dish_ids       JSONB NOT NULL DEFAULT '[]'::jsonb,
    embedding               DOUBLE PRECISION[],
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- UserDailyNutrition — unique_together = (user, date)
-- ---------------------------------------------------------------------------
CREATE TABLE user_daily_nutrition (
    uid                UUID PRIMARY KEY,
    user_id            BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    date               DATE NOT NULL,
    is_active          BOOLEAN NOT NULL DEFAULT TRUE,
    age                INTEGER NOT NULL DEFAULT 25 CHECK (age >= 0),
    gender             VARCHAR(16) NOT NULL DEFAULT 'OTHER',
    height_cm          DOUBLE PRECISION NOT NULL DEFAULT 170.0,
    weight_kg          DOUBLE PRECISION NOT NULL DEFAULT 65.0,
    activity_level     VARCHAR(16) NOT NULL DEFAULT 'LIGHT',
    goal               VARCHAR(16) NOT NULL DEFAULT 'MAINTAIN',
    bmr_kcal           DOUBLE PRECISION NOT NULL DEFAULT 0,
    tdee_kcal          DOUBLE PRECISION NOT NULL DEFAULT 0,
    target_protein_g   DOUBLE PRECISION NOT NULL DEFAULT 0,
    target_lipid_g     DOUBLE PRECISION NOT NULL DEFAULT 0,
    target_carb_g      DOUBLE PRECISION NOT NULL DEFAULT 0,
    target_sodium_mg   DOUBLE PRECISION NOT NULL DEFAULT 0,
    target_fiber_g     DOUBLE PRECISION NOT NULL DEFAULT 0,
    consumed_protein_g DOUBLE PRECISION NOT NULL DEFAULT 0,
    consumed_lipid_g   DOUBLE PRECISION NOT NULL DEFAULT 0,
    consumed_carb_g    DOUBLE PRECISION NOT NULL DEFAULT 0,
    consumed_sodium_mg DOUBLE PRECISION NOT NULL DEFAULT 0,
    consumed_fiber_g   DOUBLE PRECISION NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_user_daily_nutrition_user_date UNIQUE (user_id, date)
);

CREATE INDEX idx_user_daily_nutrition_date ON user_daily_nutrition (date);

-- ---------------------------------------------------------------------------
-- DailyMealLog
-- ---------------------------------------------------------------------------
CREATE TABLE daily_meal_log (
    uid                 UUID PRIMARY KEY,
    daily_nutrition_uid UUID NOT NULL REFERENCES user_daily_nutrition (uid) ON DELETE CASCADE,
    source              VARCHAR(16) NOT NULL DEFAULT 'PARSED',
    meal_time           VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    dish_uid            UUID REFERENCES dish (uid) ON DELETE SET NULL,
    meal_name           TEXT NOT NULL,
    quantity_multiplier DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    nutrition_protein_g DOUBLE PRECISION NOT NULL DEFAULT 0,
    nutrition_lipid_g   DOUBLE PRECISION NOT NULL DEFAULT 0,
    nutrition_carb_g    DOUBLE PRECISION NOT NULL DEFAULT 0,
    nutrition_sodium_mg DOUBLE PRECISION NOT NULL DEFAULT 0,
    nutrition_fiber_g   DOUBLE PRECISION NOT NULL DEFAULT 0,
    confidence_parse    DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    confidence_source   DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    raw_payload         JSONB NOT NULL DEFAULT '{}'::jsonb,
    source_ref          VARCHAR(64),
    is_deleted          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_daily_meal_log_daily ON daily_meal_log (daily_nutrition_uid);
CREATE INDEX idx_daily_meal_log_source_ref ON daily_meal_log (source_ref);
CREATE INDEX idx_daily_meal_log_is_deleted ON daily_meal_log (is_deleted);

-- ---------------------------------------------------------------------------
-- DishTranslationMapping (no API writes it in Django either — admin/DB-seeded data)
-- ---------------------------------------------------------------------------
CREATE TABLE dish_translation_mapping (
    uid                        UUID PRIMARY KEY,
    vietnamese_name            TEXT NOT NULL UNIQUE,
    normalized_vietnamese_name TEXT NOT NULL,
    english_name               TEXT,
    ingredients                JSONB NOT NULL DEFAULT '[]'::jsonb,
    nutrition_per_serving      JSONB NOT NULL DEFAULT '{}'::jsonb,
    serving_grams              DOUBLE PRECISION NOT NULL DEFAULT 100.0,
    usda_confidence            DOUBLE PRECISION NOT NULL DEFAULT 0.8,
    active                     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_dish_translation_mapping_normalized ON dish_translation_mapping (normalized_vietnamese_name);

-- ---------------------------------------------------------------------------
-- DishRecipeSnapshot (Gemini-generated recipe audit trail)
-- ---------------------------------------------------------------------------
CREATE TABLE dish_recipe_snapshot (
    uid              UUID PRIMARY KEY,
    dish_name        TEXT NOT NULL,
    normalized_name  TEXT NOT NULL,
    ingredients      JSONB NOT NULL DEFAULT '[]'::jsonb,
    source           VARCHAR(32) NOT NULL DEFAULT 'GEMINI',
    confidence_score DOUBLE PRECISION NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_dish_recipe_snapshot_normalized ON dish_recipe_snapshot (normalized_name);

-- ---------------------------------------------------------------------------
-- pgvector tables — verbatim from Django migration 0003 (only the referenced table names
-- differ: dish_dish -> dish, users_customuser -> app_user). vector(5) =
-- [protein, lipid, carbohydrate, sodium(g), fiber]. Accessed only through raw SQL
-- (JdbcTemplate), exactly like Django's connection.cursor() code.
-- ---------------------------------------------------------------------------
CREATE TABLE recommendation_dish_vector_index (
    dish_uid   UUID PRIMARY KEY REFERENCES dish (uid) ON DELETE CASCADE,
    embedding  vector(5) NOT NULL,
    confidence REAL NOT NULL DEFAULT 1.0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE recommendation_user_vector_index (
    user_id    BIGINT PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    embedding  vector(5) NOT NULL,
    confidence REAL NOT NULL DEFAULT 0.0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS rec_dish_vec_ivfflat_cos_idx
    ON recommendation_dish_vector_index
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

DO $$
BEGIN
    BEGIN
        CREATE INDEX IF NOT EXISTS rec_dish_vec_hnsw_cos_idx
            ON recommendation_dish_vector_index
            USING hnsw (embedding vector_cosine_ops);
    EXCEPTION
        WHEN OTHERS THEN
            NULL;
    END;
END
$$;
