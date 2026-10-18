package br.com.tradingbot.strategy.independent;

import java.math.BigDecimal;
import static br.com.tradingbot.strategy.independent.ClosedCandleFeatures.MC;

/**
 * Shared single-position balance for LONG and SHORT paper simulation. One
 * wallet per experimental policy and replay window. Never places orders.
 *
 * Arbitration: at simultaneous next-open fills LONG is evaluated first.
 * Both signals can be pending, but only the first fill can occupy the book.
 * Risk and daily loss limits operate on the SAME cash and UTC day.
 */
final class SharedPaperWallet {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final long DAY_MS = 86_400_000L;
    private final BigDecimal initialCapital;
    private BigDecimal cash, dailyRealized = BigDecimal.ZERO;
    private BigDecimal peak, worstDrawdownPct = BigDecimal.ZERO, markedEquity;
    private long day = Long.MIN_VALUE;
    private long openTime = Long.MIN_VALUE;
    private boolean occupiedAtOpen, filledThisBar;
    private IndependentPaperPortfolio owner;

    SharedPaperWallet(BigDecimal capital) {
        if (capital == null || capital.signum() <= 0) throw new IllegalArgumentException("capital");
        initialCapital = capital;
        cash = capital;
        peak = capital;
        markedEquity = capital;
    }
    void beginDay(long openTime) {
        // Snapshot occupancy BEFORE any exits/stops in this bar. A trade
        // stopped inside the bar must not unlock a retroactive OPEN fill.
        if (this.openTime != openTime) {
            this.openTime = openTime;
            occupiedAtOpen = owner != null;
            filledThisBar = false;
        }
        long nextDay = Math.floorDiv(openTime, DAY_MS);
        if (day != nextDay) {
            day = nextDay;
            dailyRealized = BigDecimal.ZERO;
        }
    }
    BigDecimal cash() { return cash; }
    BigDecimal dailyRealized() { return dailyRealized; }
    BigDecimal worstDrawdownPct() { return worstDrawdownPct; }
    BigDecimal markedEquity() { return markedEquity; }
    BigDecimal initialCapital() { return initialCapital; }
    boolean occupied() { return owner != null; }
    boolean entryBlocked() { return owner != null || occupiedAtOpen || filledThisBar; }

    void acquired(IndependentPaperPortfolio book, BigDecimal newCash) {
        if (entryBlocked()) throw new IllegalStateException("Shared open already consumed by a position");
        owner = java.util.Objects.requireNonNull(book,"book");
        filledThisBar = true;
        cash = newCash;
    }
    void funded(IndependentPaperPortfolio book, BigDecimal newCash) {
        if (owner != book) throw new IllegalStateException("Funding without owner");
        cash = newCash;
    }
    void released(IndependentPaperPortfolio book, BigDecimal newCash, BigDecimal realizedToday) {
        if (owner != book) throw new IllegalStateException("Close without owner");
        cash = newCash;
        dailyRealized = realizedToday;
        owner = null;
    }
    void mark(BigDecimal closePrice) {
        markedEquity = cash.add(owner == null ? BigDecimal.ZERO : owner.unrealized(closePrice));
        peak = peak.max(markedEquity);
        if (peak.signum() > 0) {
            BigDecimal dd = peak.subtract(markedEquity).multiply(HUNDRED, MC)
                    .divide(peak, MC);
            worstDrawdownPct = worstDrawdownPct.max(dd);
        }
    }
}
