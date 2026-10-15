package br.com.tradingbot.simulation;

import br.com.tradingbot.analysis.brooks.PriceActionSnapshot.Trend;
import java.util.Objects;

/** State machine fed once per COMPLETED primary candle; never accesses future bars. */
final class ReentryWaitState {
    private final ReentryWaitPolicy policy;
    private boolean rangeWaitArmed;
    private int remainingBars;
    private boolean blockThisCandle;

    ReentryWaitState(ReentryWaitPolicy policy) {
        this.policy = Objects.requireNonNull(policy);
    }

    /** Called before a new entry decision, using the latest already closed 15m trend. */
    void onClosedCandle(Trend trend15m) {
        Objects.requireNonNull(trend15m, "trend15m");
        if (policy == ReentryWaitPolicy.WAIT_WHILE_15M_RANGE) {
            if (trend15m != Trend.RANGE) rangeWaitArmed = false;
            blockThisCandle = rangeWaitArmed && trend15m == Trend.RANGE;
        } else {
            blockThisCandle = remainingBars > 0;
            if (remainingBars > 0) remainingBars--;
        }
    }

    /** Called only AFTER BLOCK_BOTH rejected a RETEST entry signal. */
    void onEntryRejected(Trend trend15m) {
        Objects.requireNonNull(trend15m, "trend15m");
        if (policy == ReentryWaitPolicy.WAIT_WHILE_15M_RANGE) {
            rangeWaitArmed = trend15m == Trend.RANGE;
        } else if (policy.fixedBars() > 0) {
            // Block exactly the next N CLOSED primary candles after rejection.
            remainingBars = policy.fixedBars();
        }
    }

    boolean blocksEntry() { return blockThisCandle; }
}
