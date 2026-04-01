package ton.dariushkmetsyak.Strategies.Core;

/**
 * Pure decision engine for Reversal strategy.
 * Contains no exchange/telegram/file I/O and can be unit-tested independently.
 */
public final class ReversalDecisionEngine {

    public enum Action {
        HOLD,
        BUY,
        SELL_PROFIT,
        SELL_LOSS
    }

    public static final class Params {
        public final double buyGap;
        public final double sellWithProfitGap;
        public final double sellWithLossGap;

        public Params(double buyGap, double sellWithProfitGap, double sellWithLossGap) {
            this.buyGap = buyGap;
            this.sellWithProfitGap = sellWithProfitGap;
            this.sellWithLossGap = sellWithLossGap;
        }
    }

    public static final class State {
        public boolean trading;
        public double buyPrice;
        public double currentMinPrice;
        public double currentMaxPrice;
        public double currentMinPriceTimestamp;
        public double currentMaxPriceTimestamp;
        public String lastReversalTag;
    }

    public static final class ReversalPoint {
        public final double timestamp;
        public final double price;
        public final String tag;

        public ReversalPoint(double timestamp, double price, String tag) {
            this.timestamp = timestamp;
            this.price = price;
            this.tag = tag;
        }
    }

    public static final class Decision {
        public final Action action;
        public final ReversalPoint newReversalPoint;

        public Decision(Action action, ReversalPoint newReversalPoint) {
            this.action = action;
            this.newReversalPoint = newReversalPoint;
        }
    }

    public State init(double timestamp, double price) {
        State state = new State();
        state.trading = false;
        state.buyPrice = 0;
        state.currentMinPrice = price;
        state.currentMaxPrice = price;
        state.currentMinPriceTimestamp = timestamp;
        state.currentMaxPriceTimestamp = timestamp;
        state.lastReversalTag = "initPoint";
        return state;
    }

    public Decision onTick(State state, Params params, double timestamp, double price) {
        if (state.trading) {
            if (((price - state.buyPrice) / state.buyPrice * 100) > params.sellWithProfitGap) {
                state.trading = false;
                return new Decision(Action.SELL_PROFIT, null);
            }
            if (((state.buyPrice - price) / state.buyPrice * 100) > params.sellWithLossGap) {
                state.trading = false;
                return new Decision(Action.SELL_LOSS, null);
            }
            return new Decision(Action.HOLD, null);
        }

        ReversalPoint reversalPoint = null;

        if (price > state.currentMaxPrice) {
            state.currentMaxPrice = price;
            state.currentMaxPriceTimestamp = timestamp;
            if (100 - (state.currentMinPrice / state.currentMaxPrice * 100) > params.buyGap
                    && !"min".equals(state.lastReversalTag)) {
                reversalPoint = new ReversalPoint(state.currentMinPriceTimestamp, state.currentMinPrice, "min");
                state.lastReversalTag = "min";
                state.currentMinPrice = price;
                state.currentMinPriceTimestamp = timestamp;
            }
            return new Decision(Action.HOLD, reversalPoint);
        }

        if (price < state.currentMinPrice) {
            state.currentMinPrice = price;
            state.currentMinPriceTimestamp = timestamp;
            if (100 - (state.currentMinPrice / state.currentMaxPrice * 100) > params.buyGap
                    && !"max".equals(state.lastReversalTag)) {
                reversalPoint = new ReversalPoint(state.currentMaxPriceTimestamp, state.currentMaxPrice, "max");
                state.lastReversalTag = "max";
                state.buyPrice = price;
                state.trading = true;
                state.currentMaxPrice = price;
                state.currentMaxPriceTimestamp = timestamp;
                return new Decision(Action.BUY, reversalPoint);
            }
            return new Decision(Action.HOLD, null);
        }

        return new Decision(Action.HOLD, null);
    }
}
