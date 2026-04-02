package ton.dariushkmetsyak.Strategies.Core;

/**
 * Execution contract for applying strategy decisions.
 * Implementations may execute against exchange API, simulator, or mock.
 */
public interface ReversalTradeExecutor {
    Double buy(double price, double quantity);
    Double sell(double price, double quantity);
}

