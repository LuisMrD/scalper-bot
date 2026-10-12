package br.com.tradingbot.risk;

import br.com.tradingbot.analysis.context.MarketContextService;
import br.com.tradingbot.config.ExecutionMode;
import br.com.tradingbot.config.TradingProperties;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RiskPreviewServiceTest {
    @Test void missingFinancialParametersFailBeforeMarketDataOrExternalRequests() {
        var trading = new TradingProperties(List.of("BTCUSDT"),
                new TradingProperties.Execution(ExecutionMode.DISABLED, false),
                new TradingProperties.Risk(null, null, null, null));
        var market = mock(MarketContextService.class);
        var conf = new RiskManagementConfiguration(null, null, new BigDecimal("1"), new BigDecimal("0.5"));
        var service = new RiskPreviewService(trading, conf, market);
        assertFalse(service.configured());
        var req = RiskEngineTest.request(RiskEngineTest.decision(
                br.com.tradingbot.decision.DecisionResult.Entry.LONG,
                br.com.tradingbot.decision.DecisionResult.PositionAction.NONE, "JAVA"),
                null, "0", "0.001", "0.001");
        var error = assertThrows(ResponseStatusException.class, () -> service.preview(req));
        assertEquals(422, error.getStatusCode().value());
        verifyNoInteractions(market);
    }
}
