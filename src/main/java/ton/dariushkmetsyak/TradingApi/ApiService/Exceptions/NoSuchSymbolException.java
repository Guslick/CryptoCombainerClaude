package ton.dariushkmetsyak.TradingApi.ApiService.Exceptions;

public class NoSuchSymbolException extends Exception {
    public NoSuchSymbolException(String message) {
        super("ERROR: Binance does not have symbol " + message);
    }
}
