#!/bin/bash

# CryptoTrader Startup Script
# Этот скрипт запускает криптотрейдинг бота с виртуальным дисплеем

# Путь к директории с приложением (замените на свой путь)
APP_DIR="/opt/cryptotrader"

# Путь к JAR файлу
JAR_FILE="$APP_DIR/CryptoTrader.jar"

# Путь к Java (если нужно указать конкретную версию)
JAVA_CMD="java"

# Параметры JVM
JVM_OPTS="-Xmx512m -Xms256m"

# Логирование
LOG_DIR="$APP_DIR/logs"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/app-$(date +%Y%m%d).log"
export LOG_DIR

# Автозагрузка переменных из .env (если файл существует)
if [ -f "$APP_DIR/.env" ]; then
    set -a
    # shellcheck disable=SC1091
    source "$APP_DIR/.env"
    set +a
fi

export APP_MODE="${APP_MODE:-BOTH}"
export API_PORT="${API_PORT:-8080}"
export MINI_APP_URL="${MINI_APP_URL:-http://localhost:${API_PORT}/miniapp}"
export USE_XVFB="${USE_XVFB:-false}"

# Проверка наличия JAR файла
if [ ! -f "$JAR_FILE" ]; then
    echo "ERROR: JAR file not found at $JAR_FILE" >&2
    exit 1
fi

# Проверка наличия файла coins
if [ ! -f "$APP_DIR/coins" ]; then
    echo "WARNING: coins file not found at $APP_DIR/coins" >&2
fi

# Переход в рабочую директорию
cd "$APP_DIR" || exit 1

echo "Starting CryptoTrader at $(date)" >> "$LOG_FILE"
echo "Working directory: $(pwd)" >> "$LOG_FILE"

# Виртуальный дисплей нужен только для legacy JavaFX screenshot rendering
if [ "$USE_XVFB" = "true" ]; then
    export DISPLAY=:99
    if ! pgrep -x "Xvfb" > /dev/null; then
        echo "Starting Xvfb virtual display..." >> "$LOG_FILE"
        Xvfb :99 -screen 0 1024x768x24 > /dev/null 2>&1 &
        sleep 2
    fi
fi

# Запуск приложения
echo "Launching Java application..." >> "$LOG_FILE"
$JAVA_CMD $JVM_OPTS -DLOG_DIR="$LOG_DIR" -DAPP_MODE="$APP_MODE" -DAPI_PORT="$API_PORT" -DMINI_APP_URL="$MINI_APP_URL" -jar "$JAR_FILE" >> "$LOG_FILE" 2>&1

# При завершении
EXIT_CODE=$?
echo "CryptoTrader stopped at $(date) with exit code $EXIT_CODE" >> "$LOG_FILE"
exit $EXIT_CODE
