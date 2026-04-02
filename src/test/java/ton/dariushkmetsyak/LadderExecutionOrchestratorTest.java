package ton.dariushkmetsyak;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import ton.dariushkmetsyak.Strategies.Core.LadderDecisionEngine;
import ton.dariushkmetsyak.Strategies.Core.LadderExecutionOrchestrator;
import ton.dariushkmetsyak.Strategies.Core.LadderTradeExecutor;

public class LadderExecutionOrchestratorTest {

    @Test
    void invokesExecutorForBuyAndSell() throws Exception {
        LadderDecisionEngine engine = new LadderDecisionEngine();
        LadderDecisionEngine.State state = new LadderDecisionEngine.State();
        LadderDecisionEngine.Params params = new LadderDecisionEngine.Params(100.0, 10.0);

        class CapturingExecutor implements LadderTradeExecutor {
            int buyCalls = 0;
            int sellCalls = 0;

            @Override
            public void buy(double price, double usdtAmount) {
                buyCalls++;
                Assertions.assertTrue(price > 0);
                Assertions.assertEquals(100.0, usdtAmount, 1e-9);
            }

            @Override
            public void sell(double price, double quantity) {
                sellCalls++;
                Assertions.assertTrue(price > 0);
                Assertions.assertTrue(quantity > 0);
            }
        }

        CapturingExecutor executor = new CapturingExecutor();
        LadderExecutionOrchestrator orchestrator = new LadderExecutionOrchestrator(engine, executor);

        orchestrator.onPrice(50000.0, params, state); // init
        orchestrator.onPrice(55000.0, params, state); // buy
        orchestrator.onPrice(50000.0, params, state); // buy
        orchestrator.onPrice(56000.0, params, state); // buy+sell

        Assertions.assertTrue(executor.buyCalls >= 3);
        Assertions.assertTrue(executor.sellCalls >= 1);
    }
}
