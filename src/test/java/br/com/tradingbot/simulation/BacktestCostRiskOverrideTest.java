package br.com.tradingbot.simulation;

import br.com.tradingbot.analysis.context.MarketContextService;
import br.com.tradingbot.config.ExecutionMode;
import br.com.tradingbot.config.TradingProperties;
import br.com.tradingbot.decision.JavaDecisionConfiguration;
import br.com.tradingbot.decision.luna.LunaDecisionService;
import br.com.tradingbot.market.service.MarketDataService;
import br.com.tradingbot.risk.RiskManagementConfiguration;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BacktestCostRiskOverrideTest {
    private static BigDecimal b(String v) { return new BigDecimal(v); }

    private static BacktestService service(String envThreshold) {
        return new BacktestService(mock(MarketDataService.class), mock(MarketContextService.class),
                new JavaDecisionConfiguration(10, 4, 2, true, true, true, true),
                mock(LunaDecisionService.class),
                new TradingProperties(List.of("BTCUSDT"),
                        new TradingProperties.Execution(ExecutionMode.BACKTEST, false),
                        new TradingProperties.Risk(b("300"), b("0.5"), b("3"), 2)),
                new RiskManagementConfiguration(b("0.05"), b("0.02"), b("1"), b("0.5"),
                        envThreshold == null ? null : b(envThreshold)));
    }

    private static BacktestRequest request(String limit) {
        return new BacktestRequest("BTCUSDT", List.of("1m", "5m", "15m"),
                3000, 200, 1791823439999L, false,
                b("0.1"), b("0.001"), b("0.001"), b("5"),
                b("1.0"), limit == null ? null : b(limit));
    }

    @Test
    void identicalWindowCanCompareUnfilteredAndFilteredPoliciesWithoutMutatingEnv() {
        var service = service("30");
        assertEquals(0, service.policy(request(null)).maxCostToInitialRiskPct().compareTo(b("30")));
        assertNull(service.policy(request("0")).maxCostToInitialRiskPct());
        assertEquals(0, service.policy(request("15")).maxCostToInitialRiskPct().compareTo(b("15")));
        assertEquals(0, service.policy(request("30")).maxCostToInitialRiskPct().compareTo(b("30")));
        assertEquals(0, service.policy(request(null)).maxCostToInitialRiskPct().compareTo(b("30")));
    }

    @Test
    void absentEnvDisablesFilterAndCanBeOverriddenForOneRun() {
        var service = service(null);
        assertNull(service.policy(request(null)).maxCostToInitialRiskPct());
        assertEquals(0, service.policy(request("30")).maxCostToInitialRiskPct().compareTo(b("30")));
    }

    @Test
    void invalidOverridesRejectedBeforeDataAccessAndPaidLunaLimitPreserved() {
        assertThrows(IllegalArgumentException.class, () -> request("-0.01"));
        assertThrows(IllegalArgumentException.class, () -> request("100.01"));
        assertThrows(IllegalArgumentException.class, () -> new BacktestRequest(
                "BTCUSDT", List.of("1m"), 11, 200, null, true,
                b("0.1"), b("0.001"), b("0.001"), b("5"), b("1"), b("30")));
    }
}
