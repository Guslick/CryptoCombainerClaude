#!/usr/bin/env bash
set -euo pipefail

ENV_FILE="${1:-.env}"

if [[ -f "$ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  set -a; source "$ENV_FILE"; set +a
fi

required=(
  TELEGRAM_BOT_TOKEN
  BINANCE_MAIN_API_KEY
  BINANCE_MAIN_PRIVATE_KEY
  BINANCE_TEST_API_KEY
  BINANCE_TEST_PRIVATE_KEY
  APP_MODE
  API_PORT
  MINI_APP_URL
  LOG_DIR
)

missing=0
for key in "${required[@]}"; do
  if [[ -z "${!key:-}" ]]; then
    echo "[MISSING] $key"
    missing=1
  else
    echo "[OK] $key"
  fi
done

if [[ $missing -eq 1 ]]; then
  echo "\nSome required variables are missing."
  echo "Run: ./scripts/setup-env.sh $ENV_FILE"
  exit 1
fi

echo "\nAll required env vars are present."
