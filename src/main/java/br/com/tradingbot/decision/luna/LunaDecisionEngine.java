package br.com.tradingbot.decision.luna;

import br.com.tradingbot.analysis.context.MultiTimeframeMarketContext;
import br.com.tradingbot.config.OpenAiProperties;
import br.com.tradingbot.decision.DecisionEngine;
import br.com.tradingbot.decision.DecisionResult;
import br.com.tradingbot.decision.PositionState;
import br.com.tradingbot.integration.openai.OpenAiResponsesClient;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Avaliacao experimental. Nunca envia ordens, nunca altera gestao de risco. */
public final class LunaDecisionEngine implements DecisionEngine {
    private final OpenAiProperties config;
    private final OpenAiResponsesClient client;
    private final JsonMapper mapper;

    public LunaDecisionEngine(OpenAiProperties config, OpenAiResponsesClient client, JsonMapper mapper) {
        this.config = Objects.requireNonNull(config);
        this.client = Objects.requireNonNull(client);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public DecisionResult evaluate(MultiTimeframeMarketContext context, PositionState position) {
        if (!config.enabled()) throw new LunaUnavailableException("Luna desabilitado por LUNA_ENABLED=false");
        if (!config.ready()) throw new LunaUnavailableException("OPENAI_API_KEY nao configurada");
        Objects.requireNonNull(context);
        Objects.requireNonNull(position);
        var primary = context.timeframes().getFirst();
        long staleThreshold = Math.multiplyExact(primary.recentCandles().getLast().interval().millis(),
                config.maxPrimaryStalenessBars());
        if (context.requestedAsOfEpochMs() - primary.asOfCloseTime() > staleThreshold) {
            return new DecisionResult("LUNA", context.symbol(), primary.interval(),
                    context.requestedAsOfEpochMs(), primary.asOfCloseTime(), DecisionResult.Entry.WAIT,
                    position.side() == PositionState.Side.FLAT ? DecisionResult.PositionAction.NONE
                            : DecisionResult.PositionAction.HOLD,
                    DecisionResult.Setup.NONE, DecisionResult.Reason.STALE_MARKET_DATA);
        }
        String marketJson = mapper.writeValueAsString(Map.of(
                "positionSide", position.side().name(), "marketContext", context));
        String response = client.evaluate(config.apiKey(), config.model(), marketJson,
                config.maxOutputTokens(), config.timeoutSeconds());
        return parseDecision(response, context, position);
    }

    public DecisionResult parseDecision(String json, MultiTimeframeMarketContext context,
            PositionState position) {
        try {
            JsonNode root = mapper.readTree(json);
            if (!root.isObject() || root.size() != 4)
                throw new IllegalArgumentException("Quantidade de campos de decisao invalida");
            DecisionResult.Entry entry = DecisionResult.Entry.valueOf(text(root, "entry"));
            DecisionResult.PositionAction action = DecisionResult.PositionAction.valueOf(text(root, "positionAction"));
            DecisionResult.Setup setup = DecisionResult.Setup.valueOf(text(root, "setup"));
            DecisionResult.Reason reason = DecisionResult.Reason.valueOf(text(root, "reason"));
            boolean flat = position.side() == PositionState.Side.FLAT;
            if (flat && action != DecisionResult.PositionAction.NONE)
                throw new IllegalArgumentException("Motor tentou gerenciar posicao inexistente");
            if (!flat && (entry != DecisionResult.Entry.WAIT || setup != DecisionResult.Setup.NONE
                    || action == DecisionResult.PositionAction.NONE))
                throw new IllegalArgumentException("Motor tentou abrir operacao com posicao existente");
            if (entry != DecisionResult.Entry.WAIT && setup == DecisionResult.Setup.NONE)
                throw new IllegalArgumentException("Entrada sem setup confirmado");
            if (entry != DecisionResult.Entry.WAIT &&
                    reason != (setup == DecisionResult.Setup.PULLBACK
                            ? DecisionResult.Reason.PULLBACK_CONFIRMED
                            : DecisionResult.Reason.BREAKOUT_RETEST_CONFIRMED))
                throw new IllegalArgumentException("Entrada e motivo inconsistentes");
            if (action == DecisionResult.PositionAction.EXIT && reason != DecisionResult.Reason.CONFIRMED_REVERSAL)
                throw new IllegalArgumentException("Saida sem reversao confirmada");
            if (action == DecisionResult.PositionAction.HOLD && reason != DecisionResult.Reason.POSITION_HOLD)
                throw new IllegalArgumentException("Manutencao sem motivo valido");
            if (entry == DecisionResult.Entry.WAIT && action == DecisionResult.PositionAction.NONE &&
                    (reason == DecisionResult.Reason.PULLBACK_CONFIRMED ||
                     reason == DecisionResult.Reason.BREAKOUT_RETEST_CONFIRMED ||
                     reason == DecisionResult.Reason.CONFIRMED_REVERSAL ||
                     reason == DecisionResult.Reason.POSITION_HOLD))
                throw new IllegalArgumentException("WAIT sem justificativa coerente");
            var primary = context.timeframes().getFirst();
            return new DecisionResult("LUNA", context.symbol(), primary.interval(),
                    context.requestedAsOfEpochMs(), primary.asOfCloseTime(), entry, action, setup, reason);
        } catch (RuntimeException e) {
            throw new LunaUnavailableException("Luna retornou decisao invalida; nenhuma entrada autorizada", e);
        }
    }

    private static String text(JsonNode obj, String field) {
        JsonNode n = obj.get(field);
        if (n == null || !n.isTextual()) throw new IllegalArgumentException("Campo " + field + " ausente");
        return n.asString();
    }
}
