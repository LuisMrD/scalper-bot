package br.com.tradingbot.simulation;

/** Frozen 34-39 OOS research arms. None changes the regular Java engine. */
public enum L2RangePolicy {
    CONTROL,
    BLOCK_ALL_15M_RANGE_SHORTS,
    REPLACE_RANGE_SHORT_RETEST_WITH_L2
}
