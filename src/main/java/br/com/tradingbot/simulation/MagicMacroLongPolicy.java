package br.com.tradingbot.simulation;

import java.math.BigDecimal;

/**
 * Predefined research-only LONG gates. Every new lookback uses the user's
 * 3/6/9 family of whole-hour horizons. SHORT signals are never modified.
 */
public enum MagicMacroLongPolicy {
    NONE(0, 0),
    BOTH_UP_3H(3, 0),
    BOTH_UP_6H(6, 0),
    BOTH_UP_9H(9, 0),
    BOTH_UP_6H_DIP_3H(6, 3),
    BOTH_UP_9H_DIP_3H(9, 3);

    private final int trendHours;
    private final int dipHours;

    MagicMacroLongPolicy(int trendHours, int dipHours) {
        if (trendHours != 0) MagicResearchNumbers.requireHours(trendHours);
        if (dipHours != 0) MagicResearchNumbers.requireHours(dipHours);
        this.trendHours = trendHours;
        this.dipHours = dipHours;
    }

    public boolean active() { return this != NONE; }

    /**
     * Uses STRICT signs (>0 for both in trend, <0 for both in pullback).
     * Null/stale macro input is forbidden by the replay before this call.
     */
    public boolean allows(CrossAssetMacroMomentum.MagicSnapshot btc,
            CrossAssetMacroMomentum.MagicSnapshot eth) {
        if (!active()) return true;
        BigDecimal a = btc.returnHours(trendHours);
        BigDecimal b = eth.returnHours(trendHours);
        if (a.signum() <= 0 || b.signum() <= 0) return false;
        return dipHours == 0 || (btc.returnHours(dipHours).signum() < 0
                && eth.returnHours(dipHours).signum() < 0);
    }
}
