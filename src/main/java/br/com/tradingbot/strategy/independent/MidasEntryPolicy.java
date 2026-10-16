package br.com.tradingbot.strategy.independent;

/** Paper-only entry filters. Never replaces the initial protective stop. */
public enum MidasEntryPolicy {
    BASELINE,
    MIDAS_TREND,
    MIDAS_PULLBACK_REJECTION
}
