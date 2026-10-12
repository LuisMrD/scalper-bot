package br.com.tradingbot.integration.openai;

import br.com.tradingbot.decision.luna.LunaUnavailableException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Cliente somente texto para Responses API. Sem SDK de ordens nem ferramentas externas. */
@Component
public class OpenAiResponsesClient {
    private static final URI RESPONSES = URI.create("https://api.openai.com/v1/responses");
    private static final String SYSTEM_PROMPT = """
            Classifique os dados de mercado exclusivamente fornecidos no JSON do usuario.\n
            Nao invente preco, tendencia, candles ou probabilidade de lucro.\n
            O primeiro timeframe e o de entrada; os demais fornecem contexto.\n
            Para FLAT: entry LONG, SHORT ou WAIT; positionAction NONE.\n
            LONG e SHORT exigem setup PULLBACK ou BREAKOUT_RETEST confirmado; senao WAIT/NONE.\n
            Para posicao LONG/SHORT existente: entry WAIT, setup NONE, positionAction HOLD ou EXIT.\n
            EXIT somente quando a reversao for confirmada por estrutura e volume.\n
            Em caso de incerteza, conflito, sinal incompleto ou falha de confirmacao: WAIT.\n
            Nunca defina quantidade, alavancagem, stop, ordem ou conta.\n
            Selecione reason apenas dos enums do schema e coerente com a decisao.
            """;

    private final JsonMapper mapper;
    private final HttpClient http;

    @Autowired
    public OpenAiResponsesClient(JsonMapper mapper) {
        this(mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build());
    }

    OpenAiResponsesClient(JsonMapper mapper, HttpClient http) {
        this.mapper = mapper;
        this.http = http;
    }

    public String evaluate(String apiKey, String model, String contextJson,
            int maxOutputTokens, int timeoutSeconds) {
        if (apiKey == null || apiKey.isBlank())
            throw new LunaUnavailableException("Chave da OpenAI nao configurada");
        String payload = mapper.writeValueAsString(Map.of(
                "model", model,
                "store", false,
                "reasoning", Map.of("effort", "none"),
                "max_output_tokens", maxOutputTokens,
                "input", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", contextJson)),
                "text", Map.of("format", Map.of(
                        "type", "json_schema",
                        "name", "trading_decision",
                        "strict", true,
                        "schema", decisionSchema()))));
        HttpRequest request = HttpRequest.newBuilder(RESPONSES)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            // Nunca devolver o body do provedor: pode conter dados enviados pelo cliente.
            if (response.statusCode() != 200)
                throw new LunaUnavailableException("OpenAI HTTP " + response.statusCode());
            return extractText(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LunaUnavailableException("Consulta Luna interrompida", e);
        } catch (IOException e) {
            throw new LunaUnavailableException("Nao foi possivel consultar o Luna", e);
        }
    }

    /** Aceita somente uma resposta completamente finalizada, sem refusal ou tool calls. */
    public String extractText(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (!"completed".equals(root.path("status").asString()))
                throw new LunaUnavailableException("Resposta Luna nao concluida");
            JsonNode output = root.path("output");
            if (!output.isArray()) throw new LunaUnavailableException("Luna sem output valido");
            String text = null;
            for (JsonNode item : output) {
                if (!"message".equals(item.path("type").asString())) continue;
                if (!"completed".equals(item.path("status").asString()))
                    throw new LunaUnavailableException("Mensagem Luna incompleta");
                JsonNode content = item.path("content");
                if (!content.isArray()) continue;
                for (JsonNode part : content) {
                    if ("refusal".equals(part.path("type").asString()))
                        throw new LunaUnavailableException("Luna recusou a avaliacao");
                    if ("output_text".equals(part.path("type").asString())) {
                        if (text != null) throw new LunaUnavailableException("Luna retornou textos multiplos");
                        text = part.path("text").asString();
                    }
                }
            }
            if (text == null || text.isBlank())
                throw new LunaUnavailableException("Luna nao retornou texto estruturado");
            return text;
        } catch (LunaUnavailableException e) { throw e; }
          catch (RuntimeException e) { throw new LunaUnavailableException("Resposta Luna invalida", e); }
    }

    private static Map<String, Object> decisionSchema() {
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("entry", "positionAction", "setup", "reason"),
                "properties", Map.of(
                        "entry", choice("LONG", "SHORT", "WAIT"),
                        "positionAction", choice("NONE", "HOLD", "EXIT"),
                        "setup", choice("NONE", "PULLBACK", "BREAKOUT_RETEST"),
                        "reason", choice("STALE_MARKET_DATA", "TREND_UNCLEAR", "HIGHER_TIMEFRAME_CONFLICT",
                                "NO_SETUP", "VOLUME_NOT_CONFIRMED", "PULLBACK_CONFIRMED",
                                "BREAKOUT_RETEST_CONFIRMED", "POSITION_HOLD", "CONFIRMED_REVERSAL")));
    }

    private static Map<String, Object> choice(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }
}
