package br.com.tradingbot.risk;

import br.com.tradingbot.analysis.brooks.PriceActionSnapshot;
import br.com.tradingbot.analysis.context.MarketContextSnapshot;
import br.com.tradingbot.decision.DecisionResult;
import br.com.tradingbot.decision.PositionState.Side;
import br.com.tradingbot.risk.RiskPreviewResult.Action;
import br.com.tradingbot.risk.RiskPreviewResult.Reason;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Calculadora hipotetica, sem envio ou gerenciamento efetivo de ordens.
 * O fechamento vigente do candle e usado para uma decisao que so vale para o proximo candle.
 */
public final class RiskEngine {
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public RiskPreviewResult preview(RiskPreviewRequest request, MarketContextSnapshot context,
            RiskPolicy policy) {
        return preview(request, context, policy, null);
    }

    /** Historical BB research only: explicit stop from a previously CLOSED extreme candle. */
    public RiskPreviewResult preview(RiskPreviewRequest request, MarketContextSnapshot context,
            RiskPolicy policy, BigDecimal stopOverride) {
        if (stopOverride != null && (request.decision().setup()
                != DecisionResult.Setup.BOLLINGER_MEAN_REVERSION
                || request.position() != null || stopOverride.signum() <= 0))
            throw new IllegalArgumentException("Stop override is exclusively for flat BB research");
        Objects.requireNonNull(request); Objects.requireNonNull(context); Objects.requireNonNull(policy);
        DecisionResult decision = request.decision();
        if (!decision.symbol().equals(context.symbol())
                || !decision.entryInterval().equals(context.interval())
                || decision.evaluatedCandleCloseTime() != context.asOfCloseTime()
                || decision.requestedAsOfEpochMs() != context.requestedAsOfEpochMs())
            throw new IllegalArgumentException("Decisao e contexto nao correspondem ao mesmo snapshot");
        var position = request.position();
        if (position != null) {
            if (decision.entry() != DecisionResult.Entry.WAIT
                    || decision.positionAction() == DecisionResult.PositionAction.NONE
                    || position.openedAtCloseTime() >= context.asOfCloseTime())
                return outcome(decision, context, Action.BLOCKED, Reason.INVALID_DECISION_OR_POSITION,
                        null, null, null, null, null, null, false);
            return manage(request, context, policy);
        }
        if (decision.positionAction() != DecisionResult.PositionAction.NONE)
            return outcome(decision, context, Action.BLOCKED, Reason.INVALID_DECISION_OR_POSITION,
                    null, null, null, null, null, null, false);
        if (decision.entry() == DecisionResult.Entry.WAIT)
            return outcome(decision, context, Action.NO_ACTION, Reason.NO_ENTRY_SIGNAL,
                    null, null, null, null, null, null, false);
        if (!decision.reason().equals(DecisionResult.Reason.PULLBACK_CONFIRMED)
                && !decision.reason().equals(DecisionResult.Reason.BREAKOUT_RETEST_CONFIRMED)
                && !(decision.reason() == DecisionResult.Reason.RANGE_FALSE_BREAK_L2_CONFIRMED
                    && decision.setup() == DecisionResult.Setup.RANGE_FALSE_BREAK_L2
                    && decision.entry() == DecisionResult.Entry.SHORT)
                && !(decision.reason() == DecisionResult.Reason.BOLLINGER_REVERSAL_CONFIRMED
                    && decision.setup() == DecisionResult.Setup.BOLLINGER_MEAN_REVERSION
                    && decision.entry() != DecisionResult.Entry.WAIT))
            return outcome(decision, context, Action.BLOCKED, Reason.INVALID_DECISION_OR_POSITION,
                    null, null, null, null, null, null, false);
        return entry(request, context, policy, stopOverride);
    }

