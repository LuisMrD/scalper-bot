package br.com.tradingbot.integration.openai;

import br.com.tradingbot.decision.luna.LunaUnavailableException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiResponsesClientTest {
    private final OpenAiResponsesClient client = new OpenAiResponsesClient(new JsonMapper());

    @Test void parsesCompletedStructuredOutput() {
        String raw = """
                {"status":"completed","output":[{"type":"message","status":"completed",
                "content":[{"type":"output_text","text":"{\\"entry\\":\\"WAIT\\"}"}]}]}
                """;
        assertEquals("{\"entry\":\"WAIT\"}", client.extractText(raw));
    }

    @Test void refusesIncompleteAndRefusals() {
        assertThrows(LunaUnavailableException.class,
                () -> client.extractText("{\"status\":\"incomplete\",\"output\":[]}"));
        assertThrows(LunaUnavailableException.class,
                () -> client.extractText("{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"status\":\"completed\",\"content\":[{\"type\":\"refusal\"}]}]}"));
    }

    @Test void rejectsMultipleTextsAndMalformedJson() {
        assertThrows(LunaUnavailableException.class,
                () -> client.extractText("{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":\"A\"},{\"type\":\"output_text\",\"text\":\"B\"}]}]}"));
        assertThrows(LunaUnavailableException.class,
                () -> client.extractText("invalid"));
    }
}
