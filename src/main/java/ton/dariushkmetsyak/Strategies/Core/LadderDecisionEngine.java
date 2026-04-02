package ton.dariushkmetsyak.Strategies.Core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure decision engine for the "Ladder" strategy.
 *
 * The engine only mutates in-memory state and returns deterministic decisions.
 * It never talks to exchange/network directly.
 */
public class LadderDecisionEngine {

    public static class Params {
        public final double orderUsdt;
        public final double priceStepPercent;

        public Params(double orderUsdt, double priceStepPercent) {
            this.orderUsdt = orderUsdt;
            this.priceStepPercent = priceStepPercent;
        }

        public double multiplier() {
            return 1.0 + (priceStepPercent / 100.0);
        }
    }

    public static class Lot {
        public final double buyPrice;
        public final double quantity;

        public Lot(double buyPrice, double quantity) {
            this.buyPrice = buyPrice;
            this.quantity = quantity;
        }
    }

    public static class State {
        public boolean initialized = false;
        public double basePrice = 0.0;
        public Integer lastExecutedStepIndex = null;
        public final List<Lot> openLots = new ArrayList<>();
    }

    public static class Decision {
        public boolean initializeOnly;

        public boolean shouldBuy;
        public double buyPrice;
        public double buyUsdt;

        public boolean shouldSell;
        public double sellPrice;
        public double sellQuantity;
    }

    public Decision evaluate(double currentPrice, Params params, State state) {
        if (currentPrice <= 0) {
            throw new IllegalArgumentException("currentPrice must be > 0");
        }
        if (params == null || state == null) {
            throw new IllegalArgumentException("params/state must not be null");
        }
        if (params.orderUsdt <= 0) {
            throw new IllegalArgumentException("orderUsdt must be > 0");
        }
        if (params.priceStepPercent <= 0) {
            throw new IllegalArgumentException("priceStepPercent must be > 0");
        }

        Decision d = new Decision();
        double k = params.multiplier();

        if (!state.initialized) {
            state.initialized = true;
            state.basePrice = currentPrice;
            state.lastExecutedStepIndex = stepIndex(currentPrice, state.basePrice, k);
            d.initializeOnly = true;
            return d;
        }

        int step = stepIndex(currentPrice, state.basePrice, k);
        if (state.lastExecutedStepIndex == null || step != state.lastExecutedStepIndex) {
            d.shouldBuy = true;
            d.buyPrice = currentPrice;
            d.buyUsdt = params.orderUsdt;

            double qty = params.orderUsdt / currentPrice;
            state.openLots.add(new Lot(currentPrice, qty));
            state.lastExecutedStepIndex = step;
        }

        Lot cheapest = state.openLots.stream().min(Comparator.comparingDouble(l -> l.buyPrice)).orElse(null);
        if (cheapest != null && currentPrice >= cheapest.buyPrice * k) {
            d.shouldSell = true;
            d.sellPrice = currentPrice;
            d.sellQuantity = cheapest.quantity;
            state.openLots.remove(cheapest);
        }

        return d;
    }

    static int stepIndex(double currentPrice, double basePrice, double k) {
        if (currentPrice >= basePrice) {
            return (int) Math.floor(Math.log(currentPrice / basePrice) / Math.log(k));
        }
        return -(int) Math.floor(Math.log(basePrice / currentPrice) / Math.log(k));
    }
}
