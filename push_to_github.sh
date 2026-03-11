#!/bin/bash
# Скрипт для пуша изменений на GitHub
# Запускайте из корня вашего локального репозитория

set -e

echo "=== Добавляем изменённые файлы из Claude ==="

# Скачиваем изменения с ветки miniapp-feature на GitHub
# (Если Claude не смог запушить)

# Путь к клонированному репо Claude (передайте как аргумент или измените)
CLAUDE_REPO="${1:-/home/claude/CryptoCombainerClaude}"

if [ ! -d "$CLAUDE_REPO" ]; then
  echo "Репозиторий Claude не найден: $CLAUDE_REPO"
  echo "Укажите путь как аргумент: ./push_changes.sh /path/to/claude/repo"
  exit 1
fi

echo "Источник: $CLAUDE_REPO"
echo "Цель: $(pwd)"

# Добавляем новые файлы и директории
mkdir -p src/main/java/ton/dariushkmetsyak/Config
mkdir -p src/main/java/ton/dariushkmetsyak/Web
mkdir -p src/main/resources/static

# Копируем файлы
cp "$CLAUDE_REPO/src/main/java/ton/dariushkmetsyak/Config/AppConfig.java" \
   src/main/java/ton/dariushkmetsyak/Config/
cp "$CLAUDE_REPO/src/main/java/ton/dariushkmetsyak/Web/MiniAppServer.java" \
   src/main/java/ton/dariushkmetsyak/Web/
cp "$CLAUDE_REPO/src/main/java/ton/dariushkmetsyak/Web/TradingSessionManager.java" \
   src/main/java/ton/dariushkmetsyak/Web/
cp "$CLAUDE_REPO/src/main/resources/static/index.html" \
   src/main/resources/static/
cp "$CLAUDE_REPO/config.properties.example" .
cp "$CLAUDE_REPO/MINIAPP_SETUP.md" .
cp "$CLAUDE_REPO/.gitignore" .

# Копируем изменённые Java файлы
cp "$CLAUDE_REPO/src/main/java/ExecPack/App.java" \
   src/main/java/ExecPack/
cp "$CLAUDE_REPO/src/main/java/ton/dariushkmetsyak/GeckoApiService/GeckoRequests.java" \
   src/main/java/ton/dariushkmetsyak/GeckoApiService/
cp "$CLAUDE_REPO/src/main/java/ton/dariushkmetsyak/Strategies/ReversalPointsStrategy/ResearchingStrategy/ReversalPointStrategyBackTester.java" \
   src/main/java/ton/dariushkmetsyak/Strategies/ReversalPointsStrategy/ResearchingStrategy/
cp "$CLAUDE_REPO/src/main/java/ton/dariushkmetsyak/Telegram/MenuHandler.java" \
   src/main/java/ton/dariushkmetsyak/Telegram/

echo ""
echo "=== Создаём ветку и коммитим ==="
git checkout -b miniapp-feature 2>/dev/null || git checkout miniapp-feature
git add -A
git commit -m "feat: Complete Telegram MiniApp implementation"

echo ""
echo "=== Пушим на GitHub ==="
git push origin miniapp-feature

echo ""
echo "✅ Готово! Ветка miniapp-feature запушена на GitHub"
echo "Для слияния с master создайте Pull Request на GitHub"