    private RiskPreviewResult entry(RiskPreviewRequest request, MarketContextSnapshot context,
            RiskPolicy policy, BigDecimal stopOverride) {
        var decision = request.decision();
        boolean longSide = decision.entry() == DecisionResult.Entry.LONG;
        BigDecimal entry = context.recentCandles().getLast().close();
        BigDecimal stop;
        if (stopOverride != null) {
            stop = stopOverride;
            // Strict contract: the short-stop buffer is one Binance tick beyond
            // the completed outside/confirmation extreme (rounded externally).
            if (stop.divide(request.priceTick(), 0, RoundingMode.UNNECESSARY)
                    .multiply(request.priceTick()).compareTo(stop) != 0)
                throw new IllegalArgumentException("Stop must be on the price tick grid");
        } else {
            BigDecimal atrBuffer = context.marketLevels().atr().multiply(policy.structuralAtrBuffer(), MC);
            List<PriceActionSnapshot.ConfirmedPivot> pivots = context.priceAction().confirmedPivots();
            var type = longSide ? PriceActionSnapshot.PivotType.LOW : PriceActionSnapshot.PivotType.HIGH;
            var confirmed = pivots.stream().filter(p -> p.type() == type
                    && p.confirmedAtCloseTime() <= context.asOfCloseTime()
                    && (longSide ? p.price().compareTo(entry) < 0 : p.price().compareTo(entry) > 0))
                    .max(Comparator.comparingLong(PriceActionSnapshot.ConfirmedPivot::confirmedAtCloseTime));
            if (confirmed.isEmpty())
                return outcome(decision, context, Action.BLOCKED, Reason.NO_CONFIRMED_STRUCTURAL_STOP,
                        entry, null, null, null, null, null, false);
            BigDecimal rawStop = longSide ? confirmed.get().price().subtract(atrBuffer)
                    : confirmed.get().price().add(atrBuffer);
            if (rawStop.signum() <= 0)
                return outcome(decision, context, Action.BLOCKED, Reason.NO_CONFIRMED_STRUCTURAL_STOP,
                        entry, null, null, null, null, null, false);
            stop = roundToStep(rawStop, request.priceTick(),
                    longSide ? RoundingMode.FLOOR : RoundingMode.CEILING);
        }
        if (stop.signum() <= 0 || (longSide ? stop.compareTo(entry) >= 0 : stop.compareTo(entry) <= 0))
            return outcome(decision, context, Action.BLOCKED, Reason.NO_CONFIRMED_STRUCTURAL_STOP,
                    entry, null, null, null, null, null, false);

        // Bloqueia entradas cujo custo hipotetico de ida e volta consome
        // parcela excessiva do risco bruto ate o stop estrutural.
        // Nao exige quantidade: a relacao e independente do tamanho da posicao.
        if (policy.costToInitialRiskTooHigh(entry, stop))
            return outcome(decision, context, Action.BLOCKED, Reason.COST_TO_RISK_TOO_HIGH,
                    entry, null, null, null, stop, null, false);

        BigDecimal dailyCap = pct(policy.capitalUsdt(), policy.dailyStopPct());
        // RealizedDailyPnl pode ser positivo; so perdas reduzem a cota diaria. Nenhum reinicio diario e presumido.
        BigDecimal lossSoFar = request.realizedDailyPnlUsdt().signum() < 0
                ? request.realizedDailyPnlUsdt().negate() : BigDecimal.ZERO;
        BigDecimal remaining = dailyCap.subtract(lossSoFar);
        if (remaining.signum() <= 0)
            return outcome(decision, context, Action.BLOCKED, Reason.DAILY_LOSS_LIMIT,
                    entry, null, null, null, stop, null, false);
        BigDecimal equityBase = policy.capitalUsdt().min(request.equityUsdt());
        BigDecimal riskBudget = pct(equityBase, policy.riskPerTradePct()).min(remaining);
        if (riskBudget.signum() <= 0)
            return outcome(decision, context, Action.BLOCKED, Reason.NO_RISK_CAPACITY,
                    entry, null, null, riskBudget, stop, null, false);
        BigDecimal unitRisk = entry.subtract(stop).abs().add(roundTripCosts(entry, stop, policy));
        if (unitRisk.signum() <= 0) throw new IllegalArgumentException("unitRisk invalido");
        BigDecimal riskQty = riskBudget.divide(unitRisk, MC);
        BigDecimal marginQty = request.equityUsdt()
                .multiply(BigDecimal.valueOf(policy.leverage()), MC).divide(entry, MC);
        BigDecimal qty = roundToStep(riskQty.min(marginQty), request.quantityStep(), RoundingMode.FLOOR);
        BigDecimal notional = qty.multiply(entry);
        if (qty.signum() == 0 || qty.compareTo(request.minimumQuantity()) < 0
                || notional.compareTo(request.minimumNotionalUsdt()) < 0)
            return outcome(decision, context, Action.BLOCKED, Reason.BINANCE_FILTERS_NOT_MET,
                    entry, qty, notional, riskBudget, stop, null, false);
        BigDecimal estimated = qty.multiply(unitRisk, MC);
        return outcome(decision, context, Action.PROPOSED_ENTRY, Reason.PREVIEW_ONLY,
                entry, qty, notional, riskBudget, stop, estimated, false);
    }

