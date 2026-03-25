# ATR+EMA Adaptive Reversal Strategy

## Overview

ATR+EMA is an evolution of the Reversal Points strategy that adds two key improvements:
1. **Adaptive thresholds** based on market volatility (ATR)
2. **Trend filtering** using Exponential Moving Average (EMA)

The core reversal-point detection mechanism remains the same, but buy/sell thresholds dynamically adjust to current market conditions.

---

## Indicators

### ATR (Average True Range, period: 14)
Measures market volatility. Calculated as a smoothed average of True Range over the last 14 price points.

**True Range** = max(|high - low|, |high - prevClose|, |low - prevClose|)

For single-price feeds (no OHLC), TR simplifies to |close - prevClose|.

### EMA-50 (Exponential Moving Average, period: 50)
Identifies the prevailing trend.
- **Price > EMA** = uptrend (favorable for buying)
- **Price < EMA** = downtrend (avoid buying, consider exiting)

### ATR Multiplier
The ratio of current ATR to its long-term average (smoothing period: 100).

```
ATR_multiplier = current_ATR / avg_ATR
```

Clamped to [0.5, 2.0] to prevent extreme adjustments.

---

## Signal Logic

### BUY Signal
All conditions must be true:
1. Price dropped more than `adaptive_buyGap%` from the recent local maximum
2. Price is **above EMA-50** (we're in an uptrend)
3. Not currently holding a position

```
adaptive_buyGap = base_buyGap * ATR_multiplier
```

**Rationale:** In high volatility, we require a deeper pullback before buying. In low volatility, we enter on smaller dips.

### SELL Signal (Profit)
- Price rose more than `adaptive_sellProfitGap%` above the buy price

```
adaptive_sellProfitGap = base_sellProfitGap * ATR_multiplier
```

**Rationale:** In high volatility, we give profits more room to grow. In low volatility, we take profits earlier.

### SELL Signal (Stop-Loss)
Either condition triggers:
1. Price dropped more than `adaptive_sellLossGap%` below the buy price
2. **Trend break:** Price crossed below EMA-50 (regardless of loss percentage)

```
adaptive_sellLossGap = base_sellLossGap * ATR_multiplier
```

**Rationale:** In high volatility, wider stops prevent noise-induced exits. The EMA trend-break exit provides a safety net when the market structure changes.

---

## Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `tradingSum` | 100 USDT | Amount per trade |
| `startAssets` | 150 USDT | Starting balance (tester/research) |
| `baseBuyGap` | 3.5% | Base drop threshold for buy signal |
| `baseSellProfitGap` | 2.0% | Base profit target |
| `baseSellLossGap` | 8.0% | Base stop-loss threshold |
| `atrPeriod` | 14 | ATR calculation period |
| `emaPeriod` | 50 | EMA calculation period |
| `atrSmoothingPeriod` | 100 | Period for average ATR calculation |

**Note:** The user sets base gaps (same inputs as Reversal Points). The strategy automatically adjusts them using the ATR multiplier.

---

## Example Scenarios

### High Volatility Market
```
base_buyGap = 3%, current ATR = 2.5%, avg ATR = 2.0%
ATR_multiplier = 2.5 / 2.0 = 1.25

adaptive_buyGap = 3% * 1.25 = 3.75%
adaptive_sellProfitGap = 2% * 1.25 = 2.50%
adaptive_sellLossGap = 8% * 1.25 = 10.0%

Result: Deeper entry, wider profit target, wider stop-loss
```

### Low Volatility Market
```
base_buyGap = 3%, current ATR = 1.2%, avg ATR = 2.0%
ATR_multiplier = 1.2 / 2.0 = 0.60

adaptive_buyGap = 3% * 0.60 = 1.80%
adaptive_sellProfitGap = 2% * 0.60 = 1.20%
adaptive_sellLossGap = 8% * 0.60 = 4.80%

Result: Earlier entry, tighter profit target, tighter stop-loss
```

---

## Advantages vs. Reversal Points

| Aspect | Reversal Points | ATR+EMA |
|--------|----------------|---------|
| Thresholds | Fixed | Adaptive to volatility |
| Trend awareness | None | EMA-50 filter |
| False signals in calm markets | Many (fixed gaps too wide) | Fewer (gaps shrink) |
| Missed entries in volatile markets | Many (fixed gaps too tight) | Fewer (gaps expand) |
| Trend-break protection | None | Auto-exit below EMA |
| Parameters | Same | Same inputs, auto-adjusted |

---

## Architecture

```
Strategies/AtrEmaStrategy/
  AtrEmaTrader.java      - Live trading (tester + Binance)
  AtrEmaBackTester.java   - Historical backtesting

TradingSessionManager.java
  startTesterTradingAtrEma()
  startResearchTradingAtrEma()
  startBinanceTradingAtrEma()
  startBinanceTestTradingAtrEma()
  startBacktestAtrEma()
  startTop10SearchAtrEma()
```

All modes are supported: Tester, Research, Binance Real, Binance Testnet, Backtest, and Top-10 Optimizer.

---

## Usage

1. Go to **Strategies** page
2. Select **ATR+EMA Adaptive** (or with Recapitalization)
3. Set parameters (same as Reversal Points)
4. Use any mode: Trade, Research, Backtest, or Optimizer
5. ATR and EMA are calculated automatically from market data
