#!/usr/bin/env python3
"""
Оптимизатор параметров для Ethereum за 3 года
Автоматически загружает исторические данные через CoinGecko API
"""

import json
import numpy as np
from typing import Dict, List, Tuple
from dataclasses import dataclass
import os
from datetime import datetime

@dataclass
class BacktestResult:
    """Результаты бэктеста"""
    buy_gap: float
    sell_profit_gap: float
    sell_loss_gap: float
    total_profit: float
    profit_percent: float
    num_trades: int
    win_rate: float
    sharpe_ratio: float
    max_drawdown: float
    score: float


class EthereumOptimizer:
    """Оптимизатор для Ethereum с локальными данными"""
    
    def __init__(self, data_file: str, initial_capital: float = 100.0):
        self.data_file = data_file
        self.initial_capital = initial_capital
        self.results: List[BacktestResult] = []
        self.coin_id = "ethereum"
        
        # Загрузка данных из локального файла
        print(f"📂 Загрузка данных из {data_file}...")
        self.prices = self.load_local_data()
        print(f"✅ Загружено {len(self.prices)} точек цен")
        
        # Определение периода
        first_ts = min(p[0] for p in self.prices) / 1000
        last_ts = max(p[0] for p in self.prices) / 1000
        days = (last_ts - first_ts) / 86400
        print(f"📅 Период: {days:.0f} дней ({days/365:.1f} лет)\n")
    
    def load_local_data(self) -> List[Tuple[float, float]]:
        """Загрузка данных из локального JSON файла"""
        with open(self.data_file, 'r') as f:
            data = json.load(f)
            prices = data.get('prices', [])
            if not prices:
                raise ValueError("Файл не содержит данных о ценах")
            return prices
    
    def calculate_volatility(self, window: int = 168) -> float:
        """Рассчитать историческую волатильность"""
        price_values = [p[1] for p in self.prices]
        if len(price_values) < window:
            window = len(price_values)
        
        recent_prices = price_values[-window:]
        returns = np.diff(recent_prices) / np.array(recent_prices[:-1])
        return np.std(returns) * 100
    
    def simulate_strategy(self, buy_gap: float, sell_profit_gap: float, 
                          sell_loss_gap: float) -> BacktestResult:
        """Симуляция торговой стратегии"""
        
        capital = self.initial_capital
        position = 0
        buy_price = 0
        trades = []
        equity_curve = [capital]
        
        current_max = 0
        current_min = float('inf')
        
        for timestamp, price in self.prices:
            # Обновление экстремумов
            if price > current_max:
                current_max = price
            if price < current_min:
                current_min = price
            
            # Если в позиции - проверяем выход
            if position > 0:
                profit_pct = (price - buy_price) / buy_price * 100
                
                # Продажа в прибыль
                if profit_pct >= sell_profit_gap:
                    sell_value = position * price
                    capital += sell_value
                    trades.append({
                        'type': 'profit',
                        'buy_price': buy_price,
                        'sell_price': price,
                        'profit_pct': profit_pct
                    })
                    position = 0
                    current_max = price
                    current_min = price
                
                # Стоп-лосс
                elif profit_pct <= -sell_loss_gap:
                    sell_value = position * price
                    capital += sell_value
                    trades.append({
                        'type': 'loss',
                        'buy_price': buy_price,
                        'sell_price': price,
                        'profit_pct': profit_pct
                    })
                    position = 0
                    current_max = price
                    current_min = price
            
            # Если нет позиции - проверяем вход
            else:
                if current_max > 0:
                    drop_pct = (current_max - price) / current_max * 100
                    
                    if drop_pct >= buy_gap and capital >= self.initial_capital * 0.95:
                        position = (capital * 0.95) / price
                        buy_price = price
                        capital *= 0.05
                        current_max = price
                        current_min = price
            
            # Equity curve
            current_equity = capital + (position * price if position > 0 else 0)
            equity_curve.append(current_equity)
        
        # Закрытие позиции
        if position > 0:
            final_price = self.prices[-1][1]
            capital += position * final_price
            trades.append({
                'type': 'final',
                'buy_price': buy_price,
                'sell_price': final_price,
                'profit_pct': (final_price - buy_price) / buy_price * 100
            })
        
        # Метрики
        total_profit = capital - self.initial_capital
        profit_percent = (total_profit / self.initial_capital) * 100
        num_trades = len(trades)
        
        winning_trades = sum(1 for t in trades if t['profit_pct'] > 0)
        win_rate = (winning_trades / num_trades * 100) if num_trades > 0 else 0
        
        # Sharpe Ratio
        if len(equity_curve) > 1:
            returns = np.diff(equity_curve) / np.array(equity_curve[:-1])
            sharpe = np.mean(returns) / (np.std(returns) + 1e-6) * np.sqrt(252)
        else:
            sharpe = 0
        
        # Max Drawdown
        peak = np.maximum.accumulate(equity_curve)
        drawdown = (peak - equity_curve) / peak
        max_drawdown = np.max(drawdown) * 100 if len(drawdown) > 0 else 0
        
        # Комплексная оценка
        score = (
            profit_percent * 0.4 +
            sharpe * 10 * 0.3 +
            win_rate * 0.2 -
            max_drawdown * 0.1
        )
        
        return BacktestResult(
            buy_gap=buy_gap,
            sell_profit_gap=sell_profit_gap,
            sell_loss_gap=sell_loss_gap,
            total_profit=total_profit,
            profit_percent=profit_percent,
            num_trades=num_trades,
            win_rate=win_rate,
            sharpe_ratio=sharpe,
            max_drawdown=max_drawdown,
            score=score
        )
    
    def grid_search(self, buy_range: Tuple[float, float, float],
                   profit_range: Tuple[float, float, float],
                   loss_range: Tuple[float, float, float]) -> List[BacktestResult]:
        """Grid Search оптимизация"""
        results = []
        
        buy_vals = np.arange(*buy_range)
        profit_vals = np.arange(*profit_range)
        loss_vals = np.arange(*loss_range)
        
        total = len(buy_vals) * len(profit_vals) * len(loss_vals)
        count = 0
        
        print(f"🔍 Начинаем Grid Search: {total} комбинаций\n")
        
        for buy_gap in buy_vals:
            for sell_profit in profit_vals:
                for sell_loss in loss_vals:
                    result = self.simulate_strategy(buy_gap, sell_profit, sell_loss)
                    results.append(result)
                    
                    count += 1
                    if count % 100 == 0:
                        print(f"⏳ Прогресс: {count}/{total} ({count/total*100:.1f}%)")
        
        self.results = results
        return results
    
    def get_best_params(self, top_n: int = 10) -> List[BacktestResult]:
        """Топ-N результатов"""
        sorted_results = sorted(self.results, key=lambda x: x.score, reverse=True)
        return sorted_results[:top_n]
    
    def analyze_volatility_based_params(self) -> Dict[str, float]:
        """Рекомендации на основе волатильности"""
        volatility = self.calculate_volatility()
        
        print(f"\n📈 Историческая волатильность Ethereum: {volatility:.2f}%")
        
        if volatility < 2:
            recommended = {
                'buyGap': 1.5,
                'sellWithProfitGap': 1.0,
                'sellWithLossGap': 2.0,
                'description': 'Низкая волатильность - узкие gaps'
            }
        elif volatility < 5:
            recommended = {
                'buyGap': 3.5,
                'sellWithProfitGap': 2.0,
                'sellWithLossGap': 4.0,
                'description': 'Средняя волатильность'
            }
        else:
            recommended = {
                'buyGap': 6.0,
                'sellWithProfitGap': 3.5,
                'sellWithLossGap': 6.0,
                'description': 'Высокая волатильность - широкие gaps'
            }
        
        return recommended
    
    def print_report(self, top_n: int = 10):
        """Вывод отчета"""
        print("\n" + "="*80)
        print("📊 ОТЧЕТ ПО ОПТИМИЗАЦИИ ETHEREUM")
        print("="*80)
        
        # Период данных
        first_date = datetime.fromtimestamp(self.prices[0][0] / 1000)
        last_date = datetime.fromtimestamp(self.prices[-1][0] / 1000)
        print(f"\n📅 Период данных: {first_date.strftime('%Y-%m-%d')} → {last_date.strftime('%Y-%m-%d')}")
        print(f"📊 Точек данных: {len(self.prices)}")
        
        # Волатильность
        volatility_params = self.analyze_volatility_based_params()
        print(f"\n🎯 Рекомендации на основе волатильности:")
        print(f"   {volatility_params['description']}")
        print(f"   buyGap: {volatility_params['buyGap']:.1f}%")
        print(f"   sellWithProfitGap: {volatility_params['sellWithProfitGap']:.1f}%")
        print(f"   sellWithLossGap: {volatility_params['sellWithLossGap']:.1f}%")
        
        # Топ результаты
        best_results = self.get_best_params(top_n)
        
        print(f"\n🏆 ТОП-{top_n} КОМБИНАЦИЙ ПАРАМЕТРОВ:\n")
        
        for i, result in enumerate(best_results, 1):
            print(f"#{i}")
            print(f"  buyGap: {result.buy_gap:.1f}%")
            print(f"  sellWithProfitGap: {result.sell_profit_gap:.1f}%")
            print(f"  sellWithLossGap: {result.sell_loss_gap:.1f}%")
            print(f"  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            print(f"  💰 Прибыль: ${result.total_profit:.2f} ({result.profit_percent:.2f}%)")
            print(f"  📈 Сделок: {result.num_trades}")
            print(f"  ✅ Win Rate: {result.win_rate:.1f}%")
            print(f"  📊 Sharpe: {result.sharpe_ratio:.2f}")
            print(f"  📉 Max DD: {result.max_drawdown:.2f}%")
            print(f"  🎯 Score: {result.score:.2f}")
            print()
        
        # Рекомендация
        print("="*80)
        print("💡 РЕКОМЕНДАЦИЯ ДЛЯ ETHEREUM:")
        print("="*80)
        best = best_results[0]
        print(f"\nИспользуйте в Telegram боте:")
        print(f"ethereum, 100, {best.buy_gap:.1f}, {best.sell_profit_gap:.1f}, {best.sell_loss_gap:.1f}, 30")
        print()


def main():
    """Главная функция"""
    print("🚀 Оптимизация параметров для Ethereum\n")
    
    # Путь к локальным данным
    data_file = "/home/claude/CryptoCombainerFull/YearlyCharts/Ethereum/Yearlychart.json"
    
    if not os.path.exists(data_file):
        print(f"❌ Файл {data_file} не найден!")
        print("\n💡 Чтобы загрузить данные за 3 года:")
        print("1. В IntelliJ запустите: ton.dariushkmetsyak.Utils.DownloadThreeYearData")
        print("2. Или используйте имеющиеся данные за 1 год")
        return
    
    # Создание оптимизатора с локальными данными
    optimizer = EthereumOptimizer(data_file, initial_capital=100.0)
    
    # Диапазоны поиска
    buy_range = (0.5, 7.0, 0.5)
    profit_range = (0.5, 6.0, 0.5)
    loss_range = (1.0, 7.0, 0.5)
    
    # Оптимизация
    optimizer.grid_search(buy_range, profit_range, loss_range)
    
    # Отчет
    optimizer.print_report(top_n=10)
    
    # Сохранение результатов
    output_file = "ethereum_optimization_results.json"
    best_results = optimizer.get_best_params(50)
    
    results_dict = [
        {
            'buy_gap': r.buy_gap,
            'sell_profit_gap': r.sell_profit_gap,
            'sell_loss_gap': r.sell_loss_gap,
            'total_profit': r.total_profit,
            'profit_percent': r.profit_percent,
            'num_trades': r.num_trades,
            'win_rate': r.win_rate,
            'sharpe_ratio': r.sharpe_ratio,
            'max_drawdown': r.max_drawdown,
            'score': r.score
        }
        for r in best_results
    ]
    
    with open(output_file, 'w') as f:
        json.dump(results_dict, f, indent=2)
    
    print(f"\n💾 Результаты сохранены в {output_file}")
    print("\n✅ Оптимизация Ethereum завершена!")
    
    # Инструкция по загрузке данных за 3 года
    print("\n" + "="*80)
    print("📌 ИНСТРУКЦИЯ: Как получить данные за 3 года")
    print("="*80)
    print("\nТекущие данные: ~1 год")
    print("Для оптимизации на 3 годах:")
    print("\n1. В IntelliJ IDEA запустите:")
    print("   ton.dariushkmetsyak.Utils.DownloadThreeYearData")
    print("\n2. Это загрузит данные в:")
    print("   YearlyCharts/Ethereum/ThreeYearChart.json")
    print("\n3. Затем повторно запустите этот скрипт:")
    print("   python3 optimize_ethereum.py")
    print()


if __name__ == "__main__":
    main()
