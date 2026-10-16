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

/** Tests actual causal pivot timing, weighted midpoints, and retest filters. */
class AnchoredMidasResearchTest {
    private static final long EPOCH=1_760_000_000_000L;
    private static BigDecimal b(String n){return new BigDecimal(n);}
    private static MarketCandle bar(int i,String o,String h,String l,String c,String v) {
        long t=EPOCH+i*900000L;
        return new MarketCandle("BTCUSDT",CandleInterval.M15,
                t,t+899999L,b(o),b(h),b(l),b(c),b(v),
                b(v).multiply(b("100")),100,b(v).divide(b("2")),
                b(v).multiply(b("50")),true);
    }
    private static IndependentSetupParameters params() {
        return new IndependentSetupParameters(2,2,3,5,b("0.5"),b("0.85"),
                b("0.85"),b("0.65"),b("2"),b("1.2"),b("2"),
                10,b("2"),b("0.85"),b("0.85"),b("1.2"),
                6,b("0.2"),b("1"),14,20,
                IndependentSetupParameters.DirectStopRule.BOX_BASE,b("1"));
    }
    private static List<MarketCandle> longTape() {
        return List.of(
                bar(0,"100","102","99","100","1"),
                bar(1,"100","102","99","100","1"),
                bar(2,"100","101","95","100","2"),
                bar(3,"100","102","99","100","1"),
                bar(4,"100","102","99","100","1"),
                bar(5,"101","104","100","103","1"));
    }
    private static List<AnchoredMidasCurve.Point> points(
            List<MarketCandle> tape,boolean shortSide) {
        var curve=new AnchoredMidasCurve(shortSide,params());
        var points=new ArrayList<AnchoredMidasCurve.Point>();
        for(int i=0;i<tape.size();i++)points.add(curve.observe(tape,i));
        return points;
    }
    @Test void lowPivotBecomesAnchorOnlyWhenTwoRightBarsCloseAndUsesBaseVolume() {
        var bars=longTape();
        var points=points(bars,false);
        for(int i=0;i<4;i++)assertFalse(points.get(i).available(),
                "No advance discovery of the low pivot");
        var at4=points.get(4);
        assertEquals(bars.get(2).openTime(),at4.anchorOpenTime());
        // ((101+95)/2 * 2 + (102+99)/2 + (102+99)/2) / 4
        assertEquals(0,at4.value().compareTo(b("99.25")));
        assertEquals(0,points.get(5).value().compareTo(b("99.8")));
        assertEquals(-1L,points.get(1).anchorOpenTime());
    }
    @Test void trendAndPullbackRejectionUsePastAnchorsAndDoNotBlockBaseline() {
        var bars=longTape();
        var p=points(bars,false);
        assertTrue(MidasEntryFilter.assess(MidasEntryPolicy.BASELINE,false,
                bars,p,5,params()).allowed());
        var trend=MidasEntryFilter.assess(MidasEntryPolicy.MIDAS_TREND,false,
                bars,p,5,params());
        assertTrue(trend.allowed());
        assertEquals(bars.get(2).openTime(),trend.anchorOpenTime());
        var retest=MidasEntryFilter.assess(MidasEntryPolicy.MIDAS_PULLBACK_REJECTION,
                false,bars,p,5,params());
        assertTrue(retest.allowed());
        assertNotNull(retest.touchedCloseTime());
    }
    @Test void noAnchorWrongSideAndNoSameAnchorRetestFailClosed() {
        var bars=longTape();
        var p=points(bars,false);
        assertEquals("MIDAS_NO_CONFIRMED_ANCHOR_OR_VOLUME",
                MidasEntryFilter.assess(MidasEntryPolicy.MIDAS_TREND,
                        false,bars,p,3,params()).reason());
        assertEquals("MIDAS_WRONG_SIDE_OF_CURVE",
                MidasEntryFilter.assess(MidasEntryPolicy.MIDAS_TREND,
                        true,bars,p,5,params()).reason());
        var forged=new ArrayList<>(p);
        for(int i=0;i<5;i++)
            forged.set(i,new AnchoredMidasCurve.Point(bars.get(i).closeTime(),
                    bars.get(0).openTime(),b("100")));
        assertEquals("MIDAS_NO_CONFIRMED_RETEST",
                MidasEntryFilter.assess(MidasEntryPolicy.MIDAS_PULLBACK_REJECTION,
                        false,bars,forged,5,params()).reason(),
                "A touch under the OLD anchor cannot confirm the NEW anchor");
    }
    @Test void shortsAnchorAtConfirmedSwingHighAndUseMirrorSide() {
        var bars=List.of(
                bar(0,"100","102","99","100","1"),
                bar(1,"100","102","99","100","1"),
                bar(2,"100","105","99","100","2"),
                bar(3,"100","102","99","100","1"),
                bar(4,"100","102","99","100","1"),
                bar(5,"100","100","98","99","1"));
        var p=points(bars,true);
        assertFalse(p.get(3).available());
        assertEquals(bars.get(2).openTime(),p.get(4).anchorOpenTime());
        // (102*2 + 100.5 + 100.5) /4 = 101.25
        assertEquals(0,p.get(4).value().compareTo(b("101.25")));
        assertTrue(MidasEntryFilter.assess(MidasEntryPolicy.MIDAS_TREND,
                true,bars,p,5,params()).allowed());
        assertTrue(MidasEntryFilter.assess(MidasEntryPolicy.MIDAS_PULLBACK_REJECTION,
                true,bars,p,5,params()).allowed());
    }
    @Test void zeroBaseVolumeMeansNoMidasAnchorPriceNotDivideByZero() {
        var bars=new ArrayList<MarketCandle>();
        for(int i=0;i<5;i++) {
            var v=longTape().get(i);
            bars.add(bar(i,v.open().toPlainString(),v.high().toPlainString(),
                    v.low().toPlainString(),v.close().toPlainString(),"0"));
        }
        assertFalse(points(bars,false).get(4).available());
    }
    @Test void missingOrUnclosedBarsCannotBuildFutureCurve() {
        var bars=new ArrayList<>(longTape());
        var curve=new AnchoredMidasCurve(false,params());
        assertThrows(IllegalArgumentException.class,()->curve.observe(bars,1));
        curve.observe(bars,0);
        bars.set(1,bar(2,"100","102","99","100","1"));
        assertThrows(IllegalArgumentException.class,()->curve.observe(bars,1));
    }
    @Test void badPaperRequestIsRejectedBeforeMarketOrDatabaseIO() {
        var market=mock(MarketDataService.class);
        var contexts=mock(MarketContextService.class);
        var trading=mock(TradingProperties.class);
        var jdbc=mock(JdbcTemplate.class);
        var service=new IndependentResearchService(market,contexts,trading,jdbc,
                Clock.fixed(Instant.parse("2026-10-16T21:00:00Z"),ZoneOffset.UTC));
        for(var bad:new Object[][]{
                {"ETHUSDT",3600,"600"},
                {"BTCUSDT",2400,"600"},
                {"BTCUSDT",3600,"1500"}
        }) {
            var request=new IndependentResearchRequest((String)bad[0],(int)bad[1],
                    60,"15m","1d",
                    Instant.parse("2026-10-15T23:59:59.999Z").toEpochMilli(),
                    params(),b((String)bad[2]),b("0.5"),b("3"),1,
                    b("0.05"),b("0.02"),b("0.01"),b("0"),b("0.5"),
                    b("0.01"),b("0.001"),b("0.001"),b("5"),b("50"),30);
            var e=assertThrows(ResponseStatusException.class,
                    ()->service.backtestMidas900h(request));
            assertEquals(400,e.getStatusCode().value());
        }
        verifyNoInteractions(market,contexts,trading,jdbc);
    }
}
