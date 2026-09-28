#!/usr/bin/env bash
# DEV ONLY. Needs the backend running on :8000 and the dev Postgres from docker-compose.dev.yml.
# Accounts (password for all: Dev@12345): dev_admin@ / dev_chef@ / dev_customer@amomeal.test
set -u
cd "$(dirname "$0")/.."
API=${API:-http://localhost:8000}
for r in admin chef customer; do
  n=$([ $r = admin ] && echo 1 || ([ $r = chef ] && echo 2 || echo 3))
  curl -s -o /dev/null -X POST $API/api/auth/register -H 'Content-Type: application/json' \
    -d "{\"username\":\"dev_$r\",\"email\":\"dev_$r@amomeal.test\",\"password\":\"Dev@12345\",\"phone_number\":\"090000000$n\"}"
done
docker compose -f docker-compose.dev.yml exec -T postgres psql -U postgres -d amomeal < scripts/dev-seed.sql
TOKEN=$(curl -s -X POST $API/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"dev_chef@amomeal.test","password":"Dev@12345"}' | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
curl -s -X POST $API/api/auth/upgrade-to-chef -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"chef_profile":{"bio":"Dev chef","specialty":"Vietnamese","kitchen_address":"1 Dev St","kitchen_city":"HCMC","kitchen_latitude":10.77,"kitchen_longitude":106.69,"is_accepting_orders":true},"chef_payment":{"bank_name":"Vietcombank","bank_code":"970436","bank_account_number":"0123456789","bank_account_name":"DEV CHEF"}}' | head -c 300
echo
