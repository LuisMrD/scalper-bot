package br.com.tradingbot.strategy.independent;

import java.math.BigDecimal;

/**
 * Frozen, experimental entry gates on the SAME BTC hybrid LONG/SHORT signals.
 * The new gates never change the initial protective stop or the holding rule.
 * The 0.50% alternatives are sensitivity controls, not separately tuned rules.
 */
public enum EntrySelectionPolicy {
    BASELINE(false,null),
    WEEKDAYS_ONLY(true,null),
    STOP_045_ONLY(false,new BigDecimal("0.45")),
    COMBINED_045(true,new BigDecimal("0.45")),
    STOP_050_ONLY(false,new BigDecimal("0.50")),
    COMBINED_050(true,new BigDecimal("0.50"));

    private final boolean weekdaysOnly;
    private final BigDecimal maxStopDistancePct;
    EntrySelectionPolicy(boolean weekdaysOnly,BigDecimal maxStopDistancePct) {
        this.weekdaysOnly=weekdaysOnly;
        this.maxStopDistancePct=maxStopDistancePct;
    }
    public boolean weekdaysOnly() { return weekdaysOnly; }
    public BigDecimal maxStopDistancePct() { return maxStopDistancePct; }
}
