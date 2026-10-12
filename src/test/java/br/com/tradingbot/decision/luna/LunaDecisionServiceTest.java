package br.com.tradingbot.decision.luna;

import br.com.tradingbot.analysis.context.MarketContextService;
import br.com.tradingbot.config.OpenAiProperties;
import br.com.tradingbot.decision.PositionState;
import br.com.tradingbot.integration.openai.OpenAiResponsesClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LunaDecisionServiceTest {
    @Test void whenDisabledServiceDoesNotEvenLoadMarketContext() {
        var market = mock(MarketContextService.class);
        var client = mock(OpenAiResponsesClient.class);
        var config = new OpenAiProperties(false, "gpt-6-luna", "test-secret", 512, 20, 2);
        var service = new LunaDecisionService(market, config, client, new JsonMapper());
        assertFalse(service.enabled());
        assertFalse(service.ready());
        assertThrows(LunaUnavailableException.class,
                () -> service.analyze("BTCUSDT", List.of("1m"), 200, null, PositionState.Side.FLAT));
        verifyNoInteractions(market, client);
    }

    @Test void whenEnabledButWithoutKeyNoMarketRequestOccurs() {
        var market = mock(MarketContextService.class);
        var client = mock(OpenAiResponsesClient.class);
        var config = new OpenAiProperties(true, "gpt-6-luna", "", 512, 20, 2);
        var service = new LunaDecisionService(market, config, client, new JsonMapper());
        assertTrue(service.enabled());
        assertFalse(service.ready());
        assertThrows(LunaUnavailableException.class,
                () -> service.analyze("BTCUSDT", List.of("1m"), 200, null, PositionState.Side.FLAT));
        verifyNoInteractions(market, client);
    }
}
