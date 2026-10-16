package br.com.tradingbot.strategy.independent;

import static org.junit.jupiter.api.Assertions.*;

import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WeeklyEmaDirectionTest {
    private static final long MONDAY=Instant.parse("2025-01-06T00:00:00Z").toEpochMilli();
    private static final long WEEK=CandleInterval.W1.millis();

    private static MarketCandle week(int i,BigDecimal close,boolean closed) {
        long start=MONDAY+i*WEEK;
        return new MarketCandle("BTCUSDT",CandleInterval.W1,start,start+WEEK-1,
                close,close.add(BigDecimal.ONE),close.subtract(BigDecimal.ONE),
                close,BigDecimal.valueOf(1000),BigDecimal.valueOf(1000),100,
                BigDecimal.valueOf(500),BigDecimal.valueOf(500),closed);
    }

    private static List<MarketCandle> series(int count,int move) {
        var out=new ArrayList<MarketCandle>();
        for(int i=0;i<count;i++)
            out.add(week(i,BigDecimal.valueOf(100L+(long)i*move),true));
        return out;
    }

    @Test void risingEmaSlopeApprovesLongEvenWithoutBrooksUpClassification() {
        var weeks=series(30,1);
        long mondayAfterLast=weeks.getLast().closeTime()+1;
        assertEquals(WeeklyEmaDirection.Direction.UP,
                WeeklyEmaDirection.fromClosedWeeks(weeks,mondayAfterLast));
        assertTrue(WeeklyEmaDirection.allows(WeeklyEmaDirection.Direction.UP,
                IndependentSetupSignal.Side.LONG));
        assertFalse(WeeklyEmaDirection.allows(WeeklyEmaDirection.Direction.UP,
                IndependentSetupSignal.Side.SHORT));
    }

    @Test void fallingEmaSlopeApprovesOnlyShort() {
        var weeks=series(30,-1);
        assertEquals(WeeklyEmaDirection.Direction.DOWN,
                WeeklyEmaDirection.fromClosedWeeks(weeks,weeks.getLast().closeTime()));
        assertTrue(WeeklyEmaDirection.allows(WeeklyEmaDirection.Direction.DOWN,
                IndependentSetupSignal.Side.SHORT));
        assertFalse(WeeklyEmaDirection.allows(WeeklyEmaDirection.Direction.DOWN,
                IndependentSetupSignal.Side.LONG));
    }

    @Test void flatWeeklyEmaSlopeRejectsBothSides() {
        var weeks=series(30,0);
        var direction=WeeklyEmaDirection.fromClosedWeeks(
                weeks,weeks.getLast().closeTime());
        assertEquals(WeeklyEmaDirection.Direction.FLAT,direction);
        assertFalse(WeeklyEmaDirection.allows(direction,IndependentSetupSignal.Side.LONG));
        assertFalse(WeeklyEmaDirection.allows(direction,IndependentSetupSignal.Side.SHORT));
    }

    @Test void unfinishedCurrentWeekCannotAffectDirectionOrAllowFutureLookahead() {
        var weeks=new ArrayList<>(series(29,1));
        weeks.add(week(29,BigDecimal.valueOf(500),false));
        long cutoff=weeks.get(28).closeTime()+CandleInterval.D1.millis();
        assertThrows(IllegalArgumentException.class,
                ()->WeeklyEmaDirection.fromClosedWeeks(weeks,cutoff));
        // Even wrongly persisted 'closed' must not make the current week visible.
        weeks.set(29,week(29,BigDecimal.valueOf(500),true));
        assertThrows(IllegalArgumentException.class,
                ()->WeeklyEmaDirection.fromClosedWeeks(weeks,cutoff));
    }

    @Test void requiresFullWarmupAndUnbrokenCompletedWeeks() {
        var weeks=new ArrayList<>(series(21,1));
        assertThrows(IllegalArgumentException.class,
                ()->WeeklyEmaDirection.fromClosedWeeks(weeks,Long.MAX_VALUE));
        weeks=new ArrayList<>(series(30,1));
        weeks.remove(14);
        var missing=List.copyOf(weeks);
        assertThrows(IllegalArgumentException.class,
                ()->WeeklyEmaDirection.fromClosedWeeks(missing,Long.MAX_VALUE));
    }
}
