package br.com.tradingbot.simulation;

import br.com.tradingbot.analysis.context.MarketContextSnapshot;
import br.com.tradingbot.decision.DecisionResult;
import br.com.tradingbot.decision.PositionState;
import br.com.tradingbot.market.model.MarketCandle;
import br.com.tradingbot.risk.RiskEngine;
import br.com.tradingbot.risk.RiskPolicy;
import br.com.tradingbot.risk.RiskPreviewRequest;
import br.com.tradingbot.risk.RiskPreviewResult;
import br.com.tradingbot.risk.SimulatedPosition;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** Independent cash ledger. Signals after close; executions at NEXT open, stop-first inside bar. */
final class BacktestLedger {
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private final String engine;
    private final RiskPolicy policy;
    private final BacktestRequest request;
    private final RiskEngine risk = new RiskEngine();
    private final ArrayList<BacktestResult.Trade> trades = new ArrayList<>();
    private BigDecimal cash;
    private BigDecimal peak;
    private BigDecimal maxDrawdown = BigDecimal.ZERO;
    private BigDecimal feeTotal = BigDecimal.ZERO;
    private BigDecimal dailyRealized = BigDecimal.ZERO;
    private LocalDate day;
    private Position position;
    private PendingEntry pendingEntry;
    private boolean pendingExit;
    private int blocked;
    private int signals;

    private record PendingEntry(PositionState.Side side, BigDecimal quantity,
            BigDecimal stop, BigDecimal budget, long signalCloseTime) {}
    private record Position(PositionState.Side side, BigDecimal qty, BigDecimal entry,
            BigDecimal initialStop, BigDecimal stop, BigDecimal entryFee,
            long signalCloseTime, long entryOpenTime) {
        Position withStop(BigDecimal next) {
            return new Position(side, qty, entry, initialStop, next, entryFee, signalCloseTime, entryOpenTime);
        }
    }

    BacktestLedger(String engine, RiskPolicy policy, BacktestRequest request) {
        this.engine = engine;
        this.policy = policy;
        this.request = request;
        cash = policy.capitalUsdt();
        peak = cash;
    }

    PositionState.Side side() { return position == null ? PositionState.Side.FLAT : position.side(); }
    private BigDecimal fraction(BigDecimal pct) { return pct.divide(HUNDRED, MC); }
    private BigDecimal slippage(BigDecimal price, PositionState.Side positionSide, boolean buy) {
        BigDecimal rate = fraction(policy.slippagePerSidePct());
        return buy ? price.multiply(BigDecimal.ONE.add(rate), MC)
                : price.multiply(BigDecimal.ONE.subtract(rate), MC);
    }
    private static BigDecimal floor(BigDecimal value, BigDecimal step) {
        return value.divide(step, 0, RoundingMode.FLOOR).multiply(step);
    }
    private BigDecimal commission(BigDecimal px, BigDecimal qty) {
        return px.multiply(qty, MC).multiply(fraction(policy.feePerSidePct()), MC);
    }
    private void fee(BigDecimal value) {
        cash = cash.subtract(value);
        feeTotal = feeTotal.add(value);
        dailyRealized = dailyRealized.subtract(value);
    }
    private void resetDay(long epochMillis) {
        LocalDate next = Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate();
        if (!next.equals(day)) { day = next; dailyRealized = BigDecimal.ZERO; }
    }

    /** Called at every new candle open. A pending decision can only fill HERE. */
    void begin(MarketCandle candle) {
        resetDay(candle.openTime());
        if (pendingExit && position != null) close(candle.open(), candle.openTime(), "EXIT_SIGNAL");
        pendingExit = false;
        if (pendingEntry != null && position == null) {
            var proposal = pendingEntry;
            pendingEntry = null;
            boolean longSide = proposal.side() == PositionState.Side.LONG;
            BigDecimal fill = slippage(candle.open(), proposal.side(), longSide);
            if (fill.signum() <= 0 || (longSide ? fill.compareTo(proposal.stop()) <= 0
                    : fill.compareTo(proposal.stop()) >= 0)) { blocked++; }
            else {
                // Gap at next open may increase risk; resize down, NEVER enlarge the approved quantity.
                BigDecimal distance = fill.subtract(proposal.stop()).abs();
                BigDecimal exitRate = fraction(policy.feePerSidePct().add(policy.slippagePerSidePct()));
                BigDecimal riskPerUnit = distance.add(fill.add(proposal.stop()).multiply(exitRate, MC));
                BigDecimal riskQty = floor(proposal.budget().divide(riskPerUnit, MC), request.quantityStep());
                BigDecimal marginQty = floor(cash.multiply(BigDecimal.valueOf(policy.leverage()), MC)
                        .divide(fill, MC), request.quantityStep());
                BigDecimal qty = floor(proposal.quantity().min(riskQty).min(marginQty), request.quantityStep());
                if (qty.compareTo(request.minimumQuantity()) < 0
                        || qty.multiply(fill).compareTo(request.minimumNotionalUsdt()) < 0) { blocked++; }
                else {
                    BigDecimal openingFee = commission(fill, qty);
                    if (openingFee.compareTo(cash) >= 0) blocked++;
                    else {
                        position = new Position(proposal.side(), qty, fill, proposal.stop(), proposal.stop(),
                                openingFee, proposal.signalCloseTime(), candle.openTime());
                        fee(openingFee);
                    }
                }
            }
        }
        // Stop is assumed triggered whenever current candle range crosses it. In an OHLC candle
        // with ambiguous ordering, stop takes precedence and adverse gaps worsen the fill.
        if (position != null && (position.side() == PositionState.Side.LONG
                ? candle.low().compareTo(position.stop()) <= 0
                : candle.high().compareTo(position.stop()) >= 0)) {
            boolean longSide = position.side() == PositionState.Side.LONG;
            BigDecimal executable = longSide ? candle.open().min(position.stop())
                    : candle.open().max(position.stop());
            close(executable, candle.openTime(), "STOP");
        }
        pendingEntry = null;
    }

