package br.com.tradingbot.analysis.volume;

import java.math.BigDecimal;
import java.util.List;

/** Medidas observaveis, nao ordens nem probabilidades de reversao. */
public record VolumeSnapshot(String symbol, String interval, long asOfCloseTime,
        int candlesAnalyzed, int referenceLookback, List<Observation> recentObservations) {
    public VolumeSnapshot {
        recentObservations = List.copyOf(recentObservations);
    }

    public enum VolumeLevel { HIGH, NORMAL, LOW, UNAVAILABLE }
    public enum SpreadLevel { WIDE, NORMAL, NARROW, UNAVAILABLE }
    public enum TakerBalance { BUY_DOMINANT, SELL_DOMINANT, BALANCED, UNAVAILABLE }
    public enum EffortResult {
        HIGH_EFFORT_SMALL_SPREAD,
        BULLISH_WIDE_SPREAD_HIGH_VOLUME,
        BEARISH_WIDE_SPREAD_HIGH_VOLUME,
        HIGH_VOLUME_UPPER_REJECTION,
        HIGH_VOLUME_LOWER_REJECTION,
        NONE
    }
    /** Ratios null quando o denominador e zero; nenhum valor e inventado. */
    public record Observation(long openTime, long closeTime, BigDecimal baseVolume,
            BigDecimal quoteVolume, BigDecimal relativeVolume,
            BigDecimal relativeSpread, BigDecimal takerBuyShare,
            VolumeLevel volumeLevel, SpreadLevel spreadLevel,
            TakerBalance takerBalance, EffortResult effortResult) {}
}
