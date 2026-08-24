#!/usr/bin/env bash
# End-to-end happy path against a running service, using nothing but curl.
#
#   docker compose up -d postgres kafka
#   (cd service && ./gradlew bootRun)
#   ./scripts/demo.sh
#
set -euo pipefail

BASE_URL="${DOCVALIDATE_URL:-http://localhost:8080}"
KEY="demo-$(date +%s)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

say() { printf '\n\033[1m%s\033[0m\n' "$1"; }

say "0. Health"
curl -fsS "$BASE_URL/actuator/health" | jq -c .

say "1. Create a validation request"
CREATED=$(curl -fsS -X POST "$BASE_URL/api/v1/validations" -H "Idempotency-Key: $KEY")
echo "$CREATED" | jq .
REQUEST_ID=$(echo "$CREATED" | jq -r .requestId)
UPLOAD_URL=$(echo "$CREATED" | jq -r .uploadUrl)

say "2. Replay the same Idempotency-Key: same request, no second resource"
REPLAY_ID=$(curl -fsS -X POST "$BASE_URL/api/v1/validations" -H "Idempotency-Key: $KEY" | jq -r .requestId)
[ "$REPLAY_ID" = "$REQUEST_ID" ] && echo "   same requestId: $REPLAY_ID" || { echo "   MISMATCH: $REPLAY_ID"; exit 1; }

say "3. Upload the document"
printf 'invoice total: 42.00\nvat: 8.40\n' > "$TMP/march-invoice.pdf"
curl -fsS -X PUT "$UPLOAD_URL" \
  -H 'Content-Type: application/pdf' \
  -H 'Content-Disposition: attachment; filename="march-invoice.pdf"' \
  --data-binary "@$TMP/march-invoice.pdf" | jq -c '{status, document}'

say "4. Re-upload the identical bytes: accepted, no second job"
curl -fsS -o /dev/null -w '   HTTP %{http_code} (200 = replay, no state change)\n' \
  -X PUT "$UPLOAD_URL" \
  -H 'Content-Type: application/pdf' \
  -H 'Content-Disposition: attachment; filename="march-invoice.pdf"' \
  --data-binary "@$TMP/march-invoice.pdf"

say "5. Upload different bytes: refused, documents are immutable once accepted"
printf 'a different document\n' > "$TMP/other.pdf"
curl -sS -o "$TMP/conflict.json" -w '   HTTP %{http_code}\n' \
  -X PUT "$UPLOAD_URL" \
  -H 'Content-Type: application/pdf' \
  -H 'Content-Disposition: attachment; filename="march-invoice.pdf"' \
  --data-binary "@$TMP/other.pdf"
jq -c '{code, detail}' "$TMP/conflict.json"

say "6. Poll until terminal"
for _ in $(seq 1 30); do
  STATE=$(curl -fsS "$BASE_URL/api/v1/validations/$REQUEST_ID")
  STATUS=$(echo "$STATE" | jq -r .status)
  echo "   $STATUS"
  case "$STATUS" in COMPLETED|FAILED|EXPIRED) break ;; esac
  sleep 1
done

say "Result"
echo "$STATE" | jq '{requestId, status, result}'
