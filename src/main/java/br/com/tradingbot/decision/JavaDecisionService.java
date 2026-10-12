package br.com.tradingbot.decision;

import br.com.tradingbot.analysis.context.MarketContextService;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class JavaDecisionService {
    private final MarketContextService contextService;
    private final DecisionEngine engine;

    public JavaDecisionService(MarketContextService contextService, JavaDecisionConfiguration config) {
        this.contextService = Objects.requireNonNull(contextService);
        this.engine = new JavaDecisionEngine(config.toParameters());
    }

    /** Somente leitura; nao acessa saldo, API de ordens nem positions da corretora. */
    public DecisionResult analyze(String symbol, List<String> intervals, int limit,
            Long asOf, PositionState.Side positionSide) {
        Objects.requireNonNull(positionSide, "positionSide");
        var context = contextService.analyzeMultiple(symbol, intervals, limit, asOf);
        return engine.evaluate(context, new PositionState(positionSide));
    }
}
