package br.com.tradingbot.simulation;

import br.com.tradingbot.analysis.brooks.PriceActionSnapshot.BreakoutState;
import br.com.tradingbot.decision.DecisionResult;
import java.util.Objects;

/**
 * Experimental entry-only filters for historical JAVA backtests.
 * Decisions and breakout states must come from the SAME already closed candle.
 * No entry filtering changes production/normal Java decision-engine behavior.
 */
public enum EntryBreakoutFilter {
    NONE,
    BLOCK_SHORT_DOWN_CANDIDATE,
    BLOCK_LONG_NONE,
    BLOCK_BOTH;

    public boolean blocks(DecisionResult decision, BreakoutState breakoutState) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(breakoutState, "breakoutState");
        if (decision.setup() != DecisionResult.Setup.BREAKOUT_RETEST) return false;
        boolean shortCandidate = decision.entry() == DecisionResult.Entry.SHORT
                && breakoutState == BreakoutState.DOWN_CANDIDATE;
        boolean longNoBreakout = decision.entry() == DecisionResult.Entry.LONG
                && breakoutState == BreakoutState.NONE;
        return switch (this) {
            case NONE -> false;
            case BLOCK_SHORT_DOWN_CANDIDATE -> shortCandidate;
            case BLOCK_LONG_NONE -> longNoBreakout;
            case BLOCK_BOTH -> shortCandidate || longNoBreakout;
        };
    }
}
