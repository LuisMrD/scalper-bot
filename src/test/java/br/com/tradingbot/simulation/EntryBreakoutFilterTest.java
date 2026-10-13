package br.com.tradingbot.simulation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.tradingbot.analysis.brooks.PriceActionSnapshot.BreakoutState;
import br.com.tradingbot.decision.DecisionResult;
import org.junit.jupiter.api.Test;

class EntryBreakoutFilterTest {
    private DecisionResult signal(DecisionResult.Entry entry, DecisionResult.Setup setup) {
        return new DecisionResult("JAVA", "SOLUSDT", "5m", 299999L, 299999L,
                entry, DecisionResult.PositionAction.NONE, setup,
                DecisionResult.Reason.BREAKOUT_RETEST_CONFIRMED);
    }

    @Test
    void filtersTargetOnlyTheTwoDiscoveredEntryCases() {
        var shortEntry = signal(DecisionResult.Entry.SHORT, DecisionResult.Setup.BREAKOUT_RETEST);
        var longEntry = signal(DecisionResult.Entry.LONG, DecisionResult.Setup.BREAKOUT_RETEST);
        assertTrue(EntryBreakoutFilter.BLOCK_SHORT_DOWN_CANDIDATE.blocks(
                shortEntry, BreakoutState.DOWN_CANDIDATE));
        assertFalse(EntryBreakoutFilter.BLOCK_LONG_NONE.blocks(
                shortEntry, BreakoutState.DOWN_CANDIDATE));
        assertTrue(EntryBreakoutFilter.BLOCK_LONG_NONE.blocks(longEntry, BreakoutState.NONE));
        assertFalse(EntryBreakoutFilter.BLOCK_SHORT_DOWN_CANDIDATE.blocks(
                longEntry, BreakoutState.NONE));
        assertTrue(EntryBreakoutFilter.BLOCK_BOTH.blocks(shortEntry, BreakoutState.DOWN_CANDIDATE));
        assertTrue(EntryBreakoutFilter.BLOCK_BOTH.blocks(longEntry, BreakoutState.NONE));
    }

    @Test
    void otherEntriesAndNoTradeRemainUnchanged() {
        var shortEntry = signal(DecisionResult.Entry.SHORT, DecisionResult.Setup.BREAKOUT_RETEST);
        var longEntry = signal(DecisionResult.Entry.LONG, DecisionResult.Setup.BREAKOUT_RETEST);
        for (EntryBreakoutFilter filter : EntryBreakoutFilter.values()) {
            assertFalse(filter.blocks(shortEntry, BreakoutState.DOWN_FOLLOW_THROUGH));
            assertFalse(filter.blocks(longEntry, BreakoutState.UP_FOLLOW_THROUGH));
            assertFalse(filter.blocks(signal(DecisionResult.Entry.WAIT, DecisionResult.Setup.NONE),
                    BreakoutState.DOWN_CANDIDATE));
            assertFalse(filter.blocks(signal(DecisionResult.Entry.LONG, DecisionResult.Setup.PULLBACK),
                    BreakoutState.NONE));
        }
    }

    @Test
    void noneNeverBlocksEvenAnIdentifiedBadState() {
        assertFalse(EntryBreakoutFilter.NONE.blocks(
                signal(DecisionResult.Entry.SHORT, DecisionResult.Setup.BREAKOUT_RETEST),
                BreakoutState.DOWN_CANDIDATE));
        assertFalse(EntryBreakoutFilter.NONE.blocks(
                signal(DecisionResult.Entry.LONG, DecisionResult.Setup.BREAKOUT_RETEST),
                BreakoutState.NONE));
    }
}
