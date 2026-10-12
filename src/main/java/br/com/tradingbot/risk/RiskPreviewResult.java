package br.com.tradingbot.risk;

import java.math.BigDecimal;

/** PREVIA SEM ORDENS. Qualquer novo stop so pode valer a partir do proximo candle. */
public record RiskPreviewResult(String engine, String symbol, String interval,
        long asOfCloseTime, Action action, Reason reason,
        BigDecimal entryPrice, BigDecimal quantity, BigDecimal notionalUsdt,
        BigDecimal riskBudgetUsdt, BigDecimal initialStop,
        BigDecimal proposedStop, BigDecimal estimatedLossAtStopUsdt,
        boolean proposedStopEffectiveNextCandle) {
    public enum Action { NO_ACTION, BLOCKED, PROPOSED_ENTRY, MAINTAIN_STOP,
        ADJUST_STOP, EXIT_SIGNAL, STOP_TOUCHED }
    public enum Reason { NO_ENTRY_SIGNAL, MISSING_RISK_CONFIGURATION,
        DAILY_LOSS_LIMIT, NO_RISK_CAPACITY, NO_CONFIRMED_STRUCTURAL_STOP,
        BINANCE_FILTERS_NOT_MET, INVALID_DECISION_OR_POSITION,
        PREVIEW_ONLY, POSITION_HOLD, CONFIRMED_REVERSAL, STOP_TOUCHED,
        BREAK_EVEN_OR_TRAILING }
}
