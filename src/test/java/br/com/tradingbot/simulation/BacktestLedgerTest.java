package br.com.tradingbot.simulation;

import br.com.tradingbot.decision.DecisionResult;
import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import br.com.tradingbot.risk.RiskPolicy;
import br.com.tradingbot.risk.RiskPreviewResult;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BacktestLedgerTest {
    static final long T = 1800000000000L;
    static BigDecimal b(String n) { return new BigDecimal(n); }
    static BacktestRequest request() {
        return new BacktestRequest("BTCUSDT", List.of("1m"), 3, 30,
                null, false, b("0.01"), b("0.001"), b("0.001"), b("5"));
    }
    static RiskPolicy policy(String fee, String slip) {
        return new RiskPolicy(b("300"), b("1"), b("3"), 3,
                b(fee), b(slip), b("1"), b("0.5"));
    }
    static MarketCandle bar(int i, String open, String high, String low, String close) {
        long t = T + i*60000L;
        return new MarketCandle("BTCUSDT", CandleInterval.M1, t, t+59999,
                b(open), b(high), b(low), b(close), b("10"), b("1000"),
                10, b("5"), b("500"), true);
    }
    static DecisionResult enter(DecisionResult.Entry side) {
        return new DecisionResult("JAVA", "BTCUSDT", "1m", T+59999, T+59999,
                side, DecisionResult.PositionAction.NONE, DecisionResult.Setup.PULLBACK,
                DecisionResult.Reason.PULLBACK_CONFIRMED);
    }
    static DecisionResult hold() {
        return new DecisionResult("JAVA", "BTCUSDT", "1m", T+119999,T+119999,
                DecisionResult.Entry.WAIT, DecisionResult.PositionAction.HOLD, DecisionResult.Setup.NONE,
                DecisionResult.Reason.POSITION_HOLD);
    }
    static RiskPreviewResult preview(DecisionResult.Entry side, String stop) {
        return new RiskPreviewResult("JAVA", "BTCUSDT", "1m", T+59999,
                RiskPreviewResult.Action.PROPOSED_ENTRY, RiskPreviewResult.Reason.PREVIEW_ONLY,
                b("100"), b("0.1"), b("10"), b("3"), b(stop), null, b("1"), false);
    }
    static BacktestLedger openedLong(String fee, String slip) {
        var ledger = new BacktestLedger("JAVA", policy(fee,slip), request());
        ledger.begin(bar(0,"100","101","99","100"));
        ledger.applyPreview(preview(DecisionResult.Entry.LONG,"90"),
                enter(DecisionResult.Entry.LONG), T+59999);
        ledger.begin(bar(1,"100","101","95","100"));
        return ledger;
    }

    @Test void entryFillsNextBarNotOnSignalClose() {
        var ledger = new BacktestLedger("JAVA", policy("0","0"), request());
        ledger.begin(bar(0,"100","101","99","100"));
        ledger.applyPreview(preview(DecisionResult.Entry.LONG,"90"),
                enter(DecisionResult.Entry.LONG), T+59999);
        assertEquals("FLAT", ledger.result(b("100")).openPositionSide());
        ledger.begin(bar(1,"100","101","95","100"));
        assertEquals("LONG", ledger.result(b("100")).openPositionSide());
    }

    @Test void feeAndAdverseSlippageReduceNetProfit() {
        var withCosts = openedLong("0.05","0.02");
        var noCosts = openedLong("0","0");
        // Next candle lows touch stop: close both positions.
        withCosts.begin(bar(2,"102","103","88","100"));
        noCosts.begin(bar(2,"102","103","88","100"));
        var a = withCosts.result(b("100"));
        var z = noCosts.result(b("100"));
        assertEquals(1, a.tradesClosed());
        assertTrue(a.trades().getFirst().netPnlUsdt().compareTo(z.trades().getFirst().netPnlUsdt()) < 0);
        assertTrue(a.feesUsdt().signum() > 0);
    }

    @Test void stopGapIsExecutedAtWorseOpeningPrice() {
        var ledger = openedLong("0","0.02");
        ledger.begin(bar(2,"85","86","80","82"));
        var t = ledger.result(b("82")).trades().getFirst();
        assertEquals("STOP",t.exitReason());
        assertTrue(t.exitPrice().compareTo(b("85")) < 0);
        assertTrue(t.netPnlUsdt().signum() < 0);
    }

    @Test void longBreakEvenAdjustmentWorksNextBar() {
        var ledger = openedLong("0","0");
        var p = new RiskPreviewResult("JAVA","BTCUSDT","1m",T+119999,
                RiskPreviewResult.Action.ADJUST_STOP, RiskPreviewResult.Reason.BREAK_EVEN_OR_TRAILING,
                b("100"),b("0.1"),b("10"),null,b("90"),b("100.5"),null,true);
        ledger.applyPreview(p, hold(), T+119999);
        ledger.begin(bar(2,"102","103","100","101"));
        assertEquals(1,ledger.result(b("101")).tradesClosed());
        assertTrue(ledger.result(b("101")).trades().getFirst().exitPrice().compareTo(b("100.5")) >= 0);
    }

    @Test void shortPositionProfitsWhenPriceFalls() {
        var ledger = new BacktestLedger("JAVA",policy("0","0"), request());
        ledger.begin(bar(0,"100","101","99","100"));
        ledger.applyPreview(preview(DecisionResult.Entry.SHORT,"110"),
                enter(DecisionResult.Entry.SHORT), T+59999);
        ledger.begin(bar(1,"100","105","98","100"));
        var exit = new RiskPreviewResult("JAVA","BTCUSDT","1m",T+119999,
                RiskPreviewResult.Action.EXIT_SIGNAL,RiskPreviewResult.Reason.CONFIRMED_REVERSAL,
                b("100"),b("0.1"),b("10"),null,b("110"),b("110"),null,false);
        ledger.applyPreview(exit,hold(),T+119999);
        ledger.begin(bar(2,"98","99","97","98"));
        assertTrue(ledger.result(b("98")).trades().getFirst().netPnlUsdt().signum() > 0);
    }

    @Test void entryGapThroughStopBlocksRatherThanReversesRisk() {
        var ledger = new BacktestLedger("JAVA",policy("0","0"),request());
        ledger.begin(bar(0,"100","101","99","100"));
        ledger.applyPreview(preview(DecisionResult.Entry.LONG,"90"),
                enter(DecisionResult.Entry.LONG),T+59999);
        ledger.begin(bar(1,"85","86","80","82"));
        assertEquals(1,ledger.result(b("82")).entriesBlocked());
        assertEquals("FLAT",ledger.result(b("82")).openPositionSide());
    }

    @Test void unrealizedPositionIsMarkedWithoutInventingClosedTrade() {
        var ledger = openedLong("0.05","0.02");
        ledger.markClose(b("105"));
        var r=ledger.result(b("105"));
        assertEquals(0,r.tradesClosed());
        assertEquals("LONG",r.openPositionSide());
        assertTrue(r.markedEquityUsdt().compareTo(r.realizedEquityUsdt()) > 0);
    }

    @Test void requestRejectsExpensiveLunaRunsAndMissingFilters() {
        assertThrows(IllegalArgumentException.class, () -> new BacktestRequest(
                "BTCUSDT",List.of("1m"),11,50,null,true,b("0.01"),b("0.001"),b("0.001"),b("5")));
        assertThrows(IllegalArgumentException.class, () -> new BacktestRequest(
                "BTCUSDT",List.of("1m"),5,50,null,false,null,b("0.001"),b("0.001"),b("5")));
    }
}
