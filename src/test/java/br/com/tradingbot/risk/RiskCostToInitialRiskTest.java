package br.com.tradingbot.risk;

import br.com.tradingbot.analysis.brooks.PriceActionSnapshot;
import br.com.tradingbot.decision.DecisionResult;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RiskCostToInitialRiskTest {
    private static BigDecimal b(String n) { return new BigDecimal(n); }

    private static RiskPolicy policy(String fee, String slip, String limit) {
        return new RiskPolicy(b("300"), b("0.5"), b("3"), 2,
                b(fee), b(slip), b("1.0"), b("0.5"), limit == null ? null : b(limit));
    }

    @Test
    void comparesTheoreticalRoundTripCostToGrossStopDistanceWithoutQuantity() {
        // 0.15% de custo por lado; (100 + 99) * 0.0015 = 0.2985.
        // Risco bruto por unidade: 1.00. Custo / risco = 29.85%.
        assertFalse(policy("0.15", "0", "29.85")
                .costToInitialRiskTooHigh(b("100"), b("99")));
        assertTrue(policy("0.15", "0", "29.84")
                .costToInitialRiskTooHigh(b("100"), b("99")));
        assertFalse(policy("0.15", "0", null)
                .costToInitialRiskTooHigh(b("100"), b("99")));
    }

    @Test
    void sameThresholdBlocksCostlyLongAndShortForJavaAndLuna() {
        var expensive = policy("1.5", "0.2", "30");
        var longReq = RiskEngineTest.request(RiskEngineTest.decision(
                DecisionResult.Entry.LONG, DecisionResult.PositionAction.NONE, "JAVA"),
                null, "0", "0.001", "0.001");
        var longResult = new RiskEngine().preview(longReq, RiskEngineTest.context(
                PriceActionSnapshot.Trend.UP, true, "101", "102", "100",
                RiskEngineTest.TIME-100), expensive);
        assertEquals(RiskPreviewResult.Action.BLOCKED, longResult.action());
        assertEquals(RiskPreviewResult.Reason.COST_TO_RISK_TOO_HIGH, longResult.reason());
        assertEquals(0, longResult.initialStop().compareTo(b("94.0")));

        var shortReq = RiskEngineTest.request(RiskEngineTest.decision(
                DecisionResult.Entry.SHORT, DecisionResult.PositionAction.NONE, "LUNA"),
                null, "0", "0.001", "0.001");
        var shortResult = new RiskEngine().preview(shortReq, RiskEngineTest.context(
                PriceActionSnapshot.Trend.DOWN, true, "99", "102", "98",
                RiskEngineTest.TIME-100), expensive);
        assertEquals(RiskPreviewResult.Action.BLOCKED, shortResult.action());
        assertEquals(RiskPreviewResult.Reason.COST_TO_RISK_TOO_HIGH, shortResult.reason());
        assertEquals("LUNA", shortResult.engine());
    }

    @Test
    void legacyPolicyWithoutThresholdPreservesPreviouslyAllowedSignals() {
        var req = RiskEngineTest.request(RiskEngineTest.decision(
                DecisionResult.Entry.LONG, DecisionResult.PositionAction.NONE, "JAVA"),
                null, "0", "0.001", "0.001");
        var ctx = RiskEngineTest.context(PriceActionSnapshot.Trend.UP, true,
                "101", "102", "100", RiskEngineTest.TIME-100);
        var prior = new RiskEngine().preview(req, ctx, RiskEngineTest.policy());
        var legacy = policy("0.04", "0.02", null);
        var result = new RiskEngine().preview(req, ctx, legacy);
        assertEquals(RiskPreviewResult.Action.PROPOSED_ENTRY, prior.action());
        assertEquals(prior.action(), result.action());
        assertEquals(prior.quantity(), result.quantity());
    }

    @Test
    void validatesCostThresholdAndHandlesZeroPriceDistanceSafely() {
        assertThrows(IllegalArgumentException.class, () -> policy("0.05", "0.02", "0"));
        assertThrows(IllegalArgumentException.class, () -> policy("0.05", "0.02", "-0.1"));
        assertThrows(IllegalArgumentException.class, () -> policy("0.05", "0.02", "101"));
        assertTrue(policy("0.05", "0.02", "30")
                .costToInitialRiskTooHigh(b("100"), b("100")));
        assertFalse(policy("0.05", "0.02", "100")
                .costToInitialRiskTooHigh(b("100"), b("99")));
    }
}