    private RiskPreviewResult manage(RiskPreviewRequest request, MarketContextSnapshot context,
            RiskPolicy policy) {
        var decision = request.decision();
        SimulatedPosition p = request.position();
        var last = context.recentCandles().getLast();
        boolean longSide = p.side() == Side.LONG;
        if (longSide ? last.low().compareTo(p.currentStop()) <= 0
                : last.high().compareTo(p.currentStop()) >= 0) {
            // Conservador: protecao anterior prevalece sobre ajustes decididos no fechamento atual.
            return outcome(decision, context, Action.STOP_TOUCHED, Reason.STOP_TOUCHED,
                    p.entryPrice(), p.quantity(), p.entryPrice().multiply(p.quantity()),
                    null, p.initialStop(), p.currentStop(), false);
        }
        if (decision.positionAction() == DecisionResult.PositionAction.EXIT)
            return outcome(decision, context, Action.EXIT_SIGNAL, Reason.CONFIRMED_REVERSAL,
                    p.entryPrice(), p.quantity(), p.entryPrice().multiply(p.quantity()),
                    null, p.initialStop(), p.currentStop(), false);

        BigDecimal price = last.close();
        BigDecimal r = p.entryPrice().subtract(p.initialStop()).abs();
        BigDecimal move = longSide ? price.subtract(p.entryPrice()) : p.entryPrice().subtract(price);
        BigDecimal proposed = p.currentStop();
        if (move.compareTo(r.multiply(policy.breakEvenTriggerR(), MC)) >= 0) {
            // Break-even considera taxas/slippage hipoteticas de ida e volta; nunca e BE garantido.
            BigDecimal unitBuffer = roundTripCosts(p.entryPrice(), p.entryPrice(), policy);
            BigDecimal be = longSide ? p.entryPrice().add(unitBuffer) : p.entryPrice().subtract(unitBuffer);
            if (be.signum() > 0) {
                be = roundToStep(be, request.priceTick(), longSide ? RoundingMode.CEILING : RoundingMode.FLOOR);
                proposed = ratchet(proposed, be, longSide);
            }
            // So utilizar pivots confirmados APOS abertura; nunca recuar o stop.
            var pivotType = longSide ? PriceActionSnapshot.PivotType.LOW : PriceActionSnapshot.PivotType.HIGH;
            BigDecimal buffer = context.marketLevels().atr().multiply(policy.structuralAtrBuffer(), MC);
            for (var pivot : context.priceAction().confirmedPivots()) {
                if (pivot.type() != pivotType || pivot.confirmedAtCloseTime() <= p.openedAtCloseTime()
                        || pivot.confirmedAtCloseTime() > context.asOfCloseTime()) continue;
                BigDecimal structural = longSide ? pivot.price().subtract(buffer) : pivot.price().add(buffer);
                if (structural.signum() <= 0) continue;
                structural = roundToStep(structural, request.priceTick(),
                        longSide ? RoundingMode.FLOOR : RoundingMode.CEILING);
                proposed = ratchet(proposed, structural, longSide);
            }
        }
        // Stop calculado no fechamento so tera efeito no candle SEGUINTE.
        if (longSide ? proposed.compareTo(price) >= 0 : proposed.compareTo(price) <= 0)
            proposed = p.currentStop();
        boolean changed = proposed.compareTo(p.currentStop()) != 0;
        return outcome(decision, context, changed ? Action.ADJUST_STOP : Action.MAINTAIN_STOP,
                changed ? Reason.BREAK_EVEN_OR_TRAILING : Reason.POSITION_HOLD,
                p.entryPrice(), p.quantity(), p.entryPrice().multiply(p.quantity()),
                null, p.initialStop(), proposed, changed);
    }

    private static BigDecimal ratchet(BigDecimal previous, BigDecimal suggested, boolean longSide) {
        return longSide ? previous.max(suggested) : previous.min(suggested);
    }
    private static BigDecimal pct(BigDecimal amount, BigDecimal percent) {
        return amount.multiply(percent, MC).divide(HUNDRED, MC);
    }
    private static BigDecimal roundTripCosts(BigDecimal entry, BigDecimal exit, RiskPolicy p) {
        BigDecimal frac = p.feePerSidePct().add(p.slippagePerSidePct()).divide(HUNDRED, MC);
        return entry.add(exit).multiply(frac, MC);
    }
    private static BigDecimal roundToStep(BigDecimal value, BigDecimal step, RoundingMode mode) {
        return value.divide(step, 0, mode).multiply(step);
    }
    private static RiskPreviewResult outcome(DecisionResult decision, MarketContextSnapshot ctx,
            Action action, Reason reason, BigDecimal price, BigDecimal qty, BigDecimal notional,
            BigDecimal budget, BigDecimal initialStop, BigDecimal currentStopOrLoss, boolean effectiveNextBar) {
        BigDecimal proposedStop = switch(action) {
            case STOP_TOUCHED, EXIT_SIGNAL, MAINTAIN_STOP, ADJUST_STOP -> currentStopOrLoss;
            default -> null;
        };
        BigDecimal estimatedLoss = action == Action.PROPOSED_ENTRY ? currentStopOrLoss : null;
        return new RiskPreviewResult(decision.engine(), decision.symbol(), decision.entryInterval(),
                ctx.asOfCloseTime(), action, reason, price, qty, notional, budget, initialStop,
                proposedStop, estimatedLoss, effectiveNextBar);
    }
}
