package br.com.tradingbot.strategy.independent;

/**
 * Frozen BTC paper A/B test. HYBRID does NOT use a MIDAS filter for LONG;
 * it applies the existing causal MIDAS pullback/rejection gate to SHORT only.
 * Stop, trailing, fees and sizing are identical across policies.
 */
public enum MidasHybridPolicy {
    BASELINE,
    HYBRID_LONG_BASELINE_SHORT_MIDAS_RETEST;

    public MidasEntryPolicy entryPolicy(boolean shortSide) {
        return this==HYBRID_LONG_BASELINE_SHORT_MIDAS_RETEST && shortSide
                ?MidasEntryPolicy.MIDAS_PULLBACK_REJECTION
                :MidasEntryPolicy.BASELINE;
    }
}
