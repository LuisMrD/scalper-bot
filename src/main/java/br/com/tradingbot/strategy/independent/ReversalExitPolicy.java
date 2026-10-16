package br.com.tradingbot.strategy.independent;

/** Isolated research exits; all entries and protective stops remain unchanged. */
public enum ReversalExitPolicy {
    BASELINE,
    PRICE_ACTION_VOLUME_REVERSAL
}
