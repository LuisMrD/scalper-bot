package br.com.tradingbot.strategy.independent;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Research parameters have NO guessed trading defaults.
 * Every threshold comes from an explicit request or future approved configuration.
 */
public record IndependentSetupParameters(
        int pivotLeft, int pivotRight, int pullbackMinBars, int pullbackMaxBars,
        BigDecimal emaTouchTolerancePct,
        BigDecimal pullbackVolumeContractionMaxRatio,
        BigDecimal pullbackBodyContractionMaxRatio,
        BigDecimal bullishStrongBodyMinRatio,
        BigDecimal rejectionWickToBodyMinRatio,
        BigDecimal buyerVolumeGrowthMinRatio,
        BigDecimal exceptionalSellingVolumeMinRatio,
        int consolidationBars,
        BigDecimal boxWidthMaxAtr,
        BigDecimal boxProgressiveRangeMaxRatio,
        BigDecimal boxVolumeDryUpMaxRatio,
        BigDecimal breakoutRelativeVolumeMinRatio,
        int retestMaxBars,
        BigDecimal retestTolerancePct,
        BigDecimal stopBufferTicks,
        int atrPeriod, int volumeLookbackBars,
        DirectStopRule directStopRule, BigDecimal trailingActivationR) {
    public enum DirectStopRule { BOX_BASE, BREAKOUT_CANDLE_LOW }
    public IndependentSetupParameters {
        if (pivotLeft < 1 || pivotRight < 1 || pullbackMinBars < 1
                || pullbackMaxBars < pullbackMinBars || pullbackMaxBars > 200
                || consolidationBars < 3 || consolidationBars > 200
                || retestMaxBars < 1 || retestMaxBars > 200)
            throw new IllegalArgumentException("Invalid independent setup lookbacks");
        nonnegative(emaTouchTolerancePct, "emaTouchTolerancePct");
        betweenOpenZeroOne(pullbackVolumeContractionMaxRatio, "pullbackVolumeContractionMaxRatio");
        betweenOpenZeroOne(pullbackBodyContractionMaxRatio, "pullbackBodyContractionMaxRatio");
        betweenOpenZeroOne(bullishStrongBodyMinRatio, "bullishStrongBodyMinRatio");
        positive(rejectionWickToBodyMinRatio, "rejectionWickToBodyMinRatio");
        positive(buyerVolumeGrowthMinRatio, "buyerVolumeGrowthMinRatio");
        positive(exceptionalSellingVolumeMinRatio, "exceptionalSellingVolumeMinRatio");
        positive(boxWidthMaxAtr, "boxWidthMaxAtr");
        betweenOpenZeroOne(boxProgressiveRangeMaxRatio, "boxProgressiveRangeMaxRatio");
        betweenOpenZeroOne(boxVolumeDryUpMaxRatio, "boxVolumeDryUpMaxRatio");
        positive(breakoutRelativeVolumeMinRatio, "breakoutRelativeVolumeMinRatio");
        nonnegative(retestTolerancePct, "retestTolerancePct");
        nonnegative(stopBufferTicks, "stopBufferTicks");
        if(atrPeriod<2||atrPeriod>200||volumeLookbackBars<2||volumeLookbackBars>200)
            throw new IllegalArgumentException("ATR and volume lookbacks must be explicit");
        Objects.requireNonNull(directStopRule,"directStopRule is required");
        positive(trailingActivationR,"trailingActivationR");
    }
    private static void positive(BigDecimal v, String n) {
        Objects.requireNonNull(v, n + " is required");
        if (v.signum() <= 0) throw new IllegalArgumentException(n + " must be positive");
    }
    private static void nonnegative(BigDecimal v, String n) {
        Objects.requireNonNull(v, n + " is required");
        if (v.signum() < 0) throw new IllegalArgumentException(n + " cannot be negative");
    }
    private static void betweenOpenZeroOne(BigDecimal v, String n) {
        positive(v,n);
        if (v.compareTo(BigDecimal.ONE) > 0)
            throw new IllegalArgumentException(n + " cannot exceed 1");
    }
}
