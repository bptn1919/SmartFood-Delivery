-- Mirrors ../../backend/tracking/models.py + migrations/0001_initial.py (ChefLocation, db_table chef_locations).
-- chef is a OneToOne (unique) FK, CASCADE as Django; composite index on (latitude, longitude) as Django Meta.indexes.
CREATE TABLE chef_locations (
    id           BIGSERIAL PRIMARY KEY,
    chef_id      BIGINT NOT NULL UNIQUE REFERENCES app_user (id) ON DELETE CASCADE,
    latitude     DOUBLE PRECISION,
    longitude    DOUBLE PRECISION,
    heading      DOUBLE PRECISION,
    last_updated TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_chef_locations_lat_lng ON chef_locations (latitude, longitude);
