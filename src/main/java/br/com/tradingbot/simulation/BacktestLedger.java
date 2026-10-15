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
    private String pendingExitReason;
    private long latestCandleCloseTime;
    private int blocked;
    private int signals;

    private record PendingEntry(PositionState.Side side, BigDecimal quantity,
            BigDecimal stop, BigDecimal budget, long signalCloseTime,
            BacktestResult.EntrySignalSnapshot snapshot, BigDecimal bollingerTarget) {}
    /**
     * One trade in progress. High/low are observed only while the trade is open.
     * If the stop is crossed intrabar, the unknown OHLC ordering is conservative:
     * no full-candle high/low excursion is credited after that stop.
     */
    private static final class Position {
        private final PositionState.Side side;
        private final BigDecimal qty, entry, initialStop, entryFee, entrySlippage, bollingerTarget;
        private final long signalCloseTime, entryOpenTime;
        private final BacktestResult.EntryExecutionDiagnostics entryDiagnostics;
        private BigDecimal stop, highestSeen, lowestSeen;
        private int durationCandles, stopAdjustments;
        private final ArrayList<BacktestResult.TradeCloseObservation> closeHistory = new ArrayList<>();
        private Long firstProfitableCloseTime, bestCloseTime, firstConfirmedReversalCloseTime;
        private BigDecimal bestNetClose;
        private int profitableCloseCandles;

        Position(PositionState.Side side, BigDecimal qty, BigDecimal entry,
                BigDecimal initialStop, BigDecimal entryFee, BigDecimal entrySlippage,
                long signalCloseTime, long entryOpenTime,
                BacktestResult.EntryExecutionDiagnostics entryDiagnostics, BigDecimal bollingerTarget) {
            this.side = side; this.qty = qty; this.entry = entry;
            this.initialStop = initialStop; this.stop = initialStop;
            this.entryFee = entryFee; this.entrySlippage = entrySlippage;
            this.signalCloseTime = signalCloseTime; this.entryOpenTime = entryOpenTime;
            this.entryDiagnostics = entryDiagnostics;
            this.bollingerTarget = bollingerTarget;
            this.highestSeen = entry; this.lowestSeen = entry;
        }
        PositionState.Side side() { return side; }
        BigDecimal qty() { return qty; }
        BigDecimal entry() { return entry; }
        BigDecimal initialStop() { return initialStop; }
        BigDecimal stop() { return stop; }
        BigDecimal entryFee() { return entryFee; }
        long signalCloseTime() { return signalCloseTime; }
        long entryOpenTime() { return entryOpenTime; }
        void withStop(BigDecimal next) {
            if (stop.compareTo(next) != 0) { stop = next; stopAdjustments++; }
        }
        void observed(BigDecimal high, BigDecimal low) {
            highestSeen = highestSeen.max(high);
            lowestSeen = lowestSeen.min(low);
        }
        void countCandle() { durationCandles++; }
        void observeClose(long closeTime, BigDecimal referenceClose, BigDecimal net,
                DecisionResult decision, RiskPreviewResult risk) {
            closeHistory.add(new BacktestResult.TradeCloseObservation(durationCandles, closeTime,
                    referenceClose, net, stop,
                    decision == null ? null : decision.reason().name(),
                    risk == null ? null : risk.reason().name()));
            if (net.signum() > 0) {
                profitableCloseCandles++;
                if (firstProfitableCloseTime == null) firstProfitableCloseTime = closeTime;
            }
            if (bestNetClose == null || net.compareTo(bestNetClose) > 0) {
                bestNetClose = net;
                bestCloseTime = closeTime;
            }
            if (firstConfirmedReversalCloseTime == null && decision != null
                    && decision.reason() == DecisionResult.Reason.CONFIRMED_REVERSAL)
                firstConfirmedReversalCloseTime = closeTime;
        }
        BigDecimal netAt(int candles) {
            return closeHistory.stream().filter(x -> x.durationCandles() == candles)
                    .map(BacktestResult.TradeCloseObservation::estimatedNetPnlUsdt)
                    .findFirst().orElse(null);
        }
        BacktestResult.TradeTimingDiagnostics timing(BigDecimal exitNet) {
            BigDecimal giveback = bestNetClose == null ? null
                    : bestNetClose.subtract(exitNet).max(BigDecimal.ZERO);
            return new BacktestResult.TradeTimingDiagnostics(firstProfitableCloseTime,
                    profitableCloseCandles, bestNetClose, bestCloseTime,
                    netAt(15), netAt(30), netAt(60), netAt(120), giveback,
                    firstConfirmedReversalCloseTime, List.copyOf(closeHistory));
        }
        BigDecimal initialRisk() { return entry.subtract(initialStop).abs().multiply(qty, MC); }
        BigDecimal mfe() {
            return (side == PositionState.Side.LONG
                    ? highestSeen.subtract(entry) : entry.subtract(lowestSeen))
                    .max(BigDecimal.ZERO).multiply(qty, MC);
        }
        BigDecimal mae() {
            return (side == PositionState.Side.LONG
                    ? entry.subtract(lowestSeen) : highestSeen.subtract(entry))
                    .max(BigDecimal.ZERO).multiply(qty, MC);
        }
        BigDecimal ratio(BigDecimal amount) {
            return initialRisk().signum() == 0 ? BigDecimal.ZERO
                    : amount.divide(initialRisk(), MC);
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
        latestCandleCloseTime = candle.closeTime();
        resetDay(candle.openTime());
        if (pendingExit && position != null)
            close(candle.open(), candle.openTime(), pendingExitReason);
        pendingExit = false;
        pendingExitReason = null;
        if (pendingEntry != null && position == null) {
            var proposal = pendingEntry;
            pendingEntry = null;
            boolean longSide = proposal.side() == PositionState.Side.LONG;
            BigDecimal fill = slippage(candle.open(), proposal.side(), longSide);
            BigDecimal target = proposal.bollingerTarget();
            // A gap may already pass the frozen middle SMA. In that case the
            // trade has no remaining positive cost-adjusted mean-reversion edge.
            BigDecimal rate = fraction(policy.feePerSidePct().add(policy.slippagePerSidePct()));
            boolean invalidTarget = target != null
                    && ((longSide ? target.subtract(fill) : fill.subtract(target))
                        .compareTo(fill.add(target).multiply(rate, MC)) <= 0);
            if (fill.signum() <= 0 || (longSide ? fill.compareTo(proposal.stop()) <= 0
                    : fill.compareTo(proposal.stop()) >= 0)
                    || invalidTarget || policy.costToInitialRiskTooHigh(fill, proposal.stop())) { blocked++; }
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
                        position = new Position(proposal.side(), qty, fill, proposal.stop(),
                                openingFee, fill.subtract(candle.open()).abs().multiply(qty, MC),
                                proposal.signalCloseTime(), candle.openTime(),
                                entryDiagnostics(proposal, fill, candle.openTime(), qty),
                                proposal.bollingerTarget());
                        fee(openingFee);
                    }
                }
            }
        }
        // Stop is assumed triggered whenever current candle range crosses it. In an OHLC candle
        // with ambiguous ordering, stop takes precedence and adverse gaps worsen the fill.
        if (position != null) {
            position.countCandle();
            boolean stopHit = position.side() == PositionState.Side.LONG
                    ? candle.low().compareTo(position.stop()) <= 0
                    : candle.high().compareTo(position.stop()) >= 0;
            if (stopHit) {
                boolean longSide = position.side() == PositionState.Side.LONG;
                BigDecimal executable = longSide ? candle.open().min(position.stop())
                        : candle.open().max(position.stop());
                // Conservative extrema: open and triggered stop only (no ambiguous full bar).
                position.observed(candle.open().max(executable), candle.open().min(executable));
                close(executable, candle.openTime(), "STOP");
            } else {
                // A frozen SMA target is an intrabar LIMIT take profit.
                // STOP always wins on a candle where both are touched.
                var target = position.bollingerTarget;
                boolean targetHit = target != null
                        && (position.side() == PositionState.Side.LONG
                            ? candle.high().compareTo(target) >= 0
                            : candle.low().compareTo(target) <= 0);
                if (targetHit) {
                    position.observed(candle.open().max(target), candle.open().min(target));
                    close(target, candle.openTime(), "BB_MIDDLE_TARGET");
                } else {
                    position.observed(candle.high(), candle.low());
                }
            }
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
                    proposal.initialStop(), proposal.riskBudgetUsdt(), asOfCloseTime, null, null);
        } else if (proposal.action() == RiskPreviewResult.Action.BLOCKED) {
            blocked++;
            if (decision.entry() != DecisionResult.Entry.WAIT) signals++;
        } else if (proposal.action() == RiskPreviewResult.Action.EXIT_SIGNAL && position != null) {
            pendingExit = true;
            pendingExitReason = "EXIT_SIGNAL";
        } else if (proposal.action() == RiskPreviewResult.Action.ADJUST_STOP && position != null) {
            // This method runs at close; next begin() is the first candle where stop is effective.
            position.withStop(proposal.proposedStop());
        }
    }

    /**
     * Attach the as-of signal snapshot only to a still-pending APPROVED entry.
     * Test helpers using applyPreview directly may leave snapshot null.
     */
    void attachPendingEntrySnapshot(BacktestResult.EntrySignalSnapshot snapshot) {
        if (pendingEntry == null || snapshot == null) return;
        if (pendingEntry.signalCloseTime() != snapshot.signal().candleCloseTime()
                || snapshot.signal().riskAction() != RiskPreviewResult.Action.PROPOSED_ENTRY
                || !snapshot.signal().entry().name().equals(pendingEntry.side().name()))
            throw new IllegalArgumentException("Sinal de entrada diferente da proposta pendente");
        pendingEntry = new PendingEntry(pendingEntry.side(), pendingEntry.quantity(),
                pendingEntry.stop(), pendingEntry.budget(), pendingEntry.signalCloseTime(),
                snapshot, pendingEntry.bollingerTarget());
    }

    /** Only an approved research Bollinger signal can carry the frozen SMA target. */
    void attachPendingBollingerTarget(long signalCloseTime, BigDecimal target) {
        if (target == null || target.signum() <= 0 || pendingEntry == null
                || pendingEntry.signalCloseTime() != signalCloseTime) {
            throw new IllegalArgumentException("No matching pending Bollinger entry target");
        }
        pendingEntry = new PendingEntry(pendingEntry.side(), pendingEntry.quantity(),
                pendingEntry.stop(), pendingEntry.budget(), pendingEntry.signalCloseTime(),
                pendingEntry.snapshot(), target);
    }

    private BacktestResult.EntryExecutionDiagnostics entryDiagnostics(
            PendingEntry proposal, BigDecimal fill, long fillOpenTime, BigDecimal quantity) {
        if (proposal.snapshot() == null) return null;
        BigDecimal stopDistance = fill.subtract(proposal.stop()).abs();
        BigDecimal rate = fraction(policy.feePerSidePct().add(policy.slippagePerSidePct()));
        BigDecimal roundTripCost = fill.add(proposal.stop()).multiply(rate, MC)
                .multiply(quantity, MC);
        BigDecimal rawRisk = stopDistance.multiply(quantity, MC);
        BigDecimal riskCostPct = rawRisk.signum() == 0 ? null
                : roundTripCost.multiply(HUNDRED, MC).divide(rawRisk, MC);
        BigDecimal distancePct = stopDistance.multiply(HUNDRED, MC).divide(fill, MC);
        BigDecimal gapPct = fill.subtract(proposal.snapshot().signalClosePrice())
                .multiply(HUNDRED, MC).divide(proposal.snapshot().signalClosePrice(), MC);
        return new BacktestResult.EntryExecutionDiagnostics(proposal.snapshot(), fillOpenTime,
                fill, proposal.stop(), distancePct, gapPct, roundTripCost, riskCostPct);
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
        BigDecimal net = gross.subtract(p.entryFee()).subtract(closingFee);
        BigDecimal feeCosts = p.entryFee().add(closingFee);
        // Entry/exit slippage is already embedded in the actual fill prices and gross PnL.
        BigDecimal exitSlippage = exit.subtract(unadjusted).abs().multiply(p.qty(), MC);
        trades.add(new BacktestResult.Trade(p.side().name(), p.entryOpenTime(), openTime,
                p.qty(), p.entry(), exit, gross, net, why,
                p.initialStop(), p.stop(), p.durationCandles,
                openTime - p.entryOpenTime(), p.initialRisk(), p.mfe(), p.mae(),
                p.ratio(p.mfe()), p.ratio(p.mae()), p.ratio(net),
                feeCosts, p.entrySlippage.add(exitSlippage), p.stopAdjustments,
                p.timing(net), p.entryDiagnostics));
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
        markClose(close, latestCandleCloseTime, null, null);
    }

    void markClose(BigDecimal close, long closeTime,
            DecisionResult decision, RiskPreviewResult riskPreview) {
        BigDecimal equity = mark(close);
        if (position != null) {
            // Projected NET liquidation PnL; cash already accounts for the entry
            // fee, so subtract it once more when isolating PnL of this trade.
            BigDecimal estimatedNet = equity.subtract(cash).subtract(position.entryFee());
            position.observeClose(closeTime, close, estimatedNet, decision, riskPreview);
            Integer limit = request.maxHoldingCandles();
            if (limit != null && position.durationCandles >= limit && !pendingExit) {
                pendingExit = true;
                pendingExitReason = "TIME_LIMIT";
            }
        }
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
        BacktestResult.OpenPositionDiagnostics active = null;
        if (position != null) {
            var p = position;
            active = new BacktestResult.OpenPositionDiagnostics(p.side().name(),
                    p.entryOpenTime(), p.qty(), p.entry(), p.initialStop(), p.stop(),
                    p.durationCandles, p.initialRisk(), p.mfe(), p.mae(),
                    p.ratio(p.mfe()), p.ratio(p.mae()),
                    marked.subtract(cash).subtract(p.entryFee()),
                    p.entryFee(), p.entrySlippage, p.stopAdjustments,
                    p.timing(marked.subtract(cash).subtract(p.entryFee())),
                    p.entryDiagnostics);
        }
        return new BacktestResult.PortfolioResult(engine, "SIMULATED", policy.capitalUsdt(),
                cash, marked, marked.subtract(policy.capitalUsdt()).multiply(HUNDRED, MC)
                        .divide(policy.capitalUsdt(), MC), maxDrawdown, feeTotal, trades.size(),
                winners, losers, blocked, signals,
                position == null ? "FLAT" : position.side().name(), List.copyOf(trades), active);
    }
}
