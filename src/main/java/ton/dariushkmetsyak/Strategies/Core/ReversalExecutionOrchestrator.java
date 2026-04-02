package ton.dariushkmetsyak.Strategies.Core;

/**
 * Applies decision-engine actions via a pluggable execution adapter.
 * This creates a shared execution contract for live/backtest integration.
 */
public final class ReversalExecutionOrchestrator {

    public static final class PositionState {
        public boolean trading;
        public double buyPrice;
        public double tradingSum;
    }

    public static final class ExecutionResult {
        public final boolean executed;
        public final double executionPrice;
        public final ReversalDecisionEngine.Action action;

        public ExecutionResult(boolean executed, double executionPrice, ReversalDecisionEngine.Action action) {
            this.executed = executed;
            this.executionPrice = executionPrice;
            this.action = action;
        }
    }

    public ExecutionResult apply(
            ReversalDecisionEngine.Action action,
            PositionState position,
            double marketPrice,
            ReversalTradeExecutor executor
    ) {
        if (action == null || position == null || executor == null) {
            return new ExecutionResult(false, 0, action);
        }

        switch (action) {
            case BUY:
                if (position.trading || marketPrice <= 0 || position.tradingSum <= 0) {
                    return new ExecutionResult(false, 0, action);
                }
                double buyQty = position.tradingSum / marketPrice;
                Double buyPrice = executor.buy(marketPrice, buyQty);
                if (buyPrice == null) return new ExecutionResult(false, 0, action);
                position.trading = true;
                position.buyPrice = buyPrice;
                return new ExecutionResult(true, buyPrice, action);

            case SELL_PROFIT:
            case SELL_LOSS:
                if (!position.trading || position.buyPrice <= 0 || marketPrice <= 0) {
                    return new ExecutionResult(false, 0, action);
                }
                double sellQty = position.tradingSum / position.buyPrice;
                Double sellPrice = executor.sell(marketPrice, sellQty);
                if (sellPrice == null) return new ExecutionResult(false, 0, action);
                position.trading = false;
                return new ExecutionResult(true, sellPrice, action);

            default:
                return new ExecutionResult(false, 0, action);
        }
    }
}

