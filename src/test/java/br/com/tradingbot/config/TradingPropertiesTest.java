package br.com.tradingbot.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class TradingPropertiesTest {
    @Test
    void riskMustHaveNoImplicitValues() {
        var risk = new TradingProperties.Risk(null, null, null, null);
        assertFalse(risk.isConfigured());
    }

    @Test
    void riskIsConfiguredOnlyWhenEveryValueIsPresent() {
        var risk = new TradingProperties.Risk(
                new BigDecimal("300"), new BigDecimal("0.5"),
                new BigDecimal("3"), 2);
        assertTrue(risk.isConfigured());
    }

    @Test
    void configuredSymbolsMustBeImmutable() {
        var properties = new TradingProperties(List.of("BTCUSDT", "ETHUSDT"),
                new TradingProperties.Execution(ExecutionMode.DISABLED, false),
                new TradingProperties.Risk(null, null, null, null));
        assertThrows(UnsupportedOperationException.class,
                () -> properties.symbols().add("SOLUSDT"));
    }
}
