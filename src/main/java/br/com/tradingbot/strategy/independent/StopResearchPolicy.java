package br.com.tradingbot.strategy.independent;

import java.math.BigDecimal;

/**
 * Experimental stop-only choices for a SEPARATE paper-only BTC 15m/1d study.
 * No policy is the production/default rule except BASELINE. All numeric
 * thresholds are hypotheses and must be validated out of sample.
 */
public enum StopResearchPolicy {
    BASELINE("0", "0", "0"),
    ATR_025("0.25", "0", "0"),
    ATR_050("0.50", "0", "0"),
    BREAK_EVEN_1_5R("0", "1.5", "0"),
    ATR_TRAIL_2_5("0", "1.5", "2.5");

    private final BigDecimal initialAtrMultiplier;
    private final BigDecimal activationR;
    private final BigDecimal trailingAtrMultiplier;

    StopResearchPolicy(String buffer, String activation, String trailing) {
        initialAtrMultiplier=new BigDecimal(buffer);
        activationR=new BigDecimal(activation);
        trailingAtrMultiplier=new BigDecimal(trailing);
    }
    public BigDecimal initialAtrMultiplier() {return initialAtrMultiplier;}
    public BigDecimal activationR() {return activationR;}
    public BigDecimal trailingAtrMultiplier() {return trailingAtrMultiplier;}
    public boolean breakEven() {return this==BREAK_EVEN_1_5R;}
    public boolean atrTrailing() {return this==ATR_TRAIL_2_5;}
}
