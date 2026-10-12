package br.com.tradingbot.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

/**
 * Regression for startup failure:
 * RiskManagementConfiguration must be created by Spring configuration-properties
 * binding (not manual constructor invocation), even with multiple constructors.
 */
class RiskManagementConfigurationBindingTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RiskManagementConfiguration.class)
    static class RiskBindingConfig {}

    private static RiskManagementConfiguration bind(Map<String, Object> properties) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test-risk-config", properties));
            context.register(RiskBindingConfig.class);
            context.refresh();
            return context.getBean(RiskManagementConfiguration.class);
        }
    }

    @Test
    void bindsFiveRiskPropertiesUsingCanonicalConstructor() {
        var bound = bind(Map.of(
                "risk-management.fee-per-side-pct", "0.05",
                "risk-management.slippage-per-side-pct", "0.02",
                "risk-management.break-even-trigger-r", "1.0",
                "risk-management.structural-atr-buffer", "0.5",
                "risk-management.max-cost-to-initial-risk-pct", "30"));
        assertEquals(0, bound.feePerSidePct().compareTo(new BigDecimal("0.05")));
        assertEquals(0, bound.slippagePerSidePct().compareTo(new BigDecimal("0.02")));
        assertEquals(0, bound.breakEvenTriggerR().compareTo(new BigDecimal("1.0")));
        assertEquals(0, bound.structuralAtrBuffer().compareTo(new BigDecimal("0.5")));
        assertEquals(0, bound.maxCostToInitialRiskPct().compareTo(new BigDecimal("30")));
    }

    @Test
    void absentOptionalCostFilterStillBindsAndRemainsDisabled() {
        var bound = bind(Map.of(
                "risk-management.fee-per-side-pct", "0.05",
                "risk-management.slippage-per-side-pct", "0.02",
                "risk-management.break-even-trigger-r", "1.0",
                "risk-management.structural-atr-buffer", "0.5"));
        assertEquals(0, bound.feePerSidePct().compareTo(new BigDecimal("0.05")));
        assertNull(bound.maxCostToInitialRiskPct());
    }
}
