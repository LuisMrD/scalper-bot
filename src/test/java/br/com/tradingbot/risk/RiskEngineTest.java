package br.com.tradingbot.risk;

import br.com.tradingbot.analysis.brooks.PriceActionSnapshot;
import br.com.tradingbot.analysis.brooks.PriceActionSnapshot.ConfirmedPivot;
import br.com.tradingbot.analysis.context.MarketContextSnapshot;
import br.com.tradingbot.analysis.levels.MarketLevelsSnapshot;
import br.com.tradingbot.analysis.volume.VolumeSnapshot;
import br.com.tradingbot.decision.DecisionResult;
import br.com.tradingbot.decision.PositionState.Side;
import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RiskEngineTest {
    static final long TIME = 1791800000000L;
    static BigDecimal bd(String v) { return new BigDecimal(v); }
    static RiskPolicy policy() {
        return new RiskPolicy(bd("300"), bd("0.5"), bd("3"), 2,
                bd("0.04"), bd("0.02"), bd("1.0"), bd("0.5"));
    }
    static DecisionResult decision(DecisionResult.Entry entry, DecisionResult.PositionAction action,
                                   String engine) {
        DecisionResult.Setup setup = entry == DecisionResult.Entry.WAIT
                ? DecisionResult.Setup.NONE : DecisionResult.Setup.PULLBACK;
        DecisionResult.Reason reason = entry == DecisionResult.Entry.WAIT
                ? DecisionResult.Reason.POSITION_HOLD : DecisionResult.Reason.PULLBACK_CONFIRMED;
        return new DecisionResult(engine, "BTCUSDT", "1m", TIME, TIME,
                entry, action, setup, reason);
    }
    static MarketContextSnapshot context(PriceActionSnapshot.Trend trend, boolean pivots, String close,
                                         String high, String low, long pivotConfirmed) {
        long openTime = TIME - 59999;
        BigDecimal o = bd("100").max(bd(low)).min(bd(high));
        var candle = new MarketCandle("BTCUSDT", CandleInterval.M1, openTime, TIME,
                o, bd(high), bd(low), bd(close), bd("10"), bd("1000"),
                10, bd("5"), bd("500"), true);
        var ps = pivots ? List.of(
                new ConfirmedPivot(PriceActionSnapshot.PivotType.LOW, openTime - 180000,
                        pivotConfirmed, bd("95")),
                new ConfirmedPivot(PriceActionSnapshot.PivotType.HIGH, openTime - 120000,
                        pivotConfirmed, bd("108"))) : List.<ConfirmedPivot>of();
        var pa = new PriceActionSnapshot("BTCUSDT", "1m", TIME, 30, trend,
                List.of(), ps, PriceActionSnapshot.Breakout.none(), false);
        var vol = new VolumeSnapshot("BTCUSDT", "1m", TIME, 30, 20, List.of());
        var levels = new MarketLevelsSnapshot("BTCUSDT", "1m", TIME, 30,
                bd(close), bd("2"), bd("1"), List.of(), null);
        return new MarketContextSnapshot("BTCUSDT", "1m", TIME, TIME, 30,
                List.of(candle), pa, vol, levels);
    }
    static RiskPreviewRequest request(DecisionResult decision, SimulatedPosition position,
                                       String dayPnl, String minQty, String step) {
        return new RiskPreviewRequest(decision, bd("300"), bd(dayPnl), bd("0.1"),
                bd(step), bd(minQty), bd("5"), 200, position);
    }

    @Test void waitDoesNotCreatePosition() {
        var req = request(decision(DecisionResult.Entry.WAIT, DecisionResult.PositionAction.NONE, "JAVA"),
                null, "0", "0.001", "0.001");
        assertEquals(RiskPreviewResult.Action.NO_ACTION,
                new RiskEngine().preview(req, context(PriceActionSnapshot.Trend.UP, true, "101", "102", "100", TIME-100), policy()).action());
    }
    @Test void longProposalHasConservativeStopAndLimitedRisk() {
        var req = request(decision(DecisionResult.Entry.LONG, DecisionResult.PositionAction.NONE, "JAVA"), null,
                "0", "0.001", "0.001");
        var out = new RiskEngine().preview(req, context(PriceActionSnapshot.Trend.UP, true,
                "101", "102", "100", TIME-100), policy());
        assertEquals(RiskPreviewResult.Action.PROPOSED_ENTRY, out.action());
        assertEquals(bd("94.0"), out.initialStop());
        assertTrue(out.estimatedLossAtStopUsdt().compareTo(out.riskBudgetUsdt()) <= 0);
        assertEquals(0, out.quantity().remainder(bd("0.001")).signum());
    }
    @Test void shortProposalHasStopAboveEntry() {
        var req = request(decision(DecisionResult.Entry.SHORT, DecisionResult.PositionAction.NONE, "LUNA"), null,
                "0", "0.001", "0.001");
        var out = new RiskEngine().preview(req, context(PriceActionSnapshot.Trend.DOWN, true,
                "99", "102", "98", TIME-100), policy());
        assertEquals(RiskPreviewResult.Action.PROPOSED_ENTRY, out.action());
        assertEquals(0, out.initialStop().compareTo(bd("109.0")));
        assertEquals("LUNA", out.engine());
    }
    @Test void missingPivotBlocksEntry() {
        var req = request(decision(DecisionResult.Entry.LONG, DecisionResult.PositionAction.NONE, "JAVA"), null,
                "0", "0.001", "0.001");
        assertEquals(RiskPreviewResult.Reason.NO_CONFIRMED_STRUCTURAL_STOP,
                new RiskEngine().preview(req, context(PriceActionSnapshot.Trend.UP, false,
                        "101", "102", "100", TIME-100), policy()).reason());
    }
    @Test void blocksAtDailyLossLimit() {
        var req = request(decision(DecisionResult.Entry.LONG, DecisionResult.PositionAction.NONE, "JAVA"), null,
                "-9", "0.001", "0.001");
        assertEquals(RiskPreviewResult.Reason.DAILY_LOSS_LIMIT,
                new RiskEngine().preview(req, context(PriceActionSnapshot.Trend.UP, true,
                        "101", "102", "100", TIME-100), policy()).reason());
    }
    @Test void remainingDailyBudgetCapsRisk() {
        var req = request(decision(DecisionResult.Entry.LONG, DecisionResult.PositionAction.NONE, "JAVA"), null,
                "-8.75", "0.001", "0.001");
        var out = new RiskEngine().preview(req, context(PriceActionSnapshot.Trend.UP, true,
                "101", "102", "100", TIME-100), policy());
        assertEquals(0, out.riskBudgetUsdt().compareTo(bd("0.25")));
    }
    @Test void smallBudgetFailsBinanceLotFiltersRatherThanRoundingUp() {
        var req = request(decision(DecisionResult.Entry.LONG, DecisionResult.PositionAction.NONE, "JAVA"), null,
                "0", "1", "1");
        assertEquals(RiskPreviewResult.Reason.BINANCE_FILTERS_NOT_MET,
                new RiskEngine().preview(req, context(PriceActionSnapshot.Trend.UP, true,
                        "101", "102", "100", TIME-100), policy()).reason());
    }
    @Test void mismatchedDecisionTimeIsRejected() {
        var original = decision(DecisionResult.Entry.LONG, DecisionResult.PositionAction.NONE, "JAVA");
        var changed = new DecisionResult("JAVA", original.symbol(), "1m", TIME+1, TIME,
                original.entry(), original.positionAction(), original.setup(), original.reason());
        var req = request(changed, null, "0", "0.001", "0.001");
        assertThrows(IllegalArgumentException.class, () -> new RiskEngine().preview(req,
                context(PriceActionSnapshot.Trend.UP, true, "101", "102", "100", TIME-100), policy()));
    }
    @Test void liveStopTakesPriorityOverExitSignal() {
        var position = new SimulatedPosition(Side.LONG, bd("100"), bd("95"), bd("99"), bd("0.1"), TIME-300000);
        var req = request(decision(DecisionResult.Entry.WAIT, DecisionResult.PositionAction.EXIT, "JAVA"),
                position, "0", "0.001", "0.001");
        assertEquals(RiskPreviewResult.Action.STOP_TOUCHED,
                new RiskEngine().preview(req, context(PriceActionSnapshot.Trend.DOWN, true,
                        "101", "102", "98", TIME-100), policy()).action());
    }
    @Test void signalExitIsOnlyPreviewNotOrder() {
        var position = new SimulatedPosition(Side.LONG, bd("100"), bd("95"), bd("96"), bd("0.1"), TIME-300000);
        var req = request(decision(DecisionResult.Entry.WAIT, DecisionResult.PositionAction.EXIT, "LUNA"),
                position, "0", "0.001", "0.001");
        assertEquals(RiskPreviewResult.Action.EXIT_SIGNAL,
                new RiskEngine().preview(req, context(PriceActionSnapshot.Trend.DOWN, true,
                        "101", "102", "98", TIME-100), policy()).action());
    }
    @Test void breakEvenAndTrailingForLongOnlyImproveStopOnNextCandle() {
        var position = new SimulatedPosition(Side.LONG, bd("100"), bd("95"), bd("95"), bd("0.1"), TIME-300000);
        var req = request(decision(DecisionResult.Entry.WAIT, DecisionResult.PositionAction.HOLD, "JAVA"),
                position, "0", "0.001", "0.001");
        var out = new RiskEngine().preview(req, context(PriceActionSnapshot.Trend.UP, true,
                "106", "107", "105", TIME-100), policy());
        assertEquals(RiskPreviewResult.Action.ADJUST_STOP, out.action());
        assertEquals(0, out.proposedStop().compareTo(bd("100.2")));
        assertTrue(out.proposedStopEffectiveNextCandle());
    }
    @Test void breakEvenForShortImprovesStop() {
        var position = new SimulatedPosition(Side.SHORT, bd("100"), bd("110"), bd("110"), bd("0.1"), TIME-300000);
        var req = request(decision(DecisionResult.Entry.WAIT, DecisionResult.PositionAction.HOLD, "JAVA"),
                position, "0", "0.001", "0.001");
        var out = new RiskEngine().preview(req, context(PriceActionSnapshot.Trend.DOWN, true,
                "89", "90", "88", TIME-100), policy());
        assertEquals(RiskPreviewResult.Action.ADJUST_STOP, out.action());
        assertTrue(out.proposedStop().compareTo(bd("100")) < 0);
    }
    @Test void noStopMoveBeforeTrigger() {
        var position = new SimulatedPosition(Side.LONG, bd("100"), bd("95"), bd("95"), bd("0.1"), TIME-300000);
        var req = request(decision(DecisionResult.Entry.WAIT, DecisionResult.PositionAction.HOLD, "JAVA"),
                position, "0", "0.001", "0.001");
        var out = new RiskEngine().preview(req, context(PriceActionSnapshot.Trend.UP, true,
                "103", "104", "102", TIME-100), policy());
        assertEquals(RiskPreviewResult.Action.MAINTAIN_STOP, out.action());
    }
    @Test void neverWidensExistingStop() {
        var position = new SimulatedPosition(Side.LONG, bd("100"), bd("95"), bd("102"), bd("0.1"), TIME-300000);
        var req = request(decision(DecisionResult.Entry.WAIT, DecisionResult.PositionAction.HOLD, "JAVA"),
                position, "0", "0.001", "0.001");
        var out = new RiskEngine().preview(req, context(PriceActionSnapshot.Trend.UP, true,
                "106", "107", "105", TIME-100), policy());
        assertEquals(RiskPreviewResult.Action.MAINTAIN_STOP, out.action());
        assertEquals(0, out.proposedStop().compareTo(bd("102")));
    }
    @Test void invalidRiskConfigurationAndPositionRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RiskPolicy(
                bd("300"), bd("0"), bd("3"), 2, bd("0"), bd("0"), bd("1"), bd("0.5")));
        assertThrows(IllegalArgumentException.class, () -> new SimulatedPosition(
                Side.LONG, bd("100"), bd("95"), bd("94"), bd("1"), 0));
    }
    @Test void sameRiskPolicyForJavaAndLuna() {
        var ctx = context(PriceActionSnapshot.Trend.UP, true, "101", "102", "100", TIME-100);
        var javaResult = new RiskEngine().preview(request(decision(DecisionResult.Entry.LONG,
                DecisionResult.PositionAction.NONE, "JAVA"), null, "0", "0.001", "0.001"), ctx, policy());
        var lunaResult = new RiskEngine().preview(request(decision(DecisionResult.Entry.LONG,
                DecisionResult.PositionAction.NONE, "LUNA"), null, "0", "0.001", "0.001"), ctx, policy());
        assertEquals(javaResult.quantity(), lunaResult.quantity());
        assertEquals(javaResult.initialStop(), lunaResult.initialStop());
        assertEquals(javaResult.estimatedLossAtStopUsdt(), lunaResult.estimatedLossAtStopUsdt());
    }
}
