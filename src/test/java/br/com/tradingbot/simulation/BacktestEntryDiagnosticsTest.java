package br.com.tradingbot.simulation;

import br.com.tradingbot.analysis.brooks.PriceActionSnapshot;
import br.com.tradingbot.analysis.volume.VolumeSnapshot;
import br.com.tradingbot.decision.DecisionResult;
import br.com.tradingbot.risk.RiskPreviewResult;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BacktestEntryDiagnosticsTest {
    private static final long T = BacktestLedgerTest.T;
    private static BigDecimal b(String value) { return new BigDecimal(value); }

    private static BacktestResult.EntrySignalSnapshot snapshot(long at, DecisionResult.Entry side) {
        var trace = new BacktestResult.DecisionTrace(
                "JAVA", "FLAT", at - 59999, at, side,
                DecisionResult.PositionAction.NONE, DecisionResult.Setup.PULLBACK,
                DecisionResult.Reason.PULLBACK_CONFIRMED, PriceActionSnapshot.Trend.UP,
                List.of(new BacktestResult.TimeframeTrend("1m", PriceActionSnapshot.Trend.UP, at),
                        new BacktestResult.TimeframeTrend("5m", PriceActionSnapshot.Trend.RANGE, at-60000)),
                VolumeSnapshot.VolumeLevel.HIGH, VolumeSnapshot.TakerBalance.BUY_DOMINANT,
                b("1.9"), b("1.4"), VolumeSnapshot.EffortResult.NONE,
                RiskPreviewResult.Action.PROPOSED_ENTRY, RiskPreviewResult.Reason.PREVIEW_ONLY);
        return new BacktestResult.EntrySignalSnapshot(trace,
                new PriceActionSnapshot.BarPattern(at - 59999, PriceActionSnapshot.Direction.BULL,
                        true, false, false, false, false, false),
                PriceActionSnapshot.BreakoutState.NONE, true, 6,
                b("3.5"), b("100"), b("0.7"), 1);
    }

    private static BacktestLedger entryOnNextOpen(String high, String low, String close) {
        var ledger = new BacktestLedger("JAVA", BacktestLedgerTest.policy("0.05","0.02"),
                BacktestLedgerTest.request());
        ledger.begin(BacktestLedgerTest.bar(0, "100","102","98","100"));
        ledger.applyPreview(BacktestLedgerTest.preview(DecisionResult.Entry.LONG, "90"),
                BacktestLedgerTest.enter(DecisionResult.Entry.LONG), T + 59999);
        ledger.attachPendingEntrySnapshot(snapshot(T+59999, DecisionResult.Entry.LONG));
        ledger.begin(BacktestLedgerTest.bar(1,"100",high,low,close));
        return ledger;
    }

    @Test
    void attachesSignalContextOnlyOnActualFillAndPreservesItAcrossFutureBars() {
        var first = entryOnNextOpen("105", "95", "103");
        var second = entryOnNextOpen("108", "93", "107");
        var a = first.result(b("103")).openPositionDiagnostics().entryDiagnostics();
        var z = second.result(b("107")).openPositionDiagnostics().entryDiagnostics();
        assertNotNull(a);
        assertEquals(a, z); // future high, low and close MUST NOT affect the entry snapshot
        assertEquals(T + 59999, a.atSignal().signal().candleCloseTime());
        assertEquals(T + 60000, a.fillOpenTime());
        assertEquals(0, a.filledEntryPrice().compareTo(b("100.02")));
        assertEquals(0, a.initialStop().compareTo(b("90")));
        assertEquals(PriceActionSnapshot.Trend.RANGE, a.atSignal().signal()
                .timeframeTrends().get(1).trend());
        assertEquals(1, a.atSignal().higherTimeframeConflicts());
        assertEquals(0, a.atSignal().atrAtSignal().compareTo(b("3.5")));
        assertEquals(0, a.atSignal().takerBuyShareAtSignal().compareTo(b("0.7")));
        assertTrue(a.estimatedRoundTripCostUsdt().signum() > 0);
        assertTrue(a.stopDistancePct().signum() > 0);
        assertTrue(a.estimatedCostToInitialRiskPct().signum() > 0);

        var expected = a.estimatedCostToInitialRiskPct().multiply(
                a.filledEntryPrice().subtract(a.initialStop()).abs(), MathContext.DECIMAL64)
                .multiply(first.result(b("103")).openPositionDiagnostics().quantity(), MathContext.DECIMAL64)
                .divide(b("100"), MathContext.DECIMAL64);
        assertTrue(expected.subtract(a.estimatedRoundTripCostUsdt()).abs()
                .compareTo(b("0.0000000001")) < 0);
    }

    @Test
    void entryIsCarriedToClosedTradeEvenIfStopTriggersInLaterCandle() {
        var ledger = entryOnNextOpen("105", "95", "103");
        ledger.begin(BacktestLedgerTest.bar(2, "99", "110", "88", "90"));
        var trade = ledger.result(b("90")).trades().getFirst();
        assertEquals("STOP", trade.exitReason());
        assertNotNull(trade.entryDiagnostics());
        assertEquals(T + 59999, trade.entryDiagnostics().atSignal().signal().candleCloseTime());
        assertEquals(0, trade.entryDiagnostics().initialStop().compareTo(b("90")));
    }

    @Test
    void cannotAttachWrongSignalOrBlockedProposal() {
        var ledger = new BacktestLedger("JAVA", BacktestLedgerTest.policy("0","0"),
                BacktestLedgerTest.request());
        ledger.begin(BacktestLedgerTest.bar(0,"100","102","98","100"));
        // No pending proposal -> no entry created and no synthetic diagnostics.
        ledger.attachPendingEntrySnapshot(snapshot(T+59999, DecisionResult.Entry.LONG));
        ledger.applyPreview(BacktestLedgerTest.preview(DecisionResult.Entry.LONG,"90"),
                BacktestLedgerTest.enter(DecisionResult.Entry.LONG),T+59999);
        assertThrows(IllegalArgumentException.class,
                () -> ledger.attachPendingEntrySnapshot(snapshot(T+119999, DecisionResult.Entry.LONG)));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.attachPendingEntrySnapshot(snapshot(T+59999, DecisionResult.Entry.SHORT)));
    }

    @Test
    void rawLegacyLedgerOperationsRemainSupportedWithoutDiagnostic() {
        var ledger = BacktestLedgerTest.openedLong("0","0");
        assertNull(ledger.result(b("100")).openPositionDiagnostics().entryDiagnostics());
        ledger.begin(BacktestLedgerTest.bar(2,"100","103","85","90"));
        assertNull(ledger.result(b("90")).trades().getFirst().entryDiagnostics());
    }
}
