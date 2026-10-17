package br.com.tradingbot.strategy.independent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import br.com.tradingbot.analysis.context.MarketContextService;
import br.com.tradingbot.config.TradingProperties;
import br.com.tradingbot.market.service.MarketDataService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** All gates are pre-entry and use ONLY a confirmed signal and next-open UTC date. */
class EntrySelectionResearchTest {
    private static BigDecimal b(String s){return new BigDecimal(s);}
    private static long signalClose(String nextOpen){
        return Instant.parse(nextOpen).toEpochMilli()-1L;
    }
    private static IndependentSetupSignal signal(String nextOpen,String stop,
            boolean shortSide) {
        return new IndependentSetupSignal(
                shortSide?IndependentSetupSignal.Setup.TREND_PULLBACK_MIXED_SHORT:
                        IndependentSetupSignal.Setup.TREND_PULLBACK_MIXED,
                IndependentSetupSignal.Status.CONFIRMED,
                signalClose(nextOpen),b("100"),b(stop),null,null,
                "CONFIRMED",List.of(),List.of());
    }
    private static EntrySelectionFilter.Assessment assess(EntrySelectionPolicy p,
            String nextOpen,String stop) {
        return EntrySelectionFilter.assess(p,signal(nextOpen,stop,false));
    }

    @Test void weekendUsesNEXT15MinuteOpenUtcIncludingMidnightBoundary() {
        var fri=assess(EntrySelectionPolicy.WEEKDAYS_ONLY,
                "2026-10-16T23:45:00Z","99.5");
        assertTrue(fri.allowed());
        assertEquals("FRIDAY",fri.nextOpenDayUtc());

        // Friday signal CLOSING at 23:59:59.999 -> Saturday 00:00
        var sat=assess(EntrySelectionPolicy.WEEKDAYS_ONLY,
                "2026-10-17T00:00:00Z","99.5");
        assertFalse(sat.allowed());
        assertEquals("SATURDAY",sat.nextOpenDayUtc());
        assertEquals(List.of("ENTRY_UTC_WEEKEND"),sat.rejectedReasons());

        var sun=assess(EntrySelectionPolicy.WEEKDAYS_ONLY,
                "2026-10-18T23:45:00Z","99.5");
        assertFalse(sun.allowed());

        // Sunday signal CLOSING at 23:59:59.999 -> Monday 00:00
        var monday=assess(EntrySelectionPolicy.WEEKDAYS_ONLY,
                "2026-10-19T00:00:00Z","99.5");
        assertTrue(monday.allowed());
        assertEquals("MONDAY",monday.nextOpenDayUtc());
    }

    @Test void thresholdUsesSignalCloseNotUnknownNextOpenOrTradeOutcome(){
        var at045=assess(EntrySelectionPolicy.STOP_045_ONLY,
                "2026-10-19T00:00:00Z","99.55");
        assertTrue(at045.allowed());
        assertEquals(0,at045.stopDistancePct().compareTo(b("0.45")));
        var beyond045=assess(EntrySelectionPolicy.STOP_045_ONLY,
                "2026-10-19T00:00:00Z","99.54");
        assertFalse(beyond045.allowed());
        assertEquals("ENTRY_STOP_DISTANCE_GT_045_PCT",beyond045.reason());
        assertTrue(assess(EntrySelectionPolicy.STOP_050_ONLY,
                "2026-10-19T00:00:00Z","99.54").allowed());
        assertTrue(assess(EntrySelectionPolicy.STOP_050_ONLY,
                "2026-10-19T00:00:00Z","99.50").allowed());
        assertFalse(assess(EntrySelectionPolicy.STOP_050_ONLY,
                "2026-10-19T00:00:00Z","99.49").allowed());
    }

    @Test void combinedReportsBOTHReasonsAndNeitherModifiesBaseline(){
        var candle=signal("2026-10-17T00:00:00Z","99.4",false);
        var combined=EntrySelectionFilter.assess(EntrySelectionPolicy.COMBINED_045,candle);
        assertEquals(List.of("ENTRY_UTC_WEEKEND","ENTRY_STOP_DISTANCE_GT_045_PCT"),
                combined.rejectedReasons());
        assertTrue(EntrySelectionFilter.assess(EntrySelectionPolicy.BASELINE,candle).allowed());
        assertFalse(EntrySelectionFilter.assess(EntrySelectionPolicy.STOP_050_ONLY,candle).allowed());
        assertFalse(EntrySelectionFilter.assess(EntrySelectionPolicy.WEEKDAYS_ONLY,candle).allowed());
    }