    /** Applied only after the candle closes; proposed stops become active at the next bar. */
    RiskPreviewResult decide(DecisionResult decision, MarketContextSnapshot context) {
        var req = new RiskPreviewRequest(decision, cash.max(new BigDecimal("0.00000001")),
                dailyRealized, request.priceTick(), request.quantityStep(),
                request.minimumQuantity(), request.minimumNotionalUsdt(), request.contextLimit(),
                position == null ? null : new SimulatedPosition(position.side(), position.entry(),
                        position.initialStop(), position.stop(), position.qty(), position.signalCloseTime()));
        RiskPreviewResult proposal = risk.preview(req, context, policy);
        applyPreview(proposal, decision, context.asOfCloseTime());
        return proposal;
    }

    /** Internal transition shared by the risk integration and deterministic ledger tests. */
    void applyPreview(RiskPreviewResult proposal, DecisionResult decision, long asOfCloseTime) {
        if (proposal.action() == RiskPreviewResult.Action.PROPOSED_ENTRY && position == null) {
            signals++;
            pendingEntry = new PendingEntry(decision.entry() == DecisionResult.Entry.LONG
                    ? PositionState.Side.LONG : PositionState.Side.SHORT, proposal.quantity(),
                    proposal.initialStop(), proposal.riskBudgetUsdt(), asOfCloseTime);
        } else if (proposal.action() == RiskPreviewResult.Action.BLOCKED) {
            blocked++;
            if (decision.entry() != DecisionResult.Entry.WAIT) signals++;
        } else if (proposal.action() == RiskPreviewResult.Action.EXIT_SIGNAL && position != null) {
            pendingExit = true;
        } else if (proposal.action() == RiskPreviewResult.Action.ADJUST_STOP && position != null) {
            // This method runs at close; next begin() is the first candle where stop is effective.
            position = position.withStop(proposal.proposedStop());
        }
    }

    private void close(BigDecimal unadjusted, long openTime, String why) {
        var p = position;
        boolean buy = p.side() == PositionState.Side.SHORT;
        BigDecimal exit = slippage(unadjusted, p.side(), buy);
        BigDecimal gross = (p.side() == PositionState.Side.LONG ? exit.subtract(p.entry())
                : p.entry().subtract(exit)).multiply(p.qty(), MC);
        BigDecimal closingFee = commission(exit, p.qty());
        cash = cash.add(gross);
        dailyRealized = dailyRealized.add(gross);
        fee(closingFee);
        trades.add(new BacktestResult.Trade(p.side().name(), p.entryOpenTime(), openTime,
                p.qty(), p.entry(), exit, gross, gross.subtract(p.entryFee()).subtract(closingFee), why));
        position = null;
    }

    private BigDecimal mark(BigDecimal currentClose) {
        if (position == null) return cash;
        boolean buyExit = position.side() == PositionState.Side.SHORT;
        BigDecimal px = slippage(currentClose, position.side(), buyExit);
        BigDecimal gross = (buyExit ? position.entry().subtract(px)
                : px.subtract(position.entry())).multiply(position.qty(), MC);
        return cash.add(gross).subtract(commission(px, position.qty()));
    }

    /** Mark to market, including projected exit slippage and fee, without closing positions. */
    void markClose(BigDecimal close) {
        BigDecimal equity = mark(close);
        if (equity.compareTo(peak) > 0) peak = equity;
        if (peak.signum() > 0) {
            BigDecimal drawdown = peak.subtract(equity).max(BigDecimal.ZERO)
                    .multiply(HUNDRED, MC).divide(peak, MC);
            maxDrawdown = maxDrawdown.max(drawdown);
        }
    }

    BacktestResult.PortfolioResult result(BigDecimal lastPrice) {
        BigDecimal marked = mark(lastPrice);
        int winners = (int) trades.stream().filter(t -> t.netPnlUsdt().signum() > 0).count();
        int losers = (int) trades.stream().filter(t -> t.netPnlUsdt().signum() < 0).count();
        return new BacktestResult.PortfolioResult(engine, "SIMULATED", policy.capitalUsdt(),
                cash, marked, marked.subtract(policy.capitalUsdt()).multiply(HUNDRED, MC)
                        .divide(policy.capitalUsdt(), MC), maxDrawdown, feeTotal, trades.size(),
                winners, losers, blocked, signals,
                position == null ? "FLAT" : position.side().name(), List.copyOf(trades));
    }
}
