# Быстрый деплой фикс-ветки без ошибок компиляции

Используй этот блок **как есть** на сервере:

```bash
cd /opt/cryptotrader/repo
set -e

git fetch origin --prune
git checkout -B feature/profile-and-fixes origin/feature/profile-and-fixes
git reset --hard origin/feature/profile-and-fixes
git clean -fd

# На случай локально повреждённого файла:
git restore --source origin/feature/profile-and-fixes -- src/main/java/ton/dariushkmetsyak/Web/TradingSessionManager.java

mvn clean package -DskipTests

sudo systemctl stop cryptotrader
cp target/CryptoTrader.jar /opt/cryptotrader/CryptoTrader.jar
sudo systemctl start cryptotrader

sudo systemctl status cryptotrader --no-pager
sudo journalctl -u cryptotrader -n 100 --no-pager
sudo journalctl -u cryptotrader -f
```

Если на шаге `mvn clean package -DskipTests` снова будут ошибки, значит на сервере реально не подтянулась ветка `origin/feature/profile-and-fixes` (или билдится не тот каталог).
