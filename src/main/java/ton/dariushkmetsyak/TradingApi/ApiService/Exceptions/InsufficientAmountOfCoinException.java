package ton.dariushkmetsyak.TradingApi.ApiService.Exceptions;

public class InsufficientAmountOfCoinException extends Exception {
    public InsufficientAmountOfCoinException(String message) {
        super("ERROR: Insufficient " +message + " in your wallet");
    }
}
