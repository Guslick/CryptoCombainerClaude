# 🚀 Инструкция по автономному запуску CryptoTrader на Kubuntu 20.04

## 📋 Предварительные требования

### 1. Установить Java 11 или выше
```bash
sudo apt update
sudo apt install openjdk-11-jdk -y
java -version  # Проверка установки
```

### 2. Установить Maven (для сборки)
```bash
sudo apt install maven -y
mvn -version  # Проверка установки
```

### 3. Установить Xvfb (виртуальный дисплей для JavaFX)
```bash
sudo apt install xvfb -y
```

---

## 🔧 Сборка проекта

### 1. Перейти в директорию проекта
```bash
cd /путь/к/CryptoCombainerClaude
```

### 2. Собрать JAR с зависимостями
```bash
mvn clean package
```

После успешной сборки появится файл:
```
target/CryptoTrader.jar
```

---

## 📁 Установка приложения

### 1. Создать директорию приложения
```bash
sudo mkdir -p /opt/cryptotrader
sudo mkdir -p /opt/cryptotrader/logs
sudo mkdir -p /opt/cryptotrader/trading_states
```

### 2. Скопировать файлы
```bash
# JAR файл
sudo cp target/CryptoTrader.jar /opt/cryptotrader/

# Файл с криптовалютами
sudo cp coins /opt/cryptotrader/

# Startup скрипт
sudo cp start-cryptotrader.sh /opt/cryptotrader/
sudo chmod +x /opt/cryptotrader/start-cryptotrader.sh
```

### 3. Установить владельца файлов
```bash
sudo chown -R $USER:$USER /opt/cryptotrader
```

---

## ⚠️ ВАЖНО: Настройка API ключей

### Путь к ключам hardcoded в коде!

Откройте MenuHandler.java и найдите строки:
- **Binance MAINNET**: `/home/kmieciaki/Рабочий стол//Ed PV.pem`
- **Binance TESTNET**: `/home/kmieciaki/Рабочий стол//test-prv-key.pem`

**ДВА ВАРИАНТА:**

#### Вариант A: Создать точно такую же структуру
```bash
mkdir -p "/home/$USER/Рабочий стол"
cp /путь/к/вашим/ключам/Ed\ PV.pem "/home/$USER/Рабочий стол/"
cp /путь/к/вашим/ключам/test-prv-key.pem "/home/$USER/Рабочий стол/"
chmod 600 "/home/$USER/Рабочий стол/"*.pem
```

#### Вариант B: Изменить пути в коде
Отредактируйте `MenuHandler.java`:
```java
// Строка ~670 (для Binance)
final char[] Ed25519_PRIVATE_KEY = "/opt/cryptotrader/keys/mainnet.pem".toCharArray();

// Строка ~720 (для Binance Test)
char[] TEST_Ed25519_PRIVATE_KEY = "/opt/cryptotrader/keys/testnet.pem".toCharArray();
```

Затем пересоберите проект.

---

## 🤖 Настройка автозапуска через systemd

### 1. Отредактировать service файл
```bash
nano cryptotrader.service
```

**Замените:**
- `YOUR_USERNAME` на ваше имя пользователя (2 раза)
- Путь к Java если нужно: `/usr/lib/jvm/java-11-openjdk-amd64`

### 2. Установить service
```bash
sudo cp cryptotrader.service /etc/systemd/system/
sudo systemctl daemon-reload
```

### 3. Включить автозапуск
```bash
sudo systemctl enable cryptotrader.service
```

### 4. Запустить сервис
```bash
sudo systemctl start cryptotrader.service
```

---

## 📊 Управление сервисом

### Проверить статус
```bash
sudo systemctl status cryptotrader.service
```

### Просмотреть логи
```bash
# Логи systemd
sudo journalctl -u cryptotrader.service -f

# Логи приложения
tail -f /opt/cryptotrader/logs/app-$(date +%Y%m%d).log

# Логи systemd stdout/stderr
tail -f /opt/cryptotrader/logs/systemd-out.log
tail -f /opt/cryptotrader/logs/systemd-err.log
```

### Остановить сервис
```bash
sudo systemctl stop cryptotrader.service
```

### Перезапустить сервис
```bash
sudo systemctl restart cryptotrader.service
```

### Отключить автозапуск
```bash
sudo systemctl disable cryptotrader.service
```

---

## 🔍 Тестирование перед установкой в systemd

### Ручной запуск для проверки
```bash
cd /opt/cryptotrader
./start-cryptotrader.sh
```

Если всё работает — можно устанавливать в systemd.

---

