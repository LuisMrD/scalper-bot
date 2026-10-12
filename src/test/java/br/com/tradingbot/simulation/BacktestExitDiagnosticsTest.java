package br.com.tradingbot.simulation;

import br.com.tradingbot.decision.DecisionResult;
import br.com.tradingbot.risk.RiskPreviewResult;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BacktestExitDiagnosticsTest {
    private static BigDecimal b(String n) { return new BigDecimal(n); }

    @Test
    void longStopFirstDoesNotClaimHighThatMightOccurAfterStop() {
        var ledger = BacktestLedgerTest.openedLong("0", "0");
        // The existing entry bar achieved high 101, low 95, then the next bar
        // touches 90. Its high 150 is NOT known to have occurred before stop.
        ledger.begin(BacktestLedgerTest.bar(2, "100", "150", "88", "99"));
        var t = ledger.result(b("99")).trades().getFirst();
        assertEquals("STOP", t.exitReason());
        assertEquals(0, t.initialStop().compareTo(b("90")));
        assertEquals(0, t.finalStop().compareTo(b("90")));
        assertEquals(2, t.durationCandles());
        assertEquals(60000L, t.durationMillis());
        assertEquals(0, t.initialRiskUsdt().compareTo(b("1")));
        assertEquals(0, t.maxFavorableExcursionUsdt().compareTo(b("0.1")));
        assertEquals(0, t.maxAdverseExcursionUsdt().compareTo(b("1")));
        assertEquals(0, t.mfeR().compareTo(b("0.1")));
        assertEquals(0, t.maeR().compareTo(b("1")));
        assertEquals(0, t.netRMultiple().compareTo(b("-1")));
        assertEquals(0, t.feesUsdt().signum());
        assertEquals(0, t.slippageUsdt().signum());
        assertEquals(0, t.stopAdjustments());
    }

    @Test
    void shortExitAtNextOpenExcludesFutureIntrabarExcursion() {
        var ledger = new BacktestLedger("JAVA", BacktestLedgerTest.policy("0","0"),
                BacktestLedgerTest.request());
        ledger.begin(BacktestLedgerTest.bar(0,"100","101","99","100"));
        ledger.applyPreview(BacktestLedgerTest.preview(DecisionResult.Entry.SHORT,"110"),
                BacktestLedgerTest.enter(DecisionResult.Entry.SHORT),
                BacktestLedgerTest.T + 59999);
        ledger.begin(BacktestLedgerTest.bar(1,"100","105","98","100"));
        var exit = new RiskPreviewResult("JAVA","BTCUSDT","1m",
                BacktestLedgerTest.T+119999,
                RiskPreviewResult.Action.EXIT_SIGNAL,
                RiskPreviewResult.Reason.CONFIRMED_REVERSAL,
                b("100"), b("0.1"), b("10"), null,
                b("110"), b("110"), null, false);
        ledger.applyPreview(exit,BacktestLedgerTest.hold(),BacktestLedgerTest.T+119999);
        // The next candle moves to 50 AFTER the exit open at 98: must not count.
        ledger.begin(BacktestLedgerTest.bar(2,"98","140","50","80"));
        var trade = ledger.result(b("80")).trades().getFirst();
        assertEquals("EXIT_SIGNAL", trade.exitReason());
        assertEquals(1, trade.durationCandles());
        assertEquals(0, trade.initialRiskUsdt().compareTo(b("1")));
        assertEquals(0, trade.maxFavorableExcursionUsdt().compareTo(b("0.2")));
        assertEquals(0, trade.maxAdverseExcursionUsdt().compareTo(b("0.5")));
        assertEquals(0, trade.netRMultiple().compareTo(b("0.2")));
    }

    @Test
    void breakEvenRatchetIsVisibleInFinalStopAndAdjustmentCount() {
        var ledger = BacktestLedgerTest.openedLong("0", "0");
        var adjustment = new RiskPreviewResult("JAVA", "BTCUSDT", "1m",
                BacktestLedgerTest.T+119999,
                RiskPreviewResult.Action.ADJUST_STOP,
                RiskPreviewResult.Reason.BREAK_EVEN_OR_TRAILING,
                b("100"), b("0.1"), b("10"), null, b("90"),
                b("100.5"), null, true);
        ledger.applyPreview(adjustment, BacktestLedgerTest.hold(), BacktestLedgerTest.T+119999);
        ledger.begin(BacktestLedgerTest.bar(2,"102","103","100","101"));
        var t = ledger.result(b("101")).trades().getFirst();
        assertEquals(0, t.initialStop().compareTo(b("90")));
        assertEquals(0, t.finalStop().compareTo(b("100.5")));
        assertEquals(1, t.stopAdjustments());
        assertEquals(0, t.netRMultiple().compareTo(b("0.05")));
    }

    @Test
    void feeAndSlippageAreSeparatelyReportedAndNotDeductedTwice() {
        var ledger = BacktestLedgerTest.openedLong("0.05", "0.02");
        ledger.begin(BacktestLedgerTest.bar(2, "102","103","88","100"));
        var t = ledger.result(b("100")).trades().getFirst();
        assertTrue(t.feesUsdt().signum() > 0);
        assertTrue(t.slippageUsdt().signum() > 0);
        assertEquals(0, t.grossPnlUsdt().subtract(t.feesUsdt())
                .compareTo(t.netPnlUsdt()));
        assertTrue(t.netPnlUsdt().signum() < 0);
        assertEquals(0, t.netPnlUsdt().divide(t.initialRiskUsdt(),
                java.math.MathContext.DECIMAL64).compareTo(t.netRMultiple()));
    }

    @Test
    void openPositionReportsExcursionsWithoutClaimingClosedTrade() {
        var ledger = BacktestLedgerTest.openedLong("0.05", "0.02");
        ledger.markClose(b("105"));
        var result = ledger.result(b("105"));
        assertEquals(0, result.tradesClosed());
        assertEquals("LONG", result.openPositionSide());
        assertNotNull(result.openPositionDiagnostics());
        var open = result.openPositionDiagnostics();
        assertEquals(1, open.durationCandles());
        assertTrue(open.maxFavorableExcursionUsdt().signum() > 0);
        assertTrue(open.maxAdverseExcursionUsdt().signum() > 0);
        assertEquals(0, open.initialStop().compareTo(b("90")));
        assertEquals(0, open.currentStop().compareTo(b("90")));
    }

    @Test
    void sameCandleEntryAndStopNeverCreditsWholeHighLow() {
        var ledger = new BacktestLedger("JAVA", BacktestLedgerTest.policy("0","0"),
                BacktestLedgerTest.request());
        ledger.begin(BacktestLedgerTest.bar(0, "100","102","98","100"));
        ledger.applyPreview(BacktestLedgerTest.preview(DecisionResult.Entry.LONG, "90"),
                BacktestLedgerTest.enter(DecisionResult.Entry.LONG),
                BacktestLedgerTest.T+59999);
        ledger.begin(BacktestLedgerTest.bar(1, "100","150","85","120"));
        var t = ledger.result(b("120")).trades().getFirst();
        assertEquals(1, t.durationCandles());
        assertEquals(0, t.durationMillis());
        assertEquals(0, t.maxFavorableExcursionUsdt().signum());
        assertEquals(0, t.maeR().compareTo(b("1")));
    }
}
