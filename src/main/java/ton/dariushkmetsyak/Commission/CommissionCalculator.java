package ton.dariushkmetsyak.Commission;

/**
 * Calculates estimated exchange commissions for trades.
 * Binance spot trading fee: 0.1% maker/taker (standard tier, no BNB discount).
 */
public class CommissionCalculator {

    public enum Exchange {
        BINANCE("Binance", 0.1);  // 0.1% per trade

        private final String displayName;
        private final double feePercent;

        Exchange(String displayName, double feePercent) {
            this.displayName = displayName;
            this.feePercent = feePercent;
        }

        public String getDisplayName() { return displayName; }
        public double getFeePercent() { return feePercent; }
    }

    private final Exchange exchange;

    public CommissionCalculator(Exchange exchange) {
        this.exchange = exchange;
    }

    public CommissionCalculator() {
        this(Exchange.BINANCE);
    }

    /** Commission for a single trade (buy or sell) */
    public double calcCommission(double tradeAmountUsdt) {
        return tradeAmountUsdt * exchange.getFeePercent() / 100.0;
    }

    /** Commission for a full round-trip (buy + sell) */
    public double calcRoundTripCommission(double buyAmountUsdt, double sellAmountUsdt) {
        return calcCommission(buyAmountUsdt) + calcCommission(sellAmountUsdt);
    }

    public Exchange getExchange() { return exchange; }
    public double getFeePercent() { return exchange.getFeePercent(); }
}
