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
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** The dedicated longer research API cannot silently change capital, universe, or stop policy. */
class MixedBaseline900hResearchTest {
    private static BigDecimal b(String v){return new BigDecimal(v);}

    private static IndependentSetupParameters params() {
        return new IndependentSetupParameters(2,2,3,5,b("0.5"),b("0.85"),
                b("0.85"),b("0.65"),b("2"),b("1.2"),b("2"),
                10,b("2"),b("0.85"),b("0.85"),b("1.2"),
                6,b("0.2"),b("1"),14,20,
                IndependentSetupParameters.DirectStopRule.BOX_BASE,b("1"));
    }
    private static IndependentResearchRequest input(String symbol,int steps,String capital) {
        return new IndependentResearchRequest(symbol,steps,60,"15m","1d",
                Instant.parse("2026-10-15T23:59:59.999Z").toEpochMilli(),params(),
                b(capital),b("0.5"),b("3"),1,
                b("0.05"),b("0.02"),b("0.01"),b("0"),
                b("0.5"),b("0.01"),b("0.001"),b("0.001"),
                b("5"),b("50"),30);
    }
    @Test void independentPaperEndpointRejectsWrongCapitalStepsAndSymbolBeforeMarketAccess() {
        var market=mock(MarketDataService.class);
        var context=mock(MarketContextService.class);
        var trading=mock(TradingProperties.class);
        var jdbc=mock(JdbcTemplate.class);
        var service=new IndependentResearchService(market,context,trading,jdbc,
                Clock.fixed(Instant.parse("2026-10-16T18:00:00Z"),ZoneOffset.UTC));
        for(var attempt:new IndependentResearchRequest[]{
                input("BTCUSDT",3600,"1500"),
                input("BTCUSDT",2400,"600"),
                input("SOLUSDT",3600,"600")}) {
            var failure=assertThrows(ResponseStatusException.class,
                    ()->service.backtestMixedBaseline900h(attempt));
            assertEquals(400,failure.getStatusCode().value());
        }
        verifyNoInteractions(market,context,trading,jdbc);
    }
    @Test void baselineMixedAccountNeverUsesExperimentalReversalEvenOnStrongOpposingClose() {
        assertEquals("BASELINE",ReversalExitPolicy.BASELINE.name());
        var request=input("BTCUSDT",3600,"600");
        var defaultLong=new IndependentPaperPortfolio("TREND_PULLBACK_MIXED",request);
        var explicitLong=new IndependentPaperPortfolio("TREND_PULLBACK_MIXED",request,
                StopResearchPolicy.BASELINE,ReversalExitPolicy.BASELINE);
        var defaultShort=new IndependentPaperPortfolio("TREND_PULLBACK_MIXED_SHORT",request);
        var explicitShort=new IndependentPaperPortfolio("TREND_PULLBACK_MIXED_SHORT",request,
                StopResearchPolicy.BASELINE,ReversalExitPolicy.BASELINE);
        assertEquals(defaultLong.summary(nullLast()).arm(),explicitLong.summary(nullLast()).arm());
        assertEquals(defaultShort.summary(nullLast()).arm(),explicitShort.summary(nullLast()).arm());
    }

    private static br.com.tradingbot.market.model.MarketCandle nullLast() {
        long open=Instant.parse("2026-10-15T00:00:00Z").toEpochMilli();
        return new br.com.tradingbot.market.model.MarketCandle(
                "BTCUSDT",br.com.tradingbot.market.model.CandleInterval.M15,
                open,open+899999,b("100"),b("101"),b("99"),b("100"),
                b("1000"),b("100000"),100,b("500"),b("50000"),true);
    }
}
