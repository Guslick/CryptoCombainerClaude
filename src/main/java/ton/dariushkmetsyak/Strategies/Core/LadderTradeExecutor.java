package ton.dariushkmetsyak.Strategies.Core;

public interface LadderTradeExecutor {
    void buy(double price, double usdtAmount) throws Exception;
    void sell(double price, double quantity) throws Exception;
}