## 🐛 Решение проблем

### Проблема: "JAR file not found"
```bash
ls -la /opt/cryptotrader/CryptoTrader.jar
```
Если файла нет — пересоберите проект.

### Проблема: "coins file not found"
```bash
ls -la /opt/cryptotrader/coins
cp /путь/к/проекту/coins /opt/cryptotrader/
```

### Проблема: JavaFX не работает headless
```bash
# Проверить Xvfb
ps aux | grep Xvfb

# Запустить вручную
Xvfb :99 -screen 0 1024x768x24 &
export DISPLAY=:99
```

### Проблема: Нет доступа к API ключам
```bash
# Проверить права доступа
ls -la "/home/$USER/Рабочий стол/"*.pem
chmod 600 "/home/$USER/Рабочий стол/"*.pem
```

### Проблема: Ошибки в логах
```bash
# Смотрим последние 50 строк
tail -50 /opt/cryptotrader/logs/app-$(date +%Y%m%d).log
```

---

## 📂 Структура файлов

```
/opt/cryptotrader/
├── CryptoTrader.jar          # Исполняемый файл
├── start-cryptotrader.sh     # Скрипт запуска
├── coins                      # Список криптовалют
├── logs/                      # Логи
│   ├── app-20260218.log
│   ├── systemd-out.log
│   └── systemd-err.log
└── trading_states/            # Сохранённые состояния
    └── bitcoin_binanceaccount.json
```

---

## ✅ Checklist после установки

- [ ] Java 11+ установлена
- [ ] JAR файл собран
- [ ] Файлы скопированы в /opt/cryptotrader
- [ ] API ключи доступны
- [ ] Xvfb установлен
- [ ] Service файл отредактирован (YOUR_USERNAME заменён)
- [ ] Сервис включен: `systemctl enable cryptotrader`
- [ ] Сервис запущен: `systemctl start cryptotrader`
- [ ] Логи проверены: нет ошибок
- [ ] Telegram бот отвечает

---

## 🔄 Обновление приложения

```bash
# 1. Остановить сервис
sudo systemctl stop cryptotrader.service

# 2. Пересобрать проект
cd /путь/к/CryptoCombainerClaude
git pull
mvn clean package

# 3. Скопировать новый JAR
sudo cp target/CryptoTrader.jar /opt/cryptotrader/

# 4. Запустить сервис
sudo systemctl start cryptotrader.service
```

---

## 📞 Проверка работы Telegram бота

После запуска откройте Telegram и отправьте боту:
```
/start
```

Если бот отвечает — всё работает! 🎉

---

## ⚡ Быстрая проверка статуса

```bash
# Однострочная команда для проверки всего
sudo systemctl status cryptotrader.service && \
echo "--- Recent Logs ---" && \
tail -20 /opt/cryptotrader/logs/app-$(date +%Y%m%d).log
```

## New runtime variables for Telegram Mini App mode

- `TELEGRAM_BOT_TOKEN` (required)
- `BINANCE_MAIN_API_KEY` (required for real Binance trading)
- `BINANCE_MAIN_PRIVATE_KEY` (required for real Binance trading)
- `BINANCE_TEST_API_KEY` (required for Binance test trading)
- `BINANCE_TEST_PRIVATE_KEY` (required for Binance test trading)
- `APP_MODE` = `BOT` | `API` | `BOTH` (default `BOTH`)
- `API_PORT` (default `8080`)
- `MINI_APP_URL` (default `http://localhost:8080/miniapp`)

Example:

```bash
export TELEGRAM_BOT_TOKEN=xxx
export BINANCE_MAIN_API_KEY=xxx
export BINANCE_MAIN_PRIVATE_KEY=xxx
export BINANCE_TEST_API_KEY=xxx
export BINANCE_TEST_PRIVATE_KEY=xxx
export APP_MODE=BOTH
export API_PORT=8080
export MINI_APP_URL=https://your-domain/miniapp
```

## Browser chart rendering (Mini App)

Now chart visualization is available in browser via Mini App, sourced from latest `trading_states/*.json` state snapshots.

- API endpoint: `GET /api/chart/latest`
- Mini App chart auto-refreshes every 10 seconds.
- `USE_XVFB=false` by default (legacy JavaFX screenshots can be enabled with `USE_XVFB=true`).

## Environment helper scripts

- Create/edit env template and validate:
  ```bash
  ./scripts/setup-env.sh .env
  ```
- Check env presence only:
  ```bash
  ./scripts/check-env.sh .env
  ```
- Apply env in current shell:
  ```bash
  set -a; source .env; set +a
  ```
