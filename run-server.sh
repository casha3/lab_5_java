#!/usr/bin/env bash
# Запуск платёжного сервера. Параметры БД/порта/ставки из переменных окружения.
set -euo pipefail
cd "$(dirname "$0")"

export HTTP_PORT="${HTTP_PORT:-8080}"
export DB_URL="${DB_URL:-jdbc:postgresql://localhost:5432/payments_db}"
export DB_USER="${DB_USER:-$(id -un)}"
export DB_PASSWORD="${DB_PASSWORD:-}"
export COMMISSION_RATE="${COMMISSION_RATE:-1.50}"

echo "HTTP_PORT=$HTTP_PORT DB_URL=$DB_URL COMMISSION_RATE=$COMMISSION_RATE"
exec java -jar payment-system.jar