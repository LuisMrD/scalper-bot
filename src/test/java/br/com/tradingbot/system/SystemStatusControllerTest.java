package br.com.tradingbot.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import br.com.tradingbot.config.ExecutionMode;
import br.com.tradingbot.config.TradingProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class SystemStatusControllerTest {
    @Test
    void mustNotReportAnyOrderOrAiExecutionAsAvailable() {
        var trading = new TradingProperties(List.of("BTCUSDT", "ETHUSDT"),
                new TradingProperties.Execution(ExecutionMode.PAPER, false),
                new TradingProperties.Risk(null, null, null, null));
        var response = new SystemStatusController(trading).status();

        assertEquals(ExecutionMode.PAPER, response.mode());
        assertEquals(List.of("BTCUSDT", "ETHUSDT"), response.configuredSymbols());
        assertFalse(response.riskConfigured());
        assertFalse(response.orderSubmissionAvailable());
        assertFalse(response.openAiIntegrationActive());
    }
}
