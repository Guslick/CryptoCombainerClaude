package ton.dariushkmetsyak;

import org.junit.jupiter.api.Test;
import ton.dariushkmetsyak.Strategies.Core.ReversalDecisionEngine;
import ton.dariushkmetsyak.Strategies.Core.ReversalExecutionOrchestrator;
import ton.dariushkmetsyak.Strategies.Core.ReversalTradeExecutor;

import static org.junit.jupiter.api.Assertions.*;

public class ReversalExecutionOrchestratorTest {

    static class MockExecutor implements ReversalTradeExecutor {
        int buys = 0;
        int sells = 0;

        @Override
        public Double buy(double price, double quantity) {
            buys++;
            return price;
        }

        @Override
        public Double sell(double price, double quantity) {
            sells++;
            return price;
        }
    }

    @Test
    void shouldExecuteBuyThenSellViaUnifiedContract() {
        ReversalExecutionOrchestrator orchestrator = new ReversalExecutionOrchestrator();
        ReversalExecutionOrchestrator.PositionState position = new ReversalExecutionOrchestrator.PositionState();
        position.tradingSum = 100;

        MockExecutor executor = new MockExecutor();

        ReversalExecutionOrchestrator.ExecutionResult buy = orchestrator.apply(
                ReversalDecisionEngine.Action.BUY, position, 100, executor
        );
        assertTrue(buy.executed);
        assertTrue(position.trading);
        assertEquals(100, position.buyPrice, 1e-9);
        assertEquals(1, executor.buys);

        ReversalExecutionOrchestrator.ExecutionResult sell = orchestrator.apply(
                ReversalDecisionEngine.Action.SELL_PROFIT, position, 103, executor
        );
        assertTrue(sell.executed);
        assertFalse(position.trading);
        assertEquals(1, executor.sells);
    }

    @Test
    void shouldNotExecuteSellWhenNotInPosition() {
        ReversalExecutionOrchestrator orchestrator = new ReversalExecutionOrchestrator();
        ReversalExecutionOrchestrator.PositionState position = new ReversalExecutionOrchestrator.PositionState();
        position.tradingSum = 100;
        position.trading = false;

        MockExecutor executor = new MockExecutor();
        ReversalExecutionOrchestrator.ExecutionResult r = orchestrator.apply(
                ReversalDecisionEngine.Action.SELL_LOSS, position, 90, executor
        );

        assertFalse(r.executed);
        assertEquals(0, executor.sells);
    }
}

