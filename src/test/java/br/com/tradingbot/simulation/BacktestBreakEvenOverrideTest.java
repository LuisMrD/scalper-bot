package br.com.tradingbot.simulation;

import br.com.tradingbot.config.ExecutionMode;
import br.com.tradingbot.config.TradingProperties;
import br.com.tradingbot.decision.JavaDecisionConfiguration;
import br.com.tradingbot.decision.luna.LunaDecisionService;
import br.com.tradingbot.analysis.context.MarketContextService;
import br.com.tradingbot.market.service.MarketDataService;
import br.com.tradingbot.risk.RiskManagementConfiguration;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class BacktestBreakEvenOverrideTest {
    private static BigDecimal b(String s) { return new BigDecimal(s); }

    private static BacktestService service() {
        return new BacktestService(mock(MarketDataService.class),
                mock(MarketContextService.class),
                new JavaDecisionConfiguration(10,4,2,true,true,true,true),
                mock(LunaDecisionService.class),
                new TradingProperties(List.of("BTCUSDT"),
                        new TradingProperties.Execution(ExecutionMode.BACKTEST,false),
                        new TradingProperties.Risk(b("300"),b("0.5"),b("3"),2)),
                new RiskManagementConfiguration(b("0.05"),b("0.02"),b("1.0"),b("0.5")));
    }

    private static BacktestRequest request(String trigger) {
        return new BacktestRequest("BTCUSDT",List.of("1m","5m","15m"),
                3000,200,1700000000000L,false,b("0.1"),b("0.001"),
                b("0.001"),b("5"),trigger==null?null:b(trigger));
    }

    @Test void appliesBreakEvenOverrideWithoutMutatingEnvironmentPolicy() {
        var service=service();
        assertEquals(0,service.policy(request(null)).breakEvenTriggerR().compareTo(b("1.0")));
        assertEquals(0,service.policy(request("1.5")).breakEvenTriggerR().compareTo(b("1.5")));
        assertEquals(0,service.policy(request("2.0")).breakEvenTriggerR().compareTo(b("2.0")));
        assertEquals(0,service.policy(request(null)).breakEvenTriggerR().compareTo(b("1.0")));
    }

    @Test void validatesOptionalTriggerRangeWithoutChangingLunaPaidGuard() {
        assertThrows(IllegalArgumentException.class,()->request("0"));
        assertThrows(IllegalArgumentException.class,()->request("-1"));
        assertThrows(IllegalArgumentException.class,()->request("20.1"));
        assertThrows(IllegalArgumentException.class, () -> new BacktestRequest(
                "BTCUSDT",List.of("1m"),11,200,null,true,
                b("0.1"),b("0.001"),b("0.001"),b("5"),b("1.5")));
    }
}
