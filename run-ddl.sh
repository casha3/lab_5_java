#!/usr/bin/env bash
# Создание БД (payments_db для codegen/сервера и payments_db_test для интеграционных тестов)
# и применение схемы db/schema.sql (идемпотентно).
set -euo pipefail
cd "$(dirname "$0")"

PSQL="/opt/homebrew/opt/postgresql@17/bin/psql"
CREATEDB="/opt/homebrew/opt/postgresql@17/bin/createdb"

declare -a DBS
DBS=()

# Только явно запрошенные, иначе обе по умолчанию.
if [ $# -eq 0 ]; then
  DBS=("payments_db" "payments_db_test")
else
  DBS=("$@")
fi

for DB in "${DBS[@]}"; do
  if "$PSQL" -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$DB'" | grep -q 1; then
    echo "БД '$DB' уже существует"
  else
    "$CREATEDB" "$DB"
    echo "БД '$DB' создана"
  fi
  "$PSQL" -d "$DB" -v ON_ERROR_STOP=1 -f db/schema.sql
  echo "Схема применена к БД: $DB"
done
echo "Готово."