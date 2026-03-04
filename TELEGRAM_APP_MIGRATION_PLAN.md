# Telegram Mini App Migration (implemented MVP)

Implemented 5-step baseline:
1. Security/config: secrets moved to env-driven config (`RuntimeConfig`).
2. Core/API: added `TradingSessionManager` + `TelegramMiniAppApiServer`.
3. Mini App scaffold: `/miniapp` static HTML with start/stop/status controls.
4. Observability: preserved file logging + `APP_MODE`/`API_PORT` config.
5. Migration: bot now contains `Mini App` menu item and web-app launch button.

## Required env vars
- TELEGRAM_BOT_TOKEN
- BINANCE_MAIN_API_KEY
- BINANCE_MAIN_PRIVATE_KEY
- BINANCE_TEST_API_KEY
- BINANCE_TEST_PRIVATE_KEY
- APP_MODE=BOT|API|BOTH
- API_PORT=8080
- MINI_APP_URL=https://your-domain/miniapp
