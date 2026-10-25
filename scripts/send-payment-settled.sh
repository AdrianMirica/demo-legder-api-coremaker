#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 PAYMENT_ID MERCHANT_ACCOUNT_UUID AMOUNT {EUR|USD|JPY}" >&2
  echo "Uses Compose service rabbitmq, or RABBITMQ_CONTAINER when set." >&2
  exit 2
}

[[ $# -eq 4 ]] || usage
payment_id=$1
merchant_id=$2
amount=$3
currency=$4
[[ -n "$payment_id" && ${#payment_id} -le 128 ]] || usage
[[ "$merchant_id" =~ ^[[:xdigit:]]{8}-[[:xdigit:]]{4}-[[:xdigit:]]{4}-[[:xdigit:]]{4}-[[:xdigit:]]{12}$ ]] || usage
[[ "$amount" =~ ^(0|[1-9][0-9]*)(\.[0-9]+)?$ ]] || usage
[[ ! "$amount" =~ ^0(\.0+)?$ ]] || usage
[[ "$currency" == EUR || "$currency" == USD || "$currency" == JPY ]] || usage

json_quote() {
  local value=$1 result='"' char code escaped index
  for ((index = 0; index < ${#value}; index++)); do
    char=${value:index:1}
    case "$char" in
      '"') result+='\"' ;;
      '\') result+='\\' ;;
      *)
        printf -v code '%d' "'$char"
        if ((code < 32)); then
          printf -v escaped '\\u%04x' "$code"
          result+=$escaped
        else
          result+=$char
        fi
        ;;
    esac
  done
  printf '%s"' "$result"
}

hex=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
event_id="${hex:0:8}-${hex:8:4}-4${hex:13:3}-8${hex:17:3}-${hex:20:12}"
payload=$(printf '{"eventId":"%s","paymentId":%s,"merchantAccountId":"%s","amount":%s,"currency":"%s"}' \
  "$event_id" "$(json_quote "$payment_id")" "$merchant_id" "$amount" "$currency")
properties=$(printf '{"content_type":"application/json","delivery_mode":2,"message_id":"%s"}' "$event_id")
printf 'eventId=%s\n%s\n' "$event_id" "$payload"

if [[ -n "${RABBITMQ_CONTAINER:-}" ]]; then
  docker_command=(docker exec "$RABBITMQ_CONTAINER")
else
  cd "$(dirname "$0")/.."
  docker_command=(docker compose exec -T rabbitmq)
fi

publish_status=0
publish_output=$("${docker_command[@]}" sh -eu -c '
  export RABBITMQADMIN_QUIET_MODE=false
  exec rabbitmqadmin \
    --username "${RABBITMQ_DEFAULT_USER:-guest}" \
    --password "${RABBITMQ_DEFAULT_PASS:-guest}" \
    --vhost "${RABBITMQ_DEFAULT_VHOST:-/}" \
    publish message \
    --exchange ledger.payments \
    --routing-key payment.settled.v1 \
    --payload "$1" \
    --properties "$2" \
    --non-interactive
' sh "$payload" "$properties" 2>&1) || publish_status=$?
printf '%s\n' "$publish_output"
if ((publish_status != 0)); then
  exit "$publish_status"
fi
case "$publish_output" in
  *"NOT routed"*)
    echo "Payment was not routed to a queue. Check the ledger.payments binding." >&2
    exit 1
    ;;
  *"Message published"*) ;;
  *)
    echo "Could not confirm payment routing from rabbitmqadmin output." >&2
    exit 1
    ;;
esac
