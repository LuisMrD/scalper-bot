package br.com.tradingbot.decision;

import br.com.tradingbot.analysis.context.MultiTimeframeMarketContext;

/** Contrato sem dependencia de corretora ou provedor de IA. Nunca executa ordens. */
public interface DecisionEngine {
    DecisionResult evaluate(MultiTimeframeMarketContext context, PositionState position);
}
