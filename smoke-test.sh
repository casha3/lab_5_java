#!/usr/bin/env bash
# Сквозной smoke-тест REST API. Поднимает сервер (если не запущен), прогоняет
# happy-path, реверс и ключевые ошибки, затем валидирует ожидаемые ответы.
set -euo pipefail
cd "$(dirname "$0")"

export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"

PORT="${HTTP_PORT:-8081}"
BASE="http://localhost:$PORT"
# 8-значные (валидные) BIC: константный 4-символьный префикс + уникальный 4-значный суффикс.
SN="$(date +%s%N | tail -c 5)"   # уникальный суффикс, чтобы BIC не конфликтовали
ISS="TSTB${SN}"; REC="TSTA${SN}"; ACQ="TSTC${SN}"; LOW="TSTD${SN}"

# ---- запуск сервера ----
if ! curl -sf "$BASE/api/health" >/dev/null; then
  export HTTP_PORT="$PORT"
  export DB_URL="${DB_URL:-jdbc:postgresql://localhost:5432/payments_db}"
  export DB_USER="${DB_USER:-$(id -un)}"
  export DB_PASSWORD="${DB_PASSWORD:-}"
  export COMMISSION_RATE="${COMMISSION_RATE:-1.50}"
  nohup java -jar payment-system.jar > target/smoke-server.log 2>&1 &
  SRV_PID=$!
  echo "server pid=$SRV_PID"
  for i in $(seq 1 30); do
    curl -sf "$BASE/api/health" >/dev/null && break
    sleep 0.5
  done
else
  SRV_PID=""
  echo "Найден уже работающий сервер на :$PORT"
fi

trap '[ -n "$SRV_PID" ] && kill "$SRV_PID" 2>/dev/null || true' EXIT

pass=0
fail=0
check() {
  local desc="$1" expected="$2" actual="$3"
  if [[ "$actual" == "$expected" ]]; then
    echo "  PASS: $desc"
    pass=$((pass+1))
  else
    echo "  FAIL: $desc (ожидали '$expected', получили '$actual')"
    fail=$((fail+1))
  fi
}

# ---- создание банков ----
mkbank() {
  local bic="$1" bal="$2"
  curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/banks" \
    -H 'Content-Type: application/json' \
    -d "{\"bic\":\"$bic\",\"name\":\"Bank $bic\",\"balance\":$bal}"
}
check "create issuer 201" 201 "$(mkbank "$ISS" 1000000)"
check "create receiver 201" 201 "$(mkbank "$REC" 1000000)"
check "create acquirer 201" 201 "$(mkbank "$ACQ" 1000000)"
check "create duplicate bank 409" 409 "$(mkbank "$ISS" 1000000)"

# ---- платёж ----
XML="<PaymentRequest xmlns=\"urn:payment:request:v1\"><clientReference>ref-$SN</clientReference><issuerBic>$ISS</issuerBic><receiverBic>$REC</receiverBic><acquirerBic>$ACQ</acquirerBic><amount>1200.00</amount></PaymentRequest>"
create_code=$(curl -s -o /tmp/smoke-create-body -w '%{http_code}' -X POST "$BASE/api/transactions" -H 'Content-Type: application/xml' -d "$XML")
create_resp=$(cat /tmp/smoke-create-body)
check "payment 201" 201 "$create_code"
echo "  created: $create_resp"
TID=$(echo "$create_resp" | sed -n 's/.*"transactionId":"\([^"]*\)".*/\1/p')
check "payment has transactionId" "$ISS" "$( [ -n "$TID" ] && echo "$ISS" || echo EMPTY )"

# ---- чтение ----
check "GET bank issuer 200" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/banks/$ISS")"
check "GET unknown bank 404" 404 "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/banks/NOPEN000")"
check "GET txn 200" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/transactions/$TID")"
check "GET txn status COMMITTED" "COMMITTED" "$(curl -s "$BASE/api/transactions/$TID" | sed -n 's/.*"status":"\([^"]*\)".*/\1/p')"
check "GET notification DEBIT 200" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/transactions/$TID/notifications/DEBIT_REQUEST")"
check "GET notification CREDIT 200" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/transactions/$TID/notifications/CREDIT_ADVICE")"

# ---- ошибки входных данных ----
check "reversal wrong media... non-xml payment 415" 415 "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/transactions" -H 'Content-Type: application/json' -d '{}')"
malformed="<foo</bar>"
check "malformed xml 400" 400 "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/transactions" -H 'Content-Type: application/xml' -d "$malformed")"
doctype='<?xml version="1.0"?><!DOCTYPE PaymentRequest [<!ENTITY x "y">]><PaymentRequest xmlns="urn:payment:request:v1"/>'
check "doctype 400 (XML_SECURITY_ERROR)" 400 "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/transactions" -H 'Content-Type: application/xml' -d "$doctype")"

# ---- недостаточно средств ----
mkbank "$LOW" 5 >/dev/null
lowxml="<PaymentRequest xmlns=\"urn:payment:request:v1\"><clientReference>r-low</clientReference><issuerBic>$LOW</issuerBic><receiverBic>$REC</receiverBic><acquirerBic>$ACQ</acquirerBic><amount>100.00</amount></PaymentRequest>"
low_body=$(curl -s -X POST "$BASE/api/transactions" -H 'Content-Type: application/xml' -d "$lowxml")
check "insufficient funds 409" "INSUFFICIENT_FUNDS" "$(echo "$low_body" | sed -n 's/.*"error":"\([^"]*\)".*/\1/p')"

# ---- реверс ----
check "reversal 200" 200 "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/transactions/$TID/reversal")"
check "GET txn status REVERSED" "REVERSED" "$(curl -s "$BASE/api/transactions/$TID" | sed -n 's/.*"status":"\([^"]*\)".*/\1/p')"
check "double reversal 409" "REVERSAL_ALREADY_APPLIED" "$(curl -s -X POST "$BASE/api/transactions/$TID/reversal" | sed -n 's/.*"error":"\([^"]*\)".*/\1/p')"
check "notification still 200 after reversal" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/transactions/$TID/notifications/CREDIT_ADVICE")"

echo
echo "ИТОГО: pass=$pass fail=$fail"
[ "$fail" -eq 0 ] || exit 1