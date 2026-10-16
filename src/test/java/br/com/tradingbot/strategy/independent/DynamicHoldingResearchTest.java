package br.com.tradingbot.strategy.independent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import br.com.tradingbot.analysis.context.MarketContextService;
import br.com.tradingbot.config.TradingProperties;
import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import br.com.tradingbot.market.service.MarketDataService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class DynamicHoldingResearchTest {
    private static final long START=1_760_000_000_000L;
    private static BigDecimal b(String s){return new BigDecimal(s);}
    private static IndependentSetupParameters params() {
        return new IndependentSetupParameters(2,2,3,5,b("0.5"),b("0.85"),
                b("0.85"),b("0.65"),b("2"),b("1.2"),b("2"),
                10,b("2"),b("0.85"),b("0.85"),b("1.2"),
                6,b("0.2"),b("1"),14,20,
                IndependentSetupParameters.DirectStopRule.BOX_BASE,b("1"));
    }
    private static IndependentResearchRequest request(String symbol,int steps,String cash) {
        return new IndependentResearchRequest(symbol,steps,60,"15m","1d",
                Instant.parse("2026-10-15T23:59:59.999Z").toEpochMilli(),
                params(),b(cash),b("0.5"),b("3"),1,b("0.05"),
                b("0.02"),b("0.01"),b("0"),b("0.5"),b("0.01"),
                b("0.001"),b("0.001"),b("5"),b("50"),30);
    }
    private static MarketCandle bar(int index,BigDecimal close,
            BigDecimal hi,BigDecimal lo) {
        long start=START+index*900000L;
        return new MarketCandle("BTCUSDT",CandleInterval.M15,start,start+899999L,
                close,hi,lo,close,b("100"),b("10000"),100,b("50"),
                b("5000"),true);
    }
    private static MarketCandle flat(int index) {
        return bar(index,b("100"),b("101"),b("99"));
    }
    private static IndependentSetupSignal longSignal() {
        return new IndependentSetupSignal(
                IndependentSetupSignal.Setup.TREND_PULLBACK_MIXED,
                IndependentSetupSignal.Status.CONFIRMED,
                flat(59).closeTime(),b("100"),b("80"),
                null,null,"CONFIRMED",List.of(),List.of());
    }
    private static List<MarketCandle> warmup() {
        var l=new ArrayList<MarketCandle>();
        for(int i=0;i<60;i++)l.add(flat(i));
        return l;
    }
    private static IndependentPaperPortfolio initialized(HoldingExitPolicy policy) {
        var p=new IndependentPaperPortfolio("TREND_PULLBACK_MIXED__"+policy.name(),
                request("BTCUSDT",3600,"600"),policy);
        p.close(flat(59),longSignal(),warmup());
        return p;
    }
    @Test void ruleRequiresDailySlopeAndConfirmedSupportingSwingOnCompletedBars() {
        var candles=new ArrayList<MarketCandle>();
        for(int i=0;i<60;i++) {
            var price=b("100").add(b("0.5").multiply(BigDecimal.valueOf(i)));
            var low=price.subtract(b("1"));
            if(i==52)low=price.subtract(b("12"));
            candles.add(bar(i,price,price.add(b("1")),low));
        }
        assertNull(TrendContinuationExitRule.failedCondition(candles,
                params(),false,WeeklyEmaDirection.Direction.UP));
        assertEquals("DYNAMIC_DAILY_TREND_LOST",
                TrendContinuationExitRule.failedCondition(candles,
                        params(),false,WeeklyEmaDirection.Direction.DOWN));
        var bad=new ArrayList<>(candles);
        bad.set(59,bar(59,b("90"),b("91"),b("89")));
        assertEquals("DYNAMIC_15M_EMA_TREND_LOST",
                TrendContinuationExitRule.failedCondition(bad,
                        params(),false,WeeklyEmaDirection.Direction.UP));
        // Confirmed swing must predate the current bar: a pivot at 58
        // needing bars 59/60 CANNOT be recognized by bar 59.
        var noPivot=new ArrayList<MarketCandle>();
        for(int i=0;i<60;i++){
            var px=b("100").add(b("0.5").multiply(BigDecimal.valueOf(i)));
            noPivot.add(bar(i,px,px.add(b("1")),px.subtract(b("1"))));
        }
        assertEquals("DYNAMIC_NO_CONFIRMED_STRUCTURE",
                TrendContinuationExitRule.failedCondition(noPivot,
                        params(),false,WeeklyEmaDirection.Direction.UP));
    }
    @Test void shortContinuationMirrorsDailyTrendAndSwingHigh() {
        var candles=new ArrayList<MarketCandle>();
        for(int i=0;i<60;i++) {
            var px=b("140").subtract(b("0.5").multiply(BigDecimal.valueOf(i)));
            var high=px.add(b("1"));
            if(i==52)high=px.add(b("12"));
            candles.add(bar(i,px,high,px.subtract(b("1"))));
        }
        assertNull(TrendContinuationExitRule.failedCondition(candles,
                params(),true,WeeklyEmaDirection.Direction.DOWN));
        assertEquals("DYNAMIC_DAILY_TREND_LOST",
                TrendContinuationExitRule.failedCondition(candles,
                        params(),true,WeeklyEmaDirection.Direction.UP));
    }
    @Test void baselineClosesAfter30HeldBarsBut45And60RemainOpen() {
        var p30=initialized(HoldingExitPolicy.BASELINE_30);
        var p45=initialized(HoldingExitPolicy.HOLD_45);
        var p60=initialized(HoldingExitPolicy.HOLD_60);
        var dynamic=initialized(HoldingExitPolicy.DYNAMIC_TREND_60);
        var accounts=List.of(p30,p45,p60,dynamic);
        for(int i=60;i<=89;i++) {
            var candle=flat(i);
            for(var p:accounts){
                p.begin(candle);
                p.close(candle,null,warmup(),WeeklyEmaDirection.Direction.FLAT);
            }
        }
        assertTrue(p30.summary(flat(89)).openPosition(),
                "The 30th close only schedules an exit for NEXT open");
        for(var p:List.of(p45,p60,dynamic))
            assertTrue(p.summary(flat(89)).openPosition());
        var next=flat(90);
        for(var p:accounts)p.begin(next);
        assertEquals("MAX_HOLDING",p30.summary(next).trades().getFirst().reason());
        assertEquals("DYNAMIC_DAILY_TREND_LOST",
                dynamic.summary(next).trades().getFirst().reason());
        assertTrue(p45.summary(next).openPosition());
        assertTrue(p60.summary(next).openPosition());
    }
    @Test void strongClosedTrendExtendsPast30AndCapsAt60WithoutChangingStop() {
        var p30=initialized(HoldingExitPolicy.BASELINE_30);
        var p45=initialized(HoldingExitPolicy.HOLD_45);
        var p60=initialized(HoldingExitPolicy.HOLD_60);
        var pDynamic=initialized(HoldingExitPolicy.DYNAMIC_TREND_60);
        var accounts=List.of(p30,p45,p60,pDynamic);
        var tape=new ArrayList<>(warmup());
        for(int i=60;i<=120;i++) {
            var price=b("100").add(b("0.8").multiply(BigDecimal.valueOf(i-60L)));
            // A genuine support pivot at index 82, confirmed at index 84.
            var low=i==82?b("104"):price.subtract(b("0.5"));
            var bar=bar(i,price,price.add(b("0.5")),low);
            tape.add(bar);
            var recent=tape.subList(Math.max(0,tape.size()-60),tape.size());
            for(var p:accounts) {
                p.begin(bar);
                p.close(bar,null,recent,WeeklyEmaDirection.Direction.UP);
            }
            if(i==90) {
                assertFalse(p30.summary(bar).openPosition());
                assertTrue(p45.summary(bar).openPosition());
                assertTrue(p60.summary(bar).openPosition());
                assertTrue(pDynamic.summary(bar).openPosition(),
                        "Dynamic must continue after bar 30 if all confirmations remain valid");
            }
            if(i==106) {
                assertFalse(p45.summary(bar).openPosition());
                assertTrue(p60.summary(bar).openPosition());
                assertTrue(pDynamic.summary(bar).openPosition());
            }
        }
        var last=tape.getLast();
        assertEquals("MAX_HOLDING",p30.summary(last).trades().getFirst().reason());
        assertEquals("MAX_HOLDING_45",p45.summary(last).trades().getFirst().reason());
        assertEquals("MAX_HOLDING_60",p60.summary(last).trades().getFirst().reason());
        assertEquals("MAX_HOLDING_60",pDynamic.summary(last).trades().getFirst().reason());
        assertFalse(pDynamic.summary(last).openPosition());
    }

    @Test void dynamicHardStopAlwaysWorksBeforeConditionalExit() {
        var dynamic=initialized(HoldingExitPolicy.DYNAMIC_TREND_60);
        var shock=bar(60,b("100"),b("101"),b("79"));
        dynamic.begin(shock);
        assertEquals("STOP",dynamic.summary(shock).trades().getFirst().reason());
    }
    @Test void apiRejectsWrongStudyInputsPriorToDatabaseOrMarketCalls(){
        var market=mock(MarketDataService.class);
        var context=mock(MarketContextService.class);
        var trading=mock(TradingProperties.class);
        var jdbc=mock(JdbcTemplate.class);
        var service=new IndependentResearchService(market,context,trading,jdbc,
                Clock.fixed(Instant.parse("2026-10-16T20:00:00Z"),ZoneOffset.UTC));
        for(var input:List.of(
                request("ETHUSDT",3600,"600"),
                request("BTCUSDT",2400,"600"),
                request("BTCUSDT",3600,"1500"))) {
            var ex=assertThrows(ResponseStatusException.class,
                    ()->service.backtestDynamicHolding900h(input));
            assertEquals(400,ex.getStatusCode().value());
        }
        verifyNoInteractions(market,context,trading,jdbc);
    }
}
