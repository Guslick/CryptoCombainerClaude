package ton.dariushkmetsyak.TradingApi.ApiService.Exceptions;

public class InsufficientAmountOfUsdtException extends Exception{
    public InsufficientAmountOfUsdtException() {
        super("ERROR: Insufficient USDT in your wallet");
    }
}
