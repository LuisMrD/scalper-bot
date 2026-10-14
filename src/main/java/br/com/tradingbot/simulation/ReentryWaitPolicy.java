package br.com.tradingbot.simulation;

/**
 * Exclusively for simulation of re-entries after an entry was rejected by BLOCK_BOTH.
 * Windows and independent portfolios never share this state.
 */
public enum ReentryWaitPolicy {
    NONE(0),
    WAIT_WHILE_15M_RANGE(0),
    WAIT_3_BARS(3),
    WAIT_12_BARS(12),
    /** 4 completed 15m candles = 60 minutes, research only. */
    WAIT_4_BARS_15M(4);

    private final int fixedBars;

    ReentryWaitPolicy(int fixedBars) { this.fixedBars = fixedBars; }

    public int fixedBars() { return fixedBars; }
}
