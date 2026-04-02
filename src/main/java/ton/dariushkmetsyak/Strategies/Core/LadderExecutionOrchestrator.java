package ton.dariushkmetsyak.Strategies.Core;

/**
 * Applies LadderDecisionEngine decisions using a pluggable executor.
 */
public class LadderExecutionOrchestrator {
    private final LadderDecisionEngine engine;
    private final LadderTradeExecutor executor;

    public LadderExecutionOrchestrator(LadderDecisionEngine engine, LadderTradeExecutor executor) {
        if (engine == null || executor == null) {
            throw new IllegalArgumentException("engine and executor must not be null");
        }
        this.engine = engine;
        this.executor = executor;
    }

    public LadderDecisionEngine.Decision onPrice(double currentPrice,
                                                 LadderDecisionEngine.Params params,
                                                 LadderDecisionEngine.State state) throws Exception {
        LadderDecisionEngine.Decision d = engine.evaluate(currentPrice, params, state);
        if (d.shouldBuy) {
            executor.buy(d.buyPrice, d.buyUsdt);
        }
        if (d.shouldSell) {
            executor.sell(d.sellPrice, d.sellQuantity);
        }
        return d;
    }
}
