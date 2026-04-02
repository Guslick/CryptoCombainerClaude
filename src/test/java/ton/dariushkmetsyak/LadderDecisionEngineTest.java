package ton.dariushkmetsyak;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import ton.dariushkmetsyak.Strategies.Core.LadderDecisionEngine;

public class LadderDecisionEngineTest {

    @Test
    void initializesOnFirstPriceWithoutTrades() {
        LadderDecisionEngine engine = new LadderDecisionEngine();
        LadderDecisionEngine.State state = new LadderDecisionEngine.State();
        LadderDecisionEngine.Params params = new LadderDecisionEngine.Params(100.0, 10.0);

        LadderDecisionEngine.Decision d = engine.evaluate(50000.0, params, state);

        Assertions.assertTrue(d.initializeOnly);
        Assertions.assertFalse(d.shouldBuy);
        Assertions.assertFalse(d.shouldSell);
        Assertions.assertEquals(50000.0, state.basePrice, 1e-9);
        Assertions.assertTrue(state.openLots.isEmpty());
    }

    @Test
    void buysWhenMovingToNewStepAndSellsCheapestWhenTargetReached() {
        LadderDecisionEngine engine = new LadderDecisionEngine();
        LadderDecisionEngine.State state = new LadderDecisionEngine.State();
        LadderDecisionEngine.Params params = new LadderDecisionEngine.Params(100.0, 10.0);

        engine.evaluate(50000.0, params, state); // init

        LadderDecisionEngine.Decision d1 = engine.evaluate(55000.0, params, state);
        Assertions.assertTrue(d1.shouldBuy);
        Assertions.assertFalse(d1.shouldSell);
        Assertions.assertEquals(1, state.openLots.size());

        // move to new lower level -> buy again
        LadderDecisionEngine.Decision d2 = engine.evaluate(50000.0, params, state);
        Assertions.assertTrue(d2.shouldBuy);
        Assertions.assertFalse(d2.shouldSell);
        Assertions.assertEquals(2, state.openLots.size());

        // jump enough to sell the cheapest lot: cheapest is 50000, sell threshold 55000
        LadderDecisionEngine.Decision d3 = engine.evaluate(56000.0, params, state);
        Assertions.assertTrue(d3.shouldSell);
        Assertions.assertTrue(d3.shouldBuy); // new step from 0 to 1 triggers buy as well
        Assertions.assertTrue(d3.sellQuantity > 0.0);
        Assertions.assertEquals(2, state.openLots.size());
    }

    @Test
    void sameStepDoesNotTriggerRepeatedBuy() {
        LadderDecisionEngine engine = new LadderDecisionEngine();
        LadderDecisionEngine.State state = new LadderDecisionEngine.State();
        LadderDecisionEngine.Params params = new LadderDecisionEngine.Params(100.0, 10.0);

        engine.evaluate(50000.0, params, state);
        LadderDecisionEngine.Decision d1 = engine.evaluate(54900.0, params, state);
        LadderDecisionEngine.Decision d2 = engine.evaluate(54800.0, params, state);

        Assertions.assertFalse(d1.shouldBuy);
        Assertions.assertFalse(d2.shouldBuy);
    }
}
