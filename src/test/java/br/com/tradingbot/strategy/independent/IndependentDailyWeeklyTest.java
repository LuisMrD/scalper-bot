package br.com.tradingbot.strategy.independent;

import static org.junit.jupiter.api.Assertions.*;

import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Regression: daily entry, UTC weekly trend, never an in-progress week. */
class IndependentDailyWeeklyTest {
    private static final long MONDAY = Instant.parse("2026-01-05T00:00:00Z").toEpochMilli();
    private static final long DAY = CandleInterval.D1.millis();
    private static final long WEEK = CandleInterval.W1.millis();
    private static BigDecimal d(String s) { return new BigDecimal(s); }

    private static MarketCandle candle(CandleInterval interval,long open,boolean closed) {
        return new MarketCandle("BTCUSDT",interval,open,open+interval.millis()-1,
                d("100"),d("103"),d("97"),d("102"),d("1000"),
                d("101000"),120,d("620"),d("61000"),closed);
    }

    private static IndependentSetupParameters params() {
        return new IndependentSetupParameters(2,2,3,5,d("0.5"),
                d("0.85"),d("0.85"),d("0.65"),d("2"),
                d("1.2"),d("2"),10,d("2"),
                d("0.85"),d("0.85"),d("1.2"),6,d("0.2"),
                d("1"),14,20,IndependentSetupParameters.DirectStopRule.BOX_BASE,
                d("1"));
    }

    private static IndependentResearchRequest request(String entry,String higher,long end) {
        return new IndependentResearchRequest("BTCUSDT",60,80,entry,higher,end,
                params(),d("1500"),d("0.5"),d("3"),1,d("0.05"),
                d("0.02"),d("0.01"),d("0"),d("0.5"),
                d("0.01"),d("0.001"),d("0.001"),d("5"),d("50"),60);
    }

    @Test
    void dailyRequiresWeeklyContextNotAnIntradayHigherFrame() {
        var r=request("1d","1w",MONDAY+DAY-1);
        assertEquals("1d",r.entryInterval());
        assertEquals("1w",r.trendInterval());
        assertThrows(IllegalArgumentException.class,
                ()->request("1d","1h",MONDAY+DAY-1));
        assertThrows(IllegalArgumentException.class,
                ()->request("5m","1w",MONDAY+DAY-1));
    }

    @Test
    void dailyCandlesAreClosedContinuousAndBothDetectorsCanConsumeThem() {
        List<MarketCandle> tape=new ArrayList<>();
        for(int i=0;i<80;i++)tape.add(candle(CandleInterval.D1,MONDAY+i*DAY,true));
        assertDoesNotThrow(()->ClosedCandleFeatures.check(tape));
        assertDoesNotThrow(()->new TrendPullbackDetector(params()).evaluate(tape,d("0.01")));
        assertDoesNotThrow(()->new RangeBreakoutDetector(params(),
                RangeBreakoutDetector.Variant.IMMEDIATE).onClosed(tape,d("0.01")));
        // Preserve original 5-minute support in the same pure analyzer.
        assertDoesNotThrow(()->ClosedCandleFeatures.check(List.of(
                candle(CandleInterval.M5,MONDAY,true),
                candle(CandleInterval.M5,MONDAY+CandleInterval.M5.millis(),true))));
    }

    @Test
    void unfinishedMondayThroughSaturdayWeekIsNeverVisibleToDailySignals() {
        var currentWeek=candle(CandleInterval.W1,MONDAY+WEEK,false);
        long tuesday=MONDAY+WEEK+2*DAY-1;
        assertThrows(IllegalArgumentException.class,
                ()->CompletedWeeklyTrend.requireCompletedWeeklyCandle(currentWeek,tuesday));
        // Even if a broken data importer incorrectly marks the weekly bar
        // 'closed', it cannot pass before its Sunday end timestamp.
        var improperlyClosed=candle(CandleInterval.W1,MONDAY+WEEK,true);
        assertThrows(IllegalArgumentException.class,
                ()->CompletedWeeklyTrend.requireCompletedWeeklyCandle(improperlyClosed,tuesday));
        assertDoesNotThrow(()->CompletedWeeklyTrend.requireCompletedWeeklyCandle(
                candle(CandleInterval.W1,MONDAY,true),tuesday));
    }

    @Test
    void weeklyClosesOnSundayUtcAndBecomesAvailableAtThatClose() {
        var week=candle(CandleInterval.W1,MONDAY,true);
        assertEquals("1w",CandleInterval.parse("1w").code());
        assertTrue(CompletedWeeklyTrend.weekStartUtcMonday(MONDAY));
        assertFalse(CompletedWeeklyTrend.weekStartUtcMonday(MONDAY+DAY));
        assertThrows(IllegalArgumentException.class,
                ()->CompletedWeeklyTrend.requireCompletedWeeklyCandle(
                        week,MONDAY+WEEK-2));
        assertDoesNotThrow(()->CompletedWeeklyTrend.requireCompletedWeeklyCandle(
                week,MONDAY+WEEK-1));
        assertEquals(3,IndependentPaperPortfolio.fundingOccurrences(
                candle(CandleInterval.D1,MONDAY,true)));
        assertEquals(0,IndependentPaperPortfolio.fundingOccurrences(
                candle(CandleInterval.M5,MONDAY,true)));
    }

    @Test
    void dailyWalkForwardRejectsOverlappingThirtyDayTradingWindows() {
        var a=request("1d","1w",MONDAY+150*DAY-1);
        var b=request("1d","1w",MONDAY+155*DAY-1);
        var backend=org.mockito.Mockito.mock(IndependentResearchService.class);
        assertThrows(IllegalArgumentException.class,
                ()->new IndependentWalkForwardService(backend).compare(
                        new IndependentWalkForwardService.Request(List.of(a,b))));
        org.mockito.Mockito.verifyNoInteractions(backend);
    }
}
