package br.com.tradingbot.decision;

import java.util.Objects;

/** Sem probabilidades inventadas, precos de stop, quantidades ou comandos de execucao. */
public record DecisionResult(
        String engine, String symbol, String entryInterval,
        long requestedAsOfEpochMs, long evaluatedCandleCloseTime,
        Entry entry, PositionAction positionAction, Setup setup, Reason reason) {

    public enum Entry { LONG, SHORT, WAIT }
    public enum PositionAction { NONE, HOLD, EXIT }
    public enum Setup { NONE, PULLBACK, BREAKOUT_RETEST }
    public enum Reason {
        STALE_MARKET_DATA, TREND_UNCLEAR, HIGHER_TIMEFRAME_CONFLICT, NO_SETUP, VOLUME_NOT_CONFIRMED,
        PULLBACK_CONFIRMED, BREAKOUT_RETEST_CONFIRMED, ENTRY_BREAKOUT_FILTER, ENTRY_REENTRY_WAIT,
        POSITION_HOLD, CONFIRMED_REVERSAL
    }

    public DecisionResult {
        Objects.requireNonNull(engine);
        Objects.requireNonNull(symbol);
        Objects.requireNonNull(entryInterval);
        Objects.requireNonNull(entry);
        Objects.requireNonNull(positionAction);
        Objects.requireNonNull(setup);
        Objects.requireNonNull(reason);
        if (requestedAsOfEpochMs < 0 || evaluatedCandleCloseTime > requestedAsOfEpochMs)
            throw new IllegalArgumentException("Horario da decisao invalido");
        if (positionAction != PositionAction.NONE && entry != Entry.WAIT)
            throw new IllegalArgumentException("Nao abrir posicao ao gerenciar posicao existente");
        if (entry == Entry.WAIT && setup != Setup.NONE)
            throw new IllegalArgumentException("WAIT nao pode sinalizar setup executavel");
    }
}
