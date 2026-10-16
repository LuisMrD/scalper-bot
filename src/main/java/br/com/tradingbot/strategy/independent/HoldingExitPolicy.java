package br.com.tradingbot.strategy.independent;

/**
 * Alternative holding-time rules on the SAME BTC MIDAS-hybrid entry signals.
 * All preserve the original protective structural stop/trailing.
 */
public enum HoldingExitPolicy {
    BASELINE_30(30, false),
    HOLD_45(45, false),
    HOLD_60(60, false),
    DYNAMIC_TREND_60(60, true);

    private final int maxBars;
    private final boolean conditional;
    HoldingExitPolicy(int maxBars,boolean conditional) {
        this.maxBars=maxBars;
        this.conditional=conditional;
    }
    public int maxBars() { return maxBars; }
    public boolean conditional() { return conditional; }
}
