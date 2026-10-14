package br.com.tradingbot.simulation;

import static org.junit.jupiter.api.Assertions.*;

import br.com.tradingbot.analysis.brooks.PriceActionSnapshot.BreakoutState;
import br.com.tradingbot.analysis.brooks.PriceActionSnapshot.Trend;
import br.com.tradingbot.analysis.volume.VolumeSnapshot.VolumeLevel;
import br.com.tradingbot.decision.DecisionResult;
import org.junit.jupiter.api.Test;

class LongContextPolicyTest {
    private DecisionResult signal(DecisionResult.Entry entry, DecisionResult.Setup setup,
            DecisionResult.PositionAction action) {
        return new DecisionResult("JAVA", "SOLUSDT", "5m", 899999L, 899999L,
                entry, action, setup, DecisionResult.Reason.BREAKOUT_RETEST_CONFIRMED);
    }

    @Test
    void firstGateRejectsOnlyLongWithClosed15mRangeAndFiveMinuteFollowThrough() {
        var buy = signal(DecisionResult.Entry.LONG, DecisionResult.Setup.BREAKOUT_RETEST,
                DecisionResult.PositionAction.NONE);
        var p = LongContextPolicy.BLOCK_LONG_RANGE_FOLLOW_THROUGH;
        assertTrue(p.blocks(buy, Trend.RANGE, BreakoutState.UP_FOLLOW_THROUGH, VolumeLevel.HIGH));
        assertFalse(p.blocks(buy, Trend.UP, BreakoutState.UP_FOLLOW_THROUGH, VolumeLevel.HIGH));
        assertFalse(p.blocks(buy, Trend.RANGE, BreakoutState.UP_CANDIDATE, VolumeLevel.HIGH));
        assertFalse(p.blocks(buy, Trend.RANGE, BreakoutState.DOWN_FOLLOW_THROUGH, VolumeLevel.NORMAL));
        assertFalse(LongContextPolicy.CONTROL.blocks(buy, Trend.RANGE,
                BreakoutState.UP_FOLLOW_THROUGH, VolumeLevel.NORMAL));
    }

    @Test
    void secondGateRejectsOnlyLongInUpTrendWithNormalFiveMinuteVolume() {
        var buy = signal(DecisionResult.Entry.LONG, DecisionResult.Setup.BREAKOUT_RETEST,
                DecisionResult.PositionAction.NONE);
        var p = LongContextPolicy.BLOCK_LONG_UP_NORMAL_VOLUME;
        assertTrue(p.blocks(buy, Trend.UP, BreakoutState.NONE, VolumeLevel.NORMAL));
        assertFalse(p.blocks(buy, Trend.RANGE, BreakoutState.NONE, VolumeLevel.NORMAL));
        assertFalse(p.blocks(buy, Trend.UP, BreakoutState.NONE, VolumeLevel.HIGH));
        assertFalse(p.blocks(buy, Trend.UP, BreakoutState.NONE, VolumeLevel.LOW));
        assertFalse(p.blocks(buy, Trend.UP, BreakoutState.NONE, VolumeLevel.UNAVAILABLE));
    }

    @Test
    void neitherGateTouchesShortPullbackWaitOrPositionManagement() {
        for (var policy : LongContextPolicy.values()) {
            assertFalse(policy.blocks(signal(DecisionResult.Entry.SHORT,
                            DecisionResult.Setup.BREAKOUT_RETEST,
                            DecisionResult.PositionAction.NONE),
                    Trend.RANGE, BreakoutState.UP_FOLLOW_THROUGH, VolumeLevel.NORMAL));
            assertFalse(policy.blocks(signal(DecisionResult.Entry.LONG,
                            DecisionResult.Setup.PULLBACK,
                            DecisionResult.PositionAction.NONE),
                    Trend.UP, BreakoutState.UP_FOLLOW_THROUGH, VolumeLevel.NORMAL));
            assertFalse(policy.blocks(signal(DecisionResult.Entry.WAIT,
                            DecisionResult.Setup.NONE,
                            DecisionResult.PositionAction.EXIT),
                    Trend.UP, BreakoutState.UP_FOLLOW_THROUGH, VolumeLevel.NORMAL));
        }
    }
}
