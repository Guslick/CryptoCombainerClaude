package ton.dariushkmetsyak;

import org.junit.jupiter.api.Test;
import ton.dariushkmetsyak.Strategies.Core.ReversalDecisionEngine;

import static org.junit.jupiter.api.Assertions.*;

public class ReversalDecisionEngineTest {

    private final ReversalDecisionEngine engine = new ReversalDecisionEngine();

    @Test
    void shouldProduceBuyAfterDropFromMaxExceedsGap() {
        ReversalDecisionEngine.Params params = new ReversalDecisionEngine.Params(3.0, 2.0, 5.0);
        ReversalDecisionEngine.State state = engine.init(1_000, 100);

        // grow local max
        engine.onTick(state, params, 2_000, 110);
        // then drop > 3%
        ReversalDecisionEngine.Decision d = engine.onTick(state, params, 3_000, 106);

        assertEquals(ReversalDecisionEngine.Action.BUY, d.action);
        assertTrue(state.trading);
        assertEquals(106, state.buyPrice, 1e-9);
        assertNotNull(d.newReversalPoint);
        assertEquals("max", d.newReversalPoint.tag);
    }

    @Test
    void shouldSellWithProfitWhenTargetReached() {
        ReversalDecisionEngine.Params params = new ReversalDecisionEngine.Params(3.0, 2.0, 5.0);
        ReversalDecisionEngine.State state = engine.init(1_000, 100);
        state.trading = true;
        state.buyPrice = 100;

        ReversalDecisionEngine.Decision d = engine.onTick(state, params, 2_000, 103);

        assertEquals(ReversalDecisionEngine.Action.SELL_PROFIT, d.action);
        assertFalse(state.trading);
    }

    @Test
    void shouldSellWithLossWhenStopReached() {
        ReversalDecisionEngine.Params params = new ReversalDecisionEngine.Params(3.0, 2.0, 5.0);
        ReversalDecisionEngine.State state = engine.init(1_000, 100);
        state.trading = true;
        state.buyPrice = 100;

        ReversalDecisionEngine.Decision d = engine.onTick(state, params, 2_000, 94);

        assertEquals(ReversalDecisionEngine.Action.SELL_LOSS, d.action);
        assertFalse(state.trading);
    }
}
