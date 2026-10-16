package br.com.tradingbot.strategy.independent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import br.com.tradingbot.analysis.context.MarketContextService;
import br.com.tradingbot.config.TradingProperties;
import br.com.tradingbot.market.model.MarketCandle;
import br.com.tradingbot.market.service.MarketDataService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class MidasHybridResearchTest {
    private static BigDecimal b(String s){return new BigDecimal(s);}
    private static IndependentSetupParameters params(){
        return new IndependentSetupParameters(2,2,3,5,b("0.5"),b("0.85"),
                b("0.85"),b("0.65"),b("2"),b("1.2"),b("2"),
                10,b("2"),b("0.85"),b("0.85"),b("1.2"),
                6,b("0.2"),b("1"),14,20,
                IndependentSetupParameters.DirectStopRule.BOX_BASE,b("1"));
    }
    private static IndependentResearchRequest request(String symbol,int bars,String cash,long asOf) {
        return new IndependentResearchRequest(symbol,bars,60,"15m","1d",
                asOf,params(),b(cash),b("0.5"),b("3"),1,b("0.05"),
                b("0.02"),b("0.01"),b("0"),b("0.5"),b("0.01"),
                b("0.001"),b("0.001"),b("5"),b("50"),30);
    }
    private static long millis(String date){return Instant.parse(date).toEpochMilli();}
    private static IndependentResearchService service(MarketDataService market,
            MarketContextService context,TradingProperties trading,JdbcTemplate jdbc) {
        return new IndependentResearchService(market,context,trading,jdbc,
                Clock.fixed(Instant.parse("2026-11-24T12:00:00Z"),ZoneOffset.UTC));
    }

    @Test void hybridAlwaysLeavesLongUnfilteredAndOnlyAppliesExistingRetestToShort(){
        for(var p:MidasHybridPolicy.values()){
            assertEquals(MidasEntryPolicy.BASELINE,p.entryPolicy(false));
        }
        assertEquals(MidasEntryPolicy.BASELINE,
                MidasHybridPolicy.BASELINE.entryPolicy(true));
        assertEquals(MidasEntryPolicy.MIDAS_PULLBACK_REJECTION,
                MidasHybridPolicy.HYBRID_LONG_BASELINE_SHORT_MIDAS_RETEST.entryPolicy(true));
    }
    @Test void previouslyTuned450DayResearchIsNeverAcceptedAsProspectiveOos(){
        var market=mock(MarketDataService.class);
        var context=mock(MarketContextService.class);
        var trading=mock(TradingProperties.class);
        var jdbc=mock(JdbcTemplate.class);
        var service=service(market,context,trading,jdbc);
        var bad=request("BTCUSDT",3600,"600",millis("2026-10-15T23:59:59.999Z"));
        var err=assertThrows(ResponseStatusException.class,
                ()->service.backtestMidasHybridOos900h(bad));
        assertEquals(400,err.getStatusCode().value());
        verifyNoInteractions(market,context,trading,jdbc);
    }
    @Test void oosBoundaryAcceptsExactly900HoursAfterSelectionWithoutPretendingHistoryExists() {
        var market=mock(MarketDataService.class);
        var context=mock(MarketContextService.class);
        var trading=mock(TradingProperties.class);
        var jdbc=mock(JdbcTemplate.class);
        when(trading.symbols()).thenReturn(List.of("BTCUSDT"));
        var service=service(market,context,trading,jdbc);
        // 3600 x 15m after Oct16 00:00 UTC -> Nov22 11:59:59.999
        var request=request("BTCUSDT",3600,"600",
                millis("2026-11-22T11:59:59.999Z"));
        var missingData=assertThrows(ResponseStatusException.class,
                ()->service.backtestMidasHybridOos900h(request));
        assertEquals(422,missingData.getStatusCode().value());
        verify(market).recentAtOrBefore("BTCUSDT","15m",4600,request.asOf());
        verifyNoInteractions(context,jdbc);
    }
    @Test void bothEndpointsRejectModifiedCapitalSymbolAndBarsBeforeAnyMarketIO(){
        var market=mock(MarketDataService.class);
        var context=mock(MarketContextService.class);
        var trading=mock(TradingProperties.class);
        var jdbc=mock(JdbcTemplate.class);
        var service=service(market,context,trading,jdbc);
        long cutoff=millis("2026-11-22T23:59:59.999Z");
        var badInputs=List.of(
                request("ETHUSDT",3600,"600",cutoff),
                request("BTCUSDT",2400,"600",cutoff),
                request("BTCUSDT",3600,"1500",cutoff));
        for(var wrong:badInputs){
            assertEquals(400,assertThrows(ResponseStatusException.class,
                    ()->service.backtestMidasHybrid900h(wrong)).getStatusCode().value());
            assertEquals(400,assertThrows(ResponseStatusException.class,
                    ()->service.backtestMidasHybridOos900h(wrong)).getStatusCode().value());
        }
        verifyNoInteractions(market,context,trading,jdbc);
    }
}
