#!/usr/bin/env bash
set -euo pipefail

ENV_FILE=".env"
OPEN_EDITOR=false

for arg in "$@"; do
  case "$arg" in
    --edit)
      OPEN_EDITOR=true
      ;;
    --no-edit)
      OPEN_EDITOR=false
      ;;
    *)
      ENV_FILE="$arg"
      ;;
  esac
done

if [[ ! -f "$ENV_FILE" ]]; then
  cat > "$ENV_FILE" <<'EOT'
TELEGRAM_BOT_TOKEN=
BINANCE_MAIN_API_KEY=
BINANCE_MAIN_PRIVATE_KEY=
BINANCE_TEST_API_KEY=
BINANCE_TEST_PRIVATE_KEY=
APP_MODE=BOTH
API_PORT=8080
MINI_APP_URL=https://YOUR_DOMAIN/miniapp
LOG_DIR=/opt/cryptotrader/logs
USE_XVFB=false
EOT
  echo "Created template $ENV_FILE"
else
  echo "Using existing $ENV_FILE"
fi

if [[ "$OPEN_EDITOR" == "true" ]]; then
  if [[ -t 0 ]] && [[ -t 1 ]]; then
    echo "Opening $ENV_FILE for editing..."
    if [[ -n "${EDITOR:-}" ]] && command -v "$EDITOR" >/dev/null 2>&1; then
      "$EDITOR" "$ENV_FILE"
    elif command -v nano >/dev/null 2>&1; then
      nano "$ENV_FILE"
    elif command -v vi >/dev/null 2>&1; then
      vi "$ENV_FILE"
    else
      echo "No editor found. Please edit $ENV_FILE manually."
    fi
  else
    echo "Skipping editor: non-interactive shell detected."
  fi
else
  echo "Editor launch skipped by default. Use --edit to open editor."
fi

echo
echo "Validating variables..."
./scripts/check-env.sh "$ENV_FILE"

echo
echo "To apply for current shell:"
echo "set -a; source $ENV_FILE; set +a"
