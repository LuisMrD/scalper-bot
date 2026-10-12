package br.com.tradingbot.simulation;

import br.com.tradingbot.decision.DecisionResult;
import br.com.tradingbot.risk.RiskPolicy;
import br.com.tradingbot.risk.RiskPreviewResult;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BacktestCostToRiskFillTest {
    private static BigDecimal b(String n) { return new BigDecimal(n); }

    private static RiskPolicy policy(String maxPct) {
        return new RiskPolicy(b("300"), b("1"), b("3"), 3,
                b("0.05"), b("0.02"), b("1"), b("0.5"),
                maxPct == null ? null : b(maxPct));
    }

    private static BacktestLedger ledger(String limit) {
        var ledger = new BacktestLedger("JAVA", policy(limit), BacktestLedgerTest.request());
        ledger.begin(BacktestLedgerTest.bar(0, "100", "101", "99", "100"));
        var entry = BacktestLedgerTest.enter(DecisionResult.Entry.LONG);
        // Forca apenas o preenchimento: um filtro na decisao pode ter aprovado um
        // preco anterior; o gap no candle seguinte muda o custo relativo.
        var proposal = new RiskPreviewResult("JAVA", "BTCUSDT", "1m",
                BacktestLedgerTest.T + 59999,
                RiskPreviewResult.Action.PROPOSED_ENTRY,
                RiskPreviewResult.Reason.PREVIEW_ONLY,
                b("100"), b("0.1"), b("10"), b("3"),
                b("99.9"), null, b("0.1"), false);
        ledger.applyPreview(proposal, entry, BacktestLedgerTest.T + 59999);
        return ledger;
    }

    @Test
    void nextOpenBlocksEntryIfCostDominatesTheStopDistance() {
        var ledger = ledger("30");
        ledger.begin(BacktestLedgerTest.bar(1, "100", "101", "99.95", "100"));
        var result = ledger.result(b("100"));
        assertEquals("FLAT", result.openPositionSide());
        assertEquals(1, result.entriesBlocked());
        assertEquals(1, result.entrySignals());
        assertEquals(0, result.tradesClosed());
    }

    @Test
    void disabledFilterMaintainsOriginalFillBehavior() {
        var ledger = ledger(null);
        ledger.begin(BacktestLedgerTest.bar(1, "100", "101", "99.95", "100"));
        var result = ledger.result(b("100"));
        assertEquals("LONG", result.openPositionSide());
        assertEquals(0, result.entriesBlocked());
    }

    @Test
    void fillRechecksWorseGapEvenWhenReferenceEntryWasAcceptable() {
        // sinal foi calculado em 100, com stop 99; custo/risk ~14%.
        // no proximo candle abre proximo do stop (99.1); risco ~0.1; bloqueia.
        var p = new RiskPolicy(b("300"), b("1"), b("3"), 3,
                b("0.05"), b("0.02"), b("1"), b("0.5"), b("30"));
        assertFalse(p.costToInitialRiskTooHigh(b("100"), b("99")));
        assertTrue(p.costToInitialRiskTooHigh(b("99.1"), b("99")));
    }
}
