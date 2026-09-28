-- DEV ONLY. Grants ADMIN to the account registered by scripts/dev-seed.sh (which registers
-- users through POST /api/auth/register so the Argon2 hashes are the app's own, and makes the
-- chef via POST /api/auth/upgrade-to-chef so a chef_profile + payment info row exist).
-- Idempotent. Run by dev-seed.sh via: docker compose -f docker-compose.dev.yml exec -T postgres psql -U postgres -d amomeal
INSERT INTO app_user_role (user_id, role)
SELECT id, 'ADMIN' FROM app_user WHERE email = 'dev_admin@amomeal.test'
ON CONFLICT DO NOTHING;
SELECT u.email, string_agg(r.role, ',' ORDER BY r.role) AS roles
FROM app_user u JOIN app_user_role r ON r.user_id = u.id GROUP BY u.email ORDER BY u.email;
