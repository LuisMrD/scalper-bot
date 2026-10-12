package br.com.tradingbot.simulation;

import br.com.tradingbot.decision.DecisionResult;
import br.com.tradingbot.risk.RiskPreviewResult;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BacktestTimeExitTest {
    private static BigDecimal b(String n) { return new BigDecimal(n); }
    private static final long T = BacktestLedgerTest.T;

    private static BacktestRequest request(Integer max) {
        return new BacktestRequest("BTCUSDT", List.of("1m"), 20, 30,
                null, false, b("0.01"), b("0.001"), b("0.001"), b("5"),
                b("1"), null, max);
    }

    private static BacktestLedger entered(String fee, String slip, Integer max) {
        var ledger = new BacktestLedger("JAVA", BacktestLedgerTest.policy(fee, slip), request(max));
        ledger.begin(BacktestLedgerTest.bar(0, "100", "101", "99", "100"));
        ledger.applyPreview(BacktestLedgerTest.preview(DecisionResult.Entry.LONG, "90"),
                BacktestLedgerTest.enter(DecisionResult.Entry.LONG), T+59999);
        return ledger;
    }

    @Test
    void holdsTwoCompletedCandlesAndExitsAtNextOpenNotOnSignalClose() {
        var ledger = entered("0", "0", 2);
        ledger.begin(BacktestLedgerTest.bar(1, "100", "103", "95", "102"));
        ledger.markClose(b("102"), T+119999, null, null);
        assertEquals(0, ledger.result(b("102")).tradesClosed());
        ledger.begin(BacktestLedgerTest.bar(2, "102", "106", "100", "104"));
        ledger.markClose(b("104"), T+179999, null, null);
        assertEquals(0, ledger.result(b("104")).tradesClosed());
        // Next candle reaches high 200 or low 10 AFTER time exit at open 105.
        ledger.begin(BacktestLedgerTest.bar(3, "105", "200", "10", "100"));
        var t = ledger.result(b("100")).trades().getFirst();
        assertEquals("TIME_LIMIT", t.exitReason());
        assertEquals(2, t.durationCandles());
        assertEquals(120000L, t.durationMillis());
        assertEquals(0, t.exitPrice().compareTo(b("105")));
        assertEquals(0, t.netPnlUsdt().compareTo(b("0.5")));
        assertEquals(2, t.timing().netPositiveCloseCandles());
        assertEquals(T+119999, t.timing().firstNetPositiveCloseTime());
        assertEquals(2, t.timing().closeHistory().size());
        assertEquals(0, t.timing().maxEstimatedNetPnlUsdt().compareTo(b("0.4")));
        assertNull(t.timing().netPnlAt15Candles());
        assertNull(t.timing().firstConfirmedReversalCloseTime());
    }

    @Test
    void stoppedPositionDoesNotWaitForTimeLimitOrClaimFutureBarClose() {
        var ledger = entered("0","0", 2);
        ledger.begin(BacktestLedgerTest.bar(1, "100","102","95","101"));
        ledger.markClose(b("101"));
        ledger.begin(BacktestLedgerTest.bar(2, "101","150","88","100"));
        var t = ledger.result(b("100")).trades().getFirst();
        assertEquals("STOP",t.exitReason());
        assertEquals(1,t.timing().closeHistory().size());
        assertEquals(0,t.maxFavorableExcursionUsdt().compareTo(b("0.2")));
        assertEquals(2,t.durationCandles());
    }

    @Test
    void profitableCloseFollowedByLossMeasuresGivebackIncludingCosts() {
        var ledger = entered("0","0",2);
        ledger.begin(BacktestLedgerTest.bar(1,"100","106","95","105"));
        ledger.markClose(b("105"),T+119999,null,null);
        ledger.begin(BacktestLedgerTest.bar(2,"105","106","91","95"));
        ledger.markClose(b("95"),T+179999,null,null);
        ledger.begin(BacktestLedgerTest.bar(3,"94","120","80","100"));
        var t=ledger.result(b("100")).trades().getFirst();
        assertEquals("TIME_LIMIT",t.exitReason());
        assertEquals(0,t.netPnlUsdt().compareTo(b("-0.6")));
        assertEquals(0,t.timing().maxEstimatedNetPnlUsdt().compareTo(b("0.5")));
        assertEquals(0,t.timing().givebackFromBestCloseUsdt().compareTo(b("1.1")));
        assertEquals(1,t.timing().netPositiveCloseCandles());
        assertEquals(T+119999,t.timing().firstNetPositiveCloseTime());
    }

    @Test
    void includesEstimatedExitSlippageAndBothFeesInCloseObservations() {
        var ledger = entered("0.05","0.02",1);
        ledger.begin(BacktestLedgerTest.bar(1,"100","105","95","104"));
        ledger.markClose(b("104"),T+119999,null,null);
        var active=ledger.result(b("104")).openPositionDiagnostics();
        var first=active.timing().closeHistory().getFirst();
        assertEquals(0, active.unrealizedNetPnlUsdt()
                .compareTo(first.estimatedNetPnlUsdt()));
        // Candle 2 time-exits at open 104, the recorded mark of candle 1
        // is therefore consistent with the realized liquidation PnL.
        ledger.begin(BacktestLedgerTest.bar(2,"104","160","50","100"));
        var trade=ledger.result(b("100")).trades().getFirst();
        assertEquals("TIME_LIMIT",trade.exitReason());
        assertEquals(0,trade.netPnlUsdt().compareTo(first.estimatedNetPnlUsdt()));
        assertTrue(trade.feesUsdt().signum()>0);
        assertTrue(trade.slippageUsdt().signum()>0);
        assertEquals(0,trade.timing().givebackFromBestCloseUsdt().signum());
    }

    @Test
    void exitSignalWinsWhenAlreadyScheduledAtTimeLimit() {
        var ledger=entered("0","0",1);
        ledger.begin(BacktestLedgerTest.bar(1,"100","102","95","101"));
        var preview=new RiskPreviewResult("JAVA","BTCUSDT","1m",T+119999,
                RiskPreviewResult.Action.EXIT_SIGNAL,
                RiskPreviewResult.Reason.CONFIRMED_REVERSAL,
                b("101"),b("0.1"),b("10"),null,b("90"),b("90"),null,false);
        ledger.applyPreview(preview,BacktestLedgerTest.hold(),T+119999);
        ledger.markClose(b("101"),T+119999,null,null);
        ledger.begin(BacktestLedgerTest.bar(2,"101","150","50","100"));
        assertEquals("EXIT_SIGNAL",ledger.result(b("100")).trades().getFirst().exitReason());
    }

    @Test
    void noNextCandleMeansNoFictitiousTimeExit() {
        var ledger=entered("0","0",1);
        ledger.begin(BacktestLedgerTest.bar(1,"100","102","95","101"));
        ledger.markClose(b("101"),T+119999,null,null);
        assertEquals(0,ledger.result(b("101")).tradesClosed());
        assertEquals("LONG",ledger.result(b("101")).openPositionSide());
    }

    @Test
    void baselineRemainsUnchangedWhenLimitIsAbsentAndValidationIsStrict() {
        var ledger=entered("0","0",null);
        ledger.begin(BacktestLedgerTest.bar(1,"100","102","95","101"));
        ledger.markClose(b("101"));
        ledger.begin(BacktestLedgerTest.bar(2,"101","103","95","102"));
        ledger.markClose(b("102"));
        assertEquals("LONG",ledger.result(b("102")).openPositionSide());
        assertThrows(IllegalArgumentException.class,()->request(0));
        assertThrows(IllegalArgumentException.class,()->request(-1));
        assertThrows(IllegalArgumentException.class,()->request(3001));
        assertNotNull(request(15));
    }
}
