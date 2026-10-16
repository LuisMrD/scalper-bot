package br.com.tradingbot.strategy.independent;

import java.math.BigDecimal;
import java.util.List;

/** Pure research signal, never an order instruction. */
public record IndependentSetupSignal(
        Setup setup, Status status, long candleCloseTime,
        BigDecimal referencePrice, BigDecimal structuralStop,
        BigDecimal frozenBoxTop, BigDecimal frozenBoxBase,
        String reason, List<String> passedFilters, List<String> rejectedFilters) {
    public enum Setup { TREND_PULLBACK, TREND_PULLBACK_MIXED, RANGE_BREAKOUT, RANGE_BREAKOUT_RETEST,
        TREND_PULLBACK_SHORT, TREND_PULLBACK_MIXED_SHORT,
        RANGE_BREAKOUT_SHORT, RANGE_BREAKOUT_RETEST_SHORT }
    public enum Status { WAIT, CONFIRMED, INVALIDATED, ALERT }
    public enum Side { LONG, SHORT }
    public Side side() {
        return setup.name().endsWith("_SHORT") ? Side.SHORT : Side.LONG;
    }
    public IndependentSetupSignal {
        passedFilters = List.copyOf(passedFilters);
        rejectedFilters = List.copyOf(rejectedFilters);
    }
    public boolean actionable() {
        return status == Status.CONFIRMED
                && structuralStop != null && rejectedFilters.isEmpty();
    }
    static IndependentSetupSignal rejected(Setup setup,long at,String reason,
            List<String> passed,List<String> failed) {
        return new IndependentSetupSignal(setup,Status.WAIT,at,null,null,null,
                null,reason,passed,failed);
    }
}
