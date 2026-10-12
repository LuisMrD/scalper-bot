package br.com.tradingbot.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExecutionSafetyGuardTest {
    private final BinanceProperties testnetUrls = new BinanceProperties(
            URI.create("https://demo-fapi.binance.com"),
            URI.create("wss://demo-fstream.binance.com"));

    @Test
    void mustRejectSubmissionEvenOnTestnet() {
        var props = properties(ExecutionMode.TESTNET, true);
        assertThrows(IllegalStateException.class,
                () -> new ExecutionSafetyGuard(props, testnetUrls).validate());
    }

    @Test
    void mustPermitReadOnlyTestnetConfiguration() {
        var props = properties(ExecutionMode.TESTNET, false);
        assertDoesNotThrow(() -> new ExecutionSafetyGuard(props, testnetUrls).validate());
    }

    @Test
    void mustRejectProductionHostOnTestnetProfile() {
        var props = properties(ExecutionMode.TESTNET, false);
        var unsafe = new BinanceProperties(
                URI.create("https://fapi.binance.com"),
                URI.create("wss://fstream.binance.com"));
        assertThrows(IllegalStateException.class,
                () -> new ExecutionSafetyGuard(props, unsafe).validate());
    }

    private static TradingProperties properties(ExecutionMode mode, boolean ordersEnabled) {
        return new TradingProperties(List.of("BTCUSDT"),
                new TradingProperties.Execution(mode, ordersEnabled),
                new TradingProperties.Risk(null, null, null, null));
    }
}
