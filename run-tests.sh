#!/usr/bin/env bash
# Юнит-тесты + HTML-отчёты (JaCoCo + surefire) в target/site/.
# Требуется запущенная локальная БД payments_db_test со схемой (./run-ddl.sh).
set -euo pipefail
cd "$(dirname "$0")"

export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"

# Интеграционные тесты идут на payments_db_test, обычная сборка/codegen — на payments_db.
export TEST_DB_URL="${TEST_DB_URL:-jdbc:postgresql://localhost:5432/payments_db_test}"
export TEST_DB_USER="${TEST_DB_USER:-$(id -un)}"
export TEST_DB_PASSWORD="${TEST_DB_PASSWORD:-}"

# Убеждаемся, что схема в тестовой БД есть, и начинаем с чистых таблиц.
./run-ddl.sh payments_db_test >/dev/null
/opt/homebrew/opt/postgresql@17/bin/psql -d payments_db_test -v ON_ERROR_STOP=1 \
  -c 'TRUNCATE TABLE bank CASCADE;' >/dev/null

mvn clean verify
mvn surefire-report:report-only

echo
echo "HTML-отчёты:"
echo "  target/site/jacoco/index.html"
echo "  target/site/surefire-report.html"