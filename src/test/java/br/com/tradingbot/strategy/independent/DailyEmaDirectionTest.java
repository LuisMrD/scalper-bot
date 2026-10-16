package br.com.tradingbot.strategy.independent;

import static org.junit.jupiter.api.Assertions.*;

import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DailyEmaDirectionTest {
    private static final long FIRST=Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
    private static final long DAY=CandleInterval.D1.millis();
    private static MarketCandle day(int number,long closePrice,boolean closed) {
        long start=FIRST+number*DAY;
        var close=BigDecimal.valueOf(closePrice);
        return new MarketCandle("BTCUSDT",CandleInterval.D1,start,start+DAY-1,
                close,close.add(BigDecimal.ONE),close.subtract(BigDecimal.ONE),
                close,BigDecimal.valueOf(1000),BigDecimal.valueOf(1000),100,
                BigDecimal.valueOf(500),BigDecimal.valueOf(500),closed);
    }
    private static ArrayList<MarketCandle> days(int count,int step) {
        var list=new ArrayList<MarketCandle>();
        for(int i=0;i<count;i++) list.add(day(i,100+(long)i*step,true));
        return list;
    }

    @Test void pastCompletedDaysCanAuthorizeLongFromIntradayCutoff() {
        var history=days(30,1);
        long intradayCutoff=history.getLast().closeTime()+CandleInterval.M15.millis();
        var direction=DailyEmaDirection.fromClosedDays(history,intradayCutoff);
        assertEquals(WeeklyEmaDirection.Direction.UP,direction);
        assertTrue(WeeklyEmaDirection.allows(direction,IndependentSetupSignal.Side.LONG));
        assertFalse(WeeklyEmaDirection.allows(direction,IndependentSetupSignal.Side.SHORT));
    }

    @Test void pastCompletedDaysCanAuthorizeShort() {
        var history=days(30,-1);
        assertEquals(WeeklyEmaDirection.Direction.DOWN,
                DailyEmaDirection.fromClosedDays(history,history.getLast().closeTime()));
    }

    @Test void perfectlyFlatDailySlopeAllowsNeitherSide() {
        var history=days(30,0);
        var dir=DailyEmaDirection.fromClosedDays(history,history.getLast().closeTime());
        assertEquals(WeeklyEmaDirection.Direction.FLAT,dir);
        assertFalse(WeeklyEmaDirection.allows(dir,IndependentSetupSignal.Side.LONG));
        assertFalse(WeeklyEmaDirection.allows(dir,IndependentSetupSignal.Side.SHORT));
    }

    @Test void currentlyFormingDayIsNeverAcceptedEvenIfIncorrectlyFlaggedClosed() {
        var history=days(30,1);
        long cutoff=history.get(28).closeTime()+CandleInterval.M15.millis();
        assertThrows(IllegalArgumentException.class,
                ()->DailyEmaDirection.fromClosedDays(history,cutoff));
        history.set(29,day(29,1000000,false));
        assertThrows(IllegalArgumentException.class,
                ()->DailyEmaDirection.fromClosedDays(history,cutoff));
    }

    @Test void missingDayOrInsufficientLookbackFailsClosed() {
        var shortHistory=days(21,1);
        assertThrows(IllegalArgumentException.class,
                ()->DailyEmaDirection.fromClosedDays(shortHistory,Long.MAX_VALUE));
        var gap=days(30,1);
        gap.remove(14);
        var interrupted=List.copyOf(gap);
        assertThrows(IllegalArgumentException.class,
                ()->DailyEmaDirection.fromClosedDays(interrupted,Long.MAX_VALUE));
    }

    @Test void requestRequiresExactlyFifteenMinutePrimaryWithDailyReference() {
        var p=new IndependentSetupParameters(2,2,3,5,new BigDecimal("0.5"),
                new BigDecimal("0.85"),new BigDecimal("0.85"),new BigDecimal("0.65"),
                new BigDecimal("2"),new BigDecimal("1.2"),new BigDecimal("2"),
                10,new BigDecimal("2"),new BigDecimal("0.85"),
                new BigDecimal("0.85"),new BigDecimal("1.2"),
                6,new BigDecimal("0.2"),BigDecimal.ONE,14,20,
                IndependentSetupParameters.DirectStopRule.BOX_BASE,BigDecimal.ONE);
        var ok=request(p,"15m","1d");
        assertEquals(2400,ok.steps());
        assertEquals("15m",ok.entryInterval());
        assertEquals("1d",ok.trendInterval());
        assertThrows(IllegalArgumentException.class,()->request(p,"15m","1w"));
    }

    private static IndependentResearchRequest request(
            IndependentSetupParameters p,String primary,String higher) {
        return new IndependentResearchRequest("BTCUSDT",2400,60,primary,higher,
                FIRST+DAY*15-1,p,new BigDecimal("1500"),
                new BigDecimal("0.5"),new BigDecimal("3"),1,
                new BigDecimal("0.05"),new BigDecimal("0.02"),
                new BigDecimal("0.01"),BigDecimal.ZERO,
                new BigDecimal("0.5"),new BigDecimal("0.01"),
                new BigDecimal("0.001"),new BigDecimal("0.001"),
                new BigDecimal("5"),new BigDecimal("50"),30);
    }
}
