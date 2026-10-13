package br.com.tradingbot.simulation;

import br.com.tradingbot.analysis.context.MarketContextService;
import br.com.tradingbot.config.ExecutionMode;
import br.com.tradingbot.config.TradingProperties;
import br.com.tradingbot.decision.JavaDecisionConfiguration;
import br.com.tradingbot.decision.luna.LunaDecisionService;
import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import br.com.tradingbot.market.service.MarketDataService;
import br.com.tradingbot.risk.RiskManagementConfiguration;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BacktestServiceTest {
    static BigDecimal b(String n) { return new BigDecimal(n); }
    static final long NOW=1800000000000L;
    private static BacktestRequest request(boolean luna) {
        return new BacktestRequest("BTCUSDT", List.of("1m"), 3, 30, NOW-1,
                luna,b("0.01"),b("0.001"),b("0.001"),b("5"));
    }
    private static BacktestService service(MarketDataService market,
            MarketContextService contexts,LunaDecisionService luna,boolean configured) {
        var risk = configured ? new TradingProperties.Risk(b("300"),b("0.5"),b("3"),2)
                : new TradingProperties.Risk(null,null,null,null);
        return new BacktestService(market,contexts,
                new JavaDecisionConfiguration(10,4,2,true,true,true,true),luna,
                new TradingProperties(List.of("BTCUSDT"),
                        new TradingProperties.Execution(ExecutionMode.BACKTEST,false),risk),
                new RiskManagementConfiguration(b("0.05"),b("0.02"),b("1"),b("0.5")),
                Clock.fixed(Instant.ofEpochMilli(NOW),ZoneOffset.UTC));
    }
    @Test void rejectPaidBacktestWithoutLunaReadyBeforeMarketLoading() {
        var market=mock(MarketDataService.class);
        var contexts=mock(MarketContextService.class);
        var luna=mock(LunaDecisionService.class);
        var ex=assertThrows(ResponseStatusException.class,
                () -> service(market,contexts,luna,true).run(request(true)));
        assertEquals(422,ex.getStatusCode().value());
        verifyNoInteractions(market,contexts);
    }
    @Test void financialParametersRemainMandatory() {
        var market=mock(MarketDataService.class);
        var contexts=mock(MarketContextService.class);
        var luna=mock(LunaDecisionService.class);
        var ex=assertThrows(ResponseStatusException.class,
                () -> service(market,contexts,luna,false).run(request(false)));
        assertEquals(422,ex.getStatusCode().value());
        verifyNoInteractions(market,contexts);
        verifyNoInteractions(luna);
    }
    @Test void replaySetupOverridesAreIndependentAndDoNotChangeConfiguredDefaults() {
        var svc = service(mock(MarketDataService.class), mock(MarketContextService.class),
                mock(LunaDecisionService.class), true);
        var base = request(false);
        var before = svc.resolveJavaParameters(base);
        assertTrue(before.pullbackEnabled());
        assertTrue(before.breakoutRetestEnabled());
        assertTrue(before.requireHigherTimeframeAlignment());

        var pullbackOnly = new BacktestRequest("BTCUSDT", List.of("1m"), 3, 30,
                NOW-1, false, b("0.01"), b("0.001"), b("0.001"), b("5"),
                null, b("50"), null, true, false);
        var retestOnly = new BacktestRequest("BTCUSDT", List.of("1m"), 3, 30,
                NOW-1, false, b("0.01"), b("0.001"), b("0.001"), b("5"),
                null, b("50"), null, false, true);
        var p = svc.resolveJavaParameters(pullbackOnly);
        var r = svc.resolveJavaParameters(retestOnly);
        assertTrue(p.pullbackEnabled());
        assertFalse(p.breakoutRetestEnabled());
        assertFalse(r.pullbackEnabled());
        assertTrue(r.breakoutRetestEnabled());
        assertEquals(before, svc.resolveJavaParameters(base));
        assertEquals(before.breakoutLookback(), r.breakoutLookback());
        assertEquals(before.retestMaxBars(), p.retestMaxBars());
        assertEquals(before.requireDominantTakerVolume(), r.requireDominantTakerVolume());
        assertThrows(IllegalArgumentException.class, () -> new BacktestRequest(
                "BTCUSDT", List.of("1m"), 3, 30, NOW-1, false,
                b("0.01"), b("0.001"), b("0.001"), b("5"),
                null, b("50"), null, false, false));
    }

    @Test void insufficientCandlesRejectsAndNeverCallsLuna() {
        var market=mock(MarketDataService.class);
        var contexts=mock(MarketContextService.class);
        var luna=mock(LunaDecisionService.class);
        when(market.recentAtOrBefore("BTCUSDT","1m",3,NOW-1)).thenReturn(List.of());
        var ex=assertThrows(ResponseStatusException.class,
                () -> service(market,contexts,luna,true).run(request(false)));
        assertEquals(422,ex.getStatusCode().value());
        verifyNoInteractions(luna,contexts);
    }
}
