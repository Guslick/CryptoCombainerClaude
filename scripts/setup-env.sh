#!/usr/bin/env bash
set -euo pipefail

ENV_FILE="${1:-.env}"

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
fi

echo "Opening $ENV_FILE for editing..."
if command -v nano >/dev/null 2>&1; then
  nano "$ENV_FILE"
else
  echo "Please edit $ENV_FILE manually."
fi

echo "\nValidating variables..."
./scripts/check-env.sh "$ENV_FILE"

echo "\nTo apply for current shell:"
echo "set -a; source $ENV_FILE; set +a"
