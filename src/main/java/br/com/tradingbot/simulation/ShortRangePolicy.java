package br.com.tradingbot.simulation;

/**
 * Research only. Gates SHORT RETEST entries after the existing
 * BLOCK_BOTH and 60-minute cooldown. Never changes LONG or exit actions.
 */
public enum ShortRangePolicy {
    CONTROL,
    BLOCK_ALL_15M_RANGE_SHORTS,
    UPPER_THIRD_OF_30_CLOSED_15M_BARS;

    public boolean active() { return this != CONTROL; }
}
