package br.com.tradingbot.decision.luna;

import br.com.tradingbot.analysis.context.MarketContextService;
import br.com.tradingbot.config.OpenAiProperties;
import br.com.tradingbot.decision.DecisionResult;
import br.com.tradingbot.decision.PositionState;
import br.com.tradingbot.integration.openai.OpenAiResponsesClient;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class LunaDecisionService {
    private final MarketContextService contextService;
    private final OpenAiProperties settings;
    private final LunaDecisionEngine engine;

    public LunaDecisionService(MarketContextService contextService, OpenAiProperties settings,
            OpenAiResponsesClient client, JsonMapper mapper) {
        this.contextService = Objects.requireNonNull(contextService);
        this.settings = Objects.requireNonNull(settings);
        this.engine = new LunaDecisionEngine(settings, client, mapper);
    }

    /** Backtest calls the engine with the SAME immutable snapshot already read for Java. */
    public DecisionResult evaluateContext(br.com.tradingbot.analysis.context.MultiTimeframeMarketContext context,
            PositionState.Side side) {
        if (!settings.enabled()) throw new LunaUnavailableException("Luna desabilitado por LUNA_ENABLED=false");
        if (!settings.ready()) throw new LunaUnavailableException("OPENAI_API_KEY nao configurada");
        return engine.evaluate(context, new PositionState(side));
    }

    public boolean enabled() { return settings.enabled(); }
    public boolean ready() { return settings.ready(); }
    public String model() { return settings.model(); }

    /** Apenas chamada sob demanda; LUNA_ENABLED=false nao busca nem analisa contexto. */
    public DecisionResult analyze(String symbol, List<String> intervals, int limit,
            Long asOf, PositionState.Side side) {
        if (!settings.enabled()) throw new LunaUnavailableException("Luna desabilitado por LUNA_ENABLED=false");
        if (!settings.ready()) throw new LunaUnavailableException("OPENAI_API_KEY nao configurada");
        var context = contextService.analyzeMultiple(symbol, intervals, limit, asOf);
        return engine.evaluate(context, new PositionState(side));
    }
}
