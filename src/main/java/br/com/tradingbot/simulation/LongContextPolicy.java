package br.com.tradingbot.simulation;

import br.com.tradingbot.analysis.brooks.PriceActionSnapshot.BreakoutState;
import br.com.tradingbot.analysis.brooks.PriceActionSnapshot.Trend;
import br.com.tradingbot.analysis.volume.VolumeSnapshot.VolumeLevel;
import br.com.tradingbot.decision.DecisionResult;
import java.util.Objects;

/** Frozen, research-only LONG rejection gates for untouched windows 40..45. */
public enum LongContextPolicy {
    CONTROL,
    BLOCK_LONG_RANGE_FOLLOW_THROUGH,
    BLOCK_LONG_UP_NORMAL_VOLUME;

    public boolean blocks(DecisionResult decision, Trend trend15m,
            BreakoutState breakout5m, VolumeLevel volume5m) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(trend15m, "trend15m");
        Objects.requireNonNull(breakout5m, "breakout5m");
        Objects.requireNonNull(volume5m, "volume5m");
        if (decision.entry() != DecisionResult.Entry.LONG
                || decision.setup() != DecisionResult.Setup.BREAKOUT_RETEST
                || decision.positionAction() != DecisionResult.PositionAction.NONE)
            return false;
        return switch (this) {
            case CONTROL -> false;
            case BLOCK_LONG_RANGE_FOLLOW_THROUGH ->
                trend15m == Trend.RANGE && breakout5m == BreakoutState.UP_FOLLOW_THROUGH;
            case BLOCK_LONG_UP_NORMAL_VOLUME ->
                trend15m == Trend.UP && volume5m == VolumeLevel.NORMAL;
        };
    }
}
