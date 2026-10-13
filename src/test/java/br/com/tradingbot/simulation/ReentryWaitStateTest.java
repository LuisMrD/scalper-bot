package br.com.tradingbot.simulation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.tradingbot.analysis.brooks.PriceActionSnapshot.Trend;
import org.junit.jupiter.api.Test;

class ReentryWaitStateTest {

    @Test
    void rangeReentryWaitOnlyStartsOnRejectedEntryAndEndsOnFirstNonRangeClose() {
        var state = new ReentryWaitState(ReentryWaitPolicy.WAIT_WHILE_15M_RANGE);
        state.onClosedCandle(Trend.RANGE);
        assertFalse(state.blocksEntry());
        state.onEntryRejected(Trend.RANGE);
        state.onClosedCandle(Trend.RANGE);
        assertTrue(state.blocksEntry());
        state.onClosedCandle(Trend.RANGE);
        assertTrue(state.blocksEntry());
        state.onClosedCandle(Trend.UP);
        assertFalse(state.blocksEntry());
        state.onClosedCandle(Trend.RANGE);
        assertFalse(state.blocksEntry());
    }

    @Test
    void rejectingDuringTrendingMarketDoesNotActivateRangeWait() {
        var state = new ReentryWaitState(ReentryWaitPolicy.WAIT_WHILE_15M_RANGE);
        state.onClosedCandle(Trend.DOWN);
        state.onEntryRejected(Trend.DOWN);
        state.onClosedCandle(Trend.RANGE);
        assertFalse(state.blocksEntry());
    }

    @Test
    void fixedThreeBarWaitBlocksExactlyNextThreeClosedCandles() {
        var state = new ReentryWaitState(ReentryWaitPolicy.WAIT_3_BARS);
        state.onClosedCandle(Trend.UP);
        assertFalse(state.blocksEntry());
        state.onEntryRejected(Trend.UP);
        for (int i = 0; i < 3; i++) {
            state.onClosedCandle(Trend.DOWN);
            assertTrue(state.blocksEntry());
        }
        state.onClosedCandle(Trend.RANGE);
        assertFalse(state.blocksEntry());
    }

    @Test
    void fixedTwelveBarWaitResetsWhenAnotherEntryIsRejected() {
        var state = new ReentryWaitState(ReentryWaitPolicy.WAIT_12_BARS);
        state.onClosedCandle(Trend.RANGE);
        state.onEntryRejected(Trend.RANGE);
        for (int i = 0; i < 10; i++) {
            state.onClosedCandle(Trend.RANGE);
            assertTrue(state.blocksEntry());
        }
        state.onEntryRejected(Trend.RANGE);
        for (int i = 0; i < 12; i++) {
            state.onClosedCandle(Trend.UP);
            assertTrue(state.blocksEntry());
        }
        state.onClosedCandle(Trend.UP);
        assertFalse(state.blocksEntry());
    }

    @Test
    void noWaitNeverBlocks() {
        var state = new ReentryWaitState(ReentryWaitPolicy.NONE);
        state.onEntryRejected(Trend.RANGE);
        for (int i = 0; i < 20; i++) {
            state.onClosedCandle(Trend.RANGE);
            assertFalse(state.blocksEntry());
        }
    }
}
