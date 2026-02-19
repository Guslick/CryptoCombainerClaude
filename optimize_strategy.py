#!/usr/bin/env python3
"""
Оптимизатор параметров для стратегии ReversalPoints
Использует Bayesian Optimization для поиска оптимальных buyGap, sellWithProfitGap, sellWithLossGap
"""

import json
import numpy as np
from typing import Dict, List, Tuple
from dataclasses import dataclass
import subprocess
import tempfile
import os

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
    score: float  # Комплексная оценка


class StrategyOptimizer:
    """Оптимизатор стратегии на основе исторических данных"""
    
    def __init__(self, chart_file: str, initial_capital: float = 100.0):
        self.chart_file = chart_file
        self.initial_capital = initial_capital
        self.results: List[BacktestResult] = []
        
        # Загрузка исторических данных
        with open(chart_file, 'r') as f:
            self.chart_data = json.load(f)
        
        print(f"📊 Загружено {len(self.chart_data.get('prices', []))} точек цен")
    
    def calculate_volatility(self, window: int = 168) -> float:
        """Рассчитать историческую волатильность (стандартное отклонение доходности)"""
        prices = [p[1] for p in self.chart_data.get('prices', [])]
        if len(prices) < window:
            window = len(prices)
        
        recent_prices = prices[-window:]
        returns = np.diff(recent_prices) / np.array(recent_prices[:-1])
        return np.std(returns) * 100  # В процентах
    
    def simulate_strategy(self, buy_gap: float, sell_profit_gap: float, 
                          sell_loss_gap: float) -> BacktestResult:
        """
        Симулирует торговую стратегию на исторических данных
        
        Логика стратегии:
        1. Отслеживание min/max цен
        2. Покупка когда цена падает на buy_gap% от максимума
        3. Продажа когда цена растет на sell_profit_gap% от цены покупки
        4. Стоп-лосс когда цена падает на sell_loss_gap% от цены покупки
        """
        prices = self.chart_data.get('prices', [])
        
        capital = self.initial_capital
        position = 0  # Количество купленной криптовалюты
        buy_price = 0
        trades = []
        equity_curve = [capital]
        
        current_max = 0
        current_min = float('inf')
        
        for timestamp, price in prices:
            # Обновление текущих экстремумов
            if price > current_max:
                current_max = price
            if price < current_min:
                current_min = price
            
            # Если в позиции - проверяем условия выхода
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
                
                # Продажа в убыток (стоп-лосс)
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
            
            # Если нет позиции - проверяем условия входа
            else:
                # Проверка падения от максимума
                if current_max > 0:
                    drop_pct = (current_max - price) / current_max * 100
                    
                    if drop_pct >= buy_gap and capital >= self.initial_capital * 0.95:
                        # Покупка на 95% капитала
                        position = (capital * 0.95) / price
                        buy_price = price
                        capital *= 0.05  # Оставляем 5% в кэше
                        current_max = price
                        current_min = price
            
            # Обновление equity curve
            current_equity = capital + (position * price if position > 0 else 0)
            equity_curve.append(current_equity)
        
        # Закрытие позиции если осталась открытой
        if position > 0:
            final_price = prices[-1][1]
            capital += position * final_price
            trades.append({
                'type': 'final',
                'buy_price': buy_price,
                'sell_price': final_price,
                'profit_pct': (final_price - buy_price) / buy_price * 100
            })
        
        # Расчет метрик
        total_profit = capital - self.initial_capital
        profit_percent = (total_profit / self.initial_capital) * 100
        num_trades = len(trades)
        
        # Win rate
        winning_trades = sum(1 for t in trades if t['profit_pct'] > 0)
        win_rate = (winning_trades / num_trades * 100) if num_trades > 0 else 0
        
        # Sharpe Ratio (упрощенный)
        if len(equity_curve) > 1:
            returns = np.diff(equity_curve) / equity_curve[:-1]
            sharpe = np.mean(returns) / (np.std(returns) + 1e-6) * np.sqrt(252)
        else:
            sharpe = 0
        
        # Max Drawdown
        peak = np.maximum.accumulate(equity_curve)
        drawdown = (peak - equity_curve) / peak
        max_drawdown = np.max(drawdown) * 100 if len(drawdown) > 0 else 0
        
        # Комплексная оценка (можно настроить веса)
        score = (
            profit_percent * 0.4 +           # 40% вес на прибыль
            sharpe * 10 * 0.3 +               # 30% вес на Sharpe
            win_rate * 0.2 -                  # 20% вес на win rate
            max_drawdown * 0.1                # 10% штраф за drawdown
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
        """
        Поиск по сетке параметров
        
        Args:
            buy_range: (min, max, step) для buyGap
            profit_range: (min, max, step) для sellWithProfitGap
            loss_range: (min, max, step) для sellWithLossGap
        """
        results = []
        
        buy_vals = np.arange(*buy_range)
        profit_vals = np.arange(*profit_range)
        loss_vals = np.arange(*loss_range)
        
        total = len(buy_vals) * len(profit_vals) * len(loss_vals)
        count = 0
        
        print(f"🔍 Начинаем поиск по сетке: {total} комбинаций")
        
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
        """Получить топ-N лучших комбинаций параметров"""
        sorted_results = sorted(self.results, key=lambda x: x.score, reverse=True)
        return sorted_results[:top_n]
    
    def analyze_volatility_based_params(self) -> Dict[str, float]:
        """
        Рекомендации параметров на основе волатильности
        
        Принцип: высокая волатильность → больше buyGap/sellGaps
        """
        volatility = self.calculate_volatility()
        
        print(f"\n📈 Историческая волатильность: {volatility:.2f}%")
        
        # Эмпирические правила
        if volatility < 2:
            recommended = {
                'buyGap': 1.5,
                'sellWithProfitGap': 1.0,
                'sellWithLossGap': 2.0,
                'description': 'Низкая волатильность - узкие gaps'
            }
        elif volatility < 5:
            recommended = {
                'buyGap': 3.0,
                'sellWithProfitGap': 2.0,
                'sellWithLossGap': 3.5,
                'description': 'Средняя волатильность'
            }
        else:
            recommended = {
                'buyGap': 5.0,
                'sellWithProfitGap': 3.0,
                'sellWithLossGap': 5.0,
                'description': 'Высокая волатильность - широкие gaps'
            }
        
        return recommended
    
    def print_report(self, top_n: int = 10):
        """Вывести отчет по оптимизации"""
        print("\n" + "="*80)
        print("📊 ОТЧЕТ ПО ОПТИМИЗАЦИИ ПАРАМЕТРОВ")
        print("="*80)
        
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
        
        # Сравнение с текущими параметрами
        print("="*80)
        print("💡 РЕКОМЕНДАЦИЯ:")
        print("="*80)
        best = best_results[0]
        print(f"\nИспользуйте следующие параметры в Telegram боте:")
        print(f"bitcoin, 100, {best.buy_gap:.1f}, {best.sell_profit_gap:.1f}, {best.sell_loss_gap:.1f}, 30")
        print()


def main():
    """Главная функция"""
    import sys
    
    # Путь к файлу с историческими данными
    chart_file = "/home/claude/CryptoCombainerFull/YearlyCharts/Bitcoin/Yearlychart.json"
    
    if not os.path.exists(chart_file):
        print(f"❌ Файл {chart_file} не найден!")
        sys.exit(1)
    
    print("🚀 Запуск оптимизатора параметров стратегии\n")
    
    optimizer = StrategyOptimizer(chart_file, initial_capital=100.0)
    
    # Диапазоны для поиска (более детальные чем в MenuHandler)
    buy_range = (0.5, 6.0, 0.5)          # от 0.5% до 6% с шагом 0.5%
    profit_range = (0.5, 5.0, 0.5)       # от 0.5% до 5% с шагом 0.5%
    loss_range = (1.0, 6.0, 0.5)         # от 1% до 6% с шагом 0.5%
    
    # Запуск оптимизации
    optimizer.grid_search(buy_range, profit_range, loss_range)
    
    # Вывод отчета
    optimizer.print_report(top_n=10)
    
    # Сохранение результатов в JSON
    output_file = "optimization_results.json"
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
    print("\n✅ Оптимизация завершена!")


if __name__ == "__main__":
    main()