    @Test void shortDistanceRequiresStopAboveSignalAndMissingDistanceFailsClosed(){
        var ok=EntrySelectionFilter.assess(EntrySelectionPolicy.COMBINED_045,
                signal("2026-10-19T00:00:00Z","100.45",true));
        assertTrue(ok.allowed());
        var wrong=EntrySelectionFilter.assess(EntrySelectionPolicy.STOP_050_ONLY,
                signal("2026-10-19T00:00:00Z","99.9",true));
        assertEquals("ENTRY_INVALID_STOP_DISTANCE",wrong.reason());
        assertTrue(EntrySelectionFilter.assess(EntrySelectionPolicy.BASELINE,
                signal("2026-10-19T00:00:00Z","99.9",true)).allowed());
        var missingRef=new IndependentSetupSignal(
                IndependentSetupSignal.Setup.TREND_PULLBACK_MIXED,
                IndependentSetupSignal.Status.CONFIRMED,
                signalClose("2026-10-19T00:00:00Z"),null,b("99"),
                null,null,"CONFIRMED",List.of(),List.of());
        assertTrue(missingRef.actionable());
        assertEquals("ENTRY_INVALID_STOP_DISTANCE",
                EntrySelectionFilter.assess(EntrySelectionPolicy.STOP_045_ONLY,
                        missingRef).reason());
    }

    @Test void noAssessmentOnUnconfirmedSignal() {
        var notReady=new IndependentSetupSignal(
                IndependentSetupSignal.Setup.TREND_PULLBACK_MIXED,
                IndependentSetupSignal.Status.WAIT,
                signalClose("2026-10-19T00:00:00Z"),
                null,null,null,null,"WAIT",List.of(),List.of("NO_SIGNAL"));
        assertThrows(IllegalArgumentException.class,()->
                EntrySelectionFilter.assess(EntrySelectionPolicy.BASELINE,notReady));
    }

    @Test void apiRejectsModifiedCapitalSymbolWindowOrTimeLimitBeforeIO() {
        var market=mock(MarketDataService.class);
        var contexts=mock(MarketContextService.class);
        var trading=mock(TradingProperties.class);
        var jdbc=mock(JdbcTemplate.class);
        var service=new IndependentResearchService(market,contexts,trading,jdbc,
                Clock.fixed(Instant.parse("2026-10-17T23:00:00Z"),ZoneOffset.UTC));
        for(var bad:List.of(
                request("ETHUSDT",3600,"600",30),
                request("BTCUSDT",2400,"600",30),
                request("BTCUSDT",3600,"1500",30),
                request("BTCUSDT",3600,"600",60))) {
            assertEquals(400,assertThrows(ResponseStatusException.class,
                    ()->service.backtestEntrySelection900h(bad)).getStatusCode().value());
        }
        verifyNoInteractions(market,contexts,trading,jdbc);
    }

    private static IndependentResearchRequest request(String symbol,int steps,
            String capital,int maxHold) {
        return new IndependentResearchRequest(symbol,steps,60,"15m","1d",
                Instant.parse("2026-10-15T23:59:59.999Z").toEpochMilli(),
                params(),b(capital),b("0.5"),b("3"),1,
                b("0.05"),b("0.02"),b("0.01"),b("0"),b("0.5"),
                b("0.01"),b("0.001"),b("0.001"),b("5"),b("50"),maxHold);
    }
    private static IndependentSetupParameters params(){
        return new IndependentSetupParameters(2,2,3,5,b("0.5"),b("0.85"),
                b("0.85"),b("0.65"),b("2"),b("1.2"),b("2"),
                10,b("2"),b("0.85"),b("0.85"),b("1.2"),
                6,b("0.2"),b("1"),14,20,
                IndependentSetupParameters.DirectStopRule.BOX_BASE,b("1"));
    }
}
