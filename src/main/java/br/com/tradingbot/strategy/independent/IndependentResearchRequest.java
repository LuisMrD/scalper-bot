package br.com.tradingbot.strategy.independent;

import java.math.BigDecimal;
import java.util.Objects;

/** Explicit research inputs. No capital, leverage, fee, funding or stop default. */
public record IndependentResearchRequest(
        String symbol,int steps,int contextCandles,String trendInterval,Long asOf,
        IndependentSetupParameters parameters,
        BigDecimal capitalUsdt,BigDecimal riskPerTradePct,
        BigDecimal dailyStopPct,int leverage,
        BigDecimal feePerSidePct,BigDecimal slippagePerSidePct,
        BigDecimal spreadPct,BigDecimal assumedFundingPer8hPct,
        BigDecimal priceTick,BigDecimal quantityStep,
        BigDecimal minimumQuantity,BigDecimal minimumNotionalUsdt,
        BigDecimal maxCostToInitialRiskPct, int maxHoldingCandles) {
    public IndependentResearchRequest {
        if(symbol==null||!symbol.matches("[A-Z0-9]{5,32}")||!symbol.endsWith("USDT"))
            throw new IllegalArgumentException("Configured Binance USDT perpetual symbol required");
        if(steps<30||steps>18000||contextCandles<60||contextCandles>500
                ||maxHoldingCandles<1||maxHoldingCandles>5000)
            throw new IllegalArgumentException("Invalid historical window or holding limit");
        if(asOf==null||asOf<0)throw new IllegalArgumentException("Explicit closed 5m asOf required");
        if(!java.util.List.of("15m","1h","4h").contains(trendInterval))
            throw new IllegalArgumentException("Choose explicit higher timeframe: 15m, 1h or 4h");
        Objects.requireNonNull(parameters,"Unapproved rules must be provided explicitly");
        positive(capitalUsdt,"capitalUsdt");
        positive(riskPerTradePct,"riskPerTradePct");
        positive(dailyStopPct,"dailyStopPct");
        if(riskPerTradePct.compareTo(BigDecimal.valueOf(100))>0
                ||dailyStopPct.compareTo(BigDecimal.valueOf(100))>0)
            throw new IllegalArgumentException("risk and daily stop must be <=100%");
        if(leverage<1||leverage>125)throw new IllegalArgumentException("Invalid leverage");
        nonnegative(feePerSidePct,"feePerSidePct");
        nonnegative(slippagePerSidePct,"slippagePerSidePct");
        nonnegative(spreadPct,"spreadPct");
        Objects.requireNonNull(assumedFundingPer8hPct,"assumedFundingPer8hPct is mandatory (explicit hypothesis)");
        positive(priceTick,"priceTick");
        positive(quantityStep,"quantityStep");
        positive(minimumQuantity,"minimumQuantity");
        positive(minimumNotionalUsdt,"minimumNotionalUsdt");
        positive(maxCostToInitialRiskPct,"maxCostToInitialRiskPct");
    }
    private static void positive(BigDecimal v,String field) {
        Objects.requireNonNull(v,field);
        if(v.signum()<=0)throw new IllegalArgumentException(field+" must be positive");
    }
    private static void nonnegative(BigDecimal v,String field) {
        Objects.requireNonNull(v,field);
        if(v.signum()<0)throw new IllegalArgumentException(field+" must be nonnegative");
    }
}
