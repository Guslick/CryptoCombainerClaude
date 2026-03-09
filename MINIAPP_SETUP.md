# 🚀 CryptoCombainer MiniApp — Руководство по запуску

## 📋 Содержание
1. [Конфигурация](#конфигурация)
2. [Запуск локально в IntelliJ IDEA](#1-локальный-запуск-intellij-idea)
3. [Запуск как systemd сервис](#2-запуск-как-systemd-сервис)
4. [Развёртывание на сервере Ubuntu](#3-ubuntu-сервер)
5. [Настройка Telegram бота](#настройка-telegram-бота)
6. [Решение проблем](#решение-проблем)

---

## Конфигурация

Скопируйте файл примера и заполните своими данными:

```bash
cp config.properties.example config.properties
```

Отредактируйте `config.properties`:

```properties
telegram.bot.token=YOUR_BOT_TOKEN
telegram.default.chat.id=YOUR_CHAT_ID
gecko.api.key=YOUR_COINGECKO_KEY
binance.api.key=YOUR_BINANCE_KEY
binance.private.key.path=/path/to/Ed_PV.pem
binance.test.api.key=YOUR_TEST_KEY
binance.test.private.key.path=/path/to/test-prv-key.pem
web.server.port=8080
```

> ⚠️ **НИКОГДА** не коммитьте `config.properties` в git — он в `.gitignore`

---

## 1. Локальный запуск (IntelliJ IDEA)

### Шаг 1 — Запустить приложение

1. Откройте `ExecPack/App.java`
2. Нажмите ▶ Run
3. Приложение запустится: бот + веб-сервер на `http://localhost:8080`

### Шаг 2 — Открыть MiniApp в браузере (для разработки)

Просто откройте: **`http://localhost:8080`**

### Шаг 3 — Сделать MiniApp доступным из Telegram

Telegram требует **HTTPS**. Используйте **ngrok**:

```bash
# Установка ngrok (одноразово)
# https://ngrok.com/download или:
brew install ngrok/ngrok/ngrok  # macOS
# или скачайте с https://ngrok.com/download

# Авторизация (один раз)
ngrok config add-authtoken YOUR_NGROK_TOKEN

# Запуск туннеля
ngrok http 8080
```

Ngrok выдаст URL вида: `https://abc123.ngrok-free.app`

### Шаг 4 — Зарегистрировать MiniApp в Telegram

1. Откройте [@BotFather](https://t.me/BotFather) в Telegram
2. Отправьте `/mybots` → выберите вашего бота
3. Нажмите **Bot Settings** → **Menu Button** → **Configure menu button**
4. Введите URL: `https://abc123.ngrok-free.app` (ваш ngrok URL)
5. Введите название кнопки: `🚀 CryptoCombainer`
6. Теперь в боте появится кнопка меню, открывающая MiniApp!

### Альтернатива — использовать команду /webapp

Или через [@BotFather](https://t.me/BotFather):
```
/newapp
```
И указать URL ngrok.

---

## 2. Запуск как systemd сервис

### Шаг 1 — Сборка JAR

```bash
cd /путь/к/CryptoCombainerClaude
mvn clean package -DskipTests
# JAR создаётся в target/CryptoTrader.jar
```

### Шаг 2 — Создание директории

```bash
sudo mkdir -p /opt/cryptotrader
sudo cp target/CryptoTrader.jar /opt/cryptotrader/
sudo cp config.properties /opt/cryptotrader/
sudo cp coins /opt/cryptotrader/
sudo mkdir -p /opt/cryptotrader/logs
sudo mkdir -p /opt/cryptotrader/trading_states
```

### Шаг 3 — Создание systemd unit файла

```bash
sudo nano /etc/systemd/system/cryptotrader.service
```

Содержимое:

```ini
[Unit]
Description=CryptoCombainer Trading Bot + MiniApp
After=network.target

[Service]
Type=simple
User=your_username
WorkingDirectory=/opt/cryptotrader
ExecStart=/usr/bin/java -Xmx256m -DLOG_DIR=/opt/cryptotrader/logs -jar /opt/cryptotrader/CryptoTrader.jar
Restart=on-failure
RestartSec=10
StandardOutput=append:/opt/cryptotrader/logs/service.log
StandardError=append:/opt/cryptotrader/logs/service.log

[Install]
WantedBy=multi-user.target
```

### Шаг 4 — Запуск сервиса

```bash
sudo systemctl daemon-reload
sudo systemctl enable cryptotrader
sudo systemctl start cryptotrader
sudo systemctl status cryptotrader
```

### Шаг 5 — HTTPS через Caddy (для локального сервера с доменом)

```bash
# Установка Caddy
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update && sudo apt install caddy

# /etc/caddy/Caddyfile
your-domain.com {
    reverse_proxy localhost:8080
}
```

---

## 3. Ubuntu сервер

### Требования
- Ubuntu 22.04/24.04
- Java 11+
- Домен (для HTTPS)
- Nginx + Certbot

### Полная установка

```bash
# 1. Установка Java
sudo apt update
sudo apt install -y openjdk-11-jdk

# 2. Установка Nginx
sudo apt install -y nginx

# 3. Создание пользователя
sudo useradd -r -m -s /bin/false cryptotrader

# 4. Создание директорий
sudo mkdir -p /opt/cryptotrader/{logs,trading_states,backup}
sudo chown -R cryptotrader:cryptotrader /opt/cryptotrader

# 5. Копирование файлов (с локальной машины)
scp target/CryptoTrader.jar user@server:/opt/cryptotrader/
scp config.properties user@server:/opt/cryptotrader/
scp coins user@server:/opt/cryptotrader/
```

### Nginx конфигурация

```bash
sudo nano /etc/nginx/sites-available/cryptotrader
```

```nginx
server {
    server_name your-domain.com;

    location / {
        proxy_pass http://localhost:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_cache_bypass $http_upgrade;
        proxy_read_timeout 300s;
    }
}
```

```bash
sudo ln -s /etc/nginx/sites-available/cryptotrader /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl restart nginx
```

### Получение HTTPS сертификата

```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d your-domain.com
```

Certbot автоматически настроит HTTPS!

### systemd на сервере

```bash
sudo nano /etc/systemd/system/cryptotrader.service
```

```ini
[Unit]
Description=CryptoCombainer MiniApp
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=cryptotrader
Group=cryptotrader
WorkingDirectory=/opt/cryptotrader
ExecStart=/usr/bin/java -Xmx512m -DLOG_DIR=/opt/cryptotrader/logs -jar /opt/cryptotrader/CryptoTrader.jar
Restart=always
RestartSec=15
TimeoutStopSec=30

# Переменные окружения (альтернатива config.properties)
# Environment="TELEGRAM_BOT_TOKEN=your_token"
# Environment="WEB_SERVER_PORT=8080"

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now cryptotrader
```

---

## Настройка Telegram бота

### Создание нового бота (если ещё нет)

1. Напишите [@BotFather](https://t.me/BotFather)
2. `/newbot` → введите имя → получите токен
3. Скопируйте токен в `config.properties`

### Регистрация MiniApp в BotFather

```
/mybots
→ Выбрать бота
→ Bot Settings
→ Menu Button
→ Configure menu button
→ Введите URL: https://your-domain.com
→ Введите текст кнопки: 🚀 Трейдер
```

### Проверка работы

1. Откройте бота в Telegram
2. Нажмите кнопку меню (левый нижний угол)
3. Должен открыться MiniApp!

---

## Решение проблем

### MiniApp не открывается в Telegram
- Telegram требует **HTTPS**! HTTP не работает.
- Проверьте сертификат: `curl -v https://your-domain.com`

### Ошибка CORS
- Уже настроено в `MiniAppServer.java` для всех источников
- Если nginx, проверьте что `proxy_set_header` настроены

### Монеты не загружаются
- Файл `coins` должен быть в рабочей директории
- Или запустите с `gecko.api.key` — скачается автоматически

### Порт уже занят
```bash
sudo lsof -i :8080
sudo kill -9 PID
```

### Логи
```bash
# systemd
sudo journalctl -u cryptotrader -f

# Файловые логи
tail -f /opt/cryptotrader/logs/app-$(date +%Y%m%d).log
```

### Обновление на сервере

```bash
# Локально
mvn clean package -DskipTests
scp target/CryptoTrader.jar user@server:/opt/cryptotrader/

# На сервере
sudo systemctl restart cryptotrader
```

---

## 📱 Архитектура MiniApp

```
Telegram App
    └── Открывает MiniApp (HTTPS URL)
            └── index.html (из JAR)
                    └── REST API
                            ├── GET  /api/health
                            ├── GET  /api/coins?q=bitcoin
                            ├── GET  /api/price/{coinId}
                            ├── GET  /api/chart/{coinId}?interval=1d
                            ├── POST /api/trading/start
                            ├── POST /api/trading/stop
                            ├── GET  /api/trading/sessions
                            ├── POST /api/backtest/start
                            └── GET  /api/backtest/result
```
