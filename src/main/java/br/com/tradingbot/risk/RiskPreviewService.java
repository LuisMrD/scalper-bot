package br.com.tradingbot.risk;

import br.com.tradingbot.analysis.context.MarketContextService;
import br.com.tradingbot.config.TradingProperties;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Previa sob demanda com MESMA politica para decisoes JAVA/LUNA; nenhuma ordem enviada. */
@Service
public class RiskPreviewService {
    private final TradingProperties trading;
    private final RiskManagementConfiguration technical;
    private final MarketContextService contextService;
    private final RiskEngine engine = new RiskEngine();

    public RiskPreviewService(TradingProperties trading, RiskManagementConfiguration technical,
            MarketContextService contextService) {
        this.trading = Objects.requireNonNull(trading);
        this.technical = Objects.requireNonNull(technical);
        this.contextService = Objects.requireNonNull(contextService);
    }

    public boolean configured() {
        return trading.risk().isConfigured() && technical.feePerSidePct() != null
                && technical.slippagePerSidePct() != null
                && technical.breakEvenTriggerR() != null
                && technical.structuralAtrBuffer() != null;
    }

    public RiskPreviewResult preview(RiskPreviewRequest request) {
        if (!configured())
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Configure capital, risco, stop diario, leverage, fee e slippage antes da previa");
        var base = trading.risk();
        RiskPolicy policy;
        try {
            policy = new RiskPolicy(base.capitalUsdt(), base.riskPerTradePct(),
                    base.dailyStopPct(), base.leverage(), technical.feePerSidePct(),
                    technical.slippagePerSidePct(), technical.breakEvenTriggerR(),
                    technical.structuralAtrBuffer());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
        var decision = request.decision();
        if (!trading.symbols().contains(decision.symbol()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Par nao configurado");
        var context = contextService.analyze(decision.symbol(), decision.entryInterval(),
                request.candleLimit(), decision.requestedAsOfEpochMs());
        try {
            return engine.preview(request, context, policy);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex);
        }
    }
}
