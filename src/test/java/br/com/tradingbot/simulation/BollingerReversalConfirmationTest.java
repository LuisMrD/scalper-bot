package br.com.tradingbot.simulation;

import static org.junit.jupiter.api.Assertions.*;

import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BollingerReversalConfirmationTest {
    static final long OPEN = 1800000000000L - 1800000000000L % 300000L;

    private static MarketCandle bar(int i, String o, String h, String l, String c, boolean traded) {
        long open = OPEN + i*300_000L;
        return new MarketCandle("SOLUSDT", CandleInterval.M5, open, open+299999,
                new BigDecimal(o),new BigDecimal(h),new BigDecimal(l),
                new BigDecimal(c),traded?BigDecimal.ONE:BigDecimal.ZERO,
                traded?new BigDecimal("100"):BigDecimal.ZERO,traded?1:0,
                BigDecimal.ZERO,BigDecimal.ZERO,true);
    }

    private static List<MarketCandle> trigger(boolean longSide) {
        var candles = new ArrayList<MarketCandle>();
        for (int i=0;i<19;i++){
            candles.add(bar(i,"100","101","99","100",true));
        }
        candles.add(longSide ? bar(19,"83","85","77","80",true)
                             : bar(19,"117","123","115","120",true));
        return candles;
    }

    @Test
    void outsideCloseOnlyArmsAndNextBullishHammerConfirmsLong() {
        var state=new BollingerReversalConfirmation();
        var tape=trigger(true);
        var armed=state.onClosedBar(tape);
        assertTrue(armed.armed());
        assertNull(armed.confirmed(),"Outside close itself MUST NOT enter");
        tape.add(bar(20,"79","83","70","82",true));
        var confirmed=state.onClosedBar(tape);
        assertNotNull(confirmed.confirmed());
        assertEquals(BollingerReversalConfirmation.Pattern.HAMMER_OR_SHOOTING_STAR,
                confirmed.confirmed().pattern());
        assertEquals(tape.get(19).closeTime(),
                confirmed.confirmed().trigger().candleCloseTime());
        assertEquals(tape.get(20).closeTime(),confirmed.confirmed().confirmedAtCloseTime());
        assertTrue(confirmed.confirmed().trigger().middle().compareTo(new BigDecimal("82"))>0);
    }

    @Test
    void bullishForceCandleConfirmsOnlyAfterLowerBandBreak() {
        var state=new BollingerReversalConfirmation();
        var tape=trigger(true);
        state.onClosedBar(tape);
        tape.add(bar(20,"80","92","79","90",true));
        var result=state.onClosedBar(tape);
        assertEquals(BollingerReversalConfirmation.Pattern.STRONG_REVERSAL_CANDLE,
                result.confirmed().pattern());
    }

    @Test
    void reversalCloseBreakingTriggerHighCanConfirmWithoutHammerOrStrongBody() {
        var state=new BollingerReversalConfirmation();
        var tape=trigger(true);
        state.onClosedBar(tape);
        tape.add(bar(20,"80","92","76","86",true));
        var result=state.onClosedBar(tape);
        assertEquals(BollingerReversalConfirmation.Pattern.CLOSE_BREAKS_PRIOR_EXTREME,
                result.confirmed().pattern());
        assertEquals(0,result.confirmed().trigger().close().compareTo(new BigDecimal("80")));
    }

    @Test
    void upperBandRequiresLaterBearishShootingStarOrBearishConfirmation() {
        var state=new BollingerReversalConfirmation();
        var tape=trigger(false);
        assertTrue(state.onClosedBar(tape).armed());
        tape.add(bar(20,"121","128","117","118",true));
        var result=state.onClosedBar(tape);
        assertNotNull(result.confirmed());
        assertEquals(BollingerMeanReversion.Side.SHORT,result.confirmed().trigger().side());
        assertEquals(BollingerReversalConfirmation.Pattern.HAMMER_OR_SHOOTING_STAR,
                result.confirmed().pattern());
    }

    @Test
    void bearishContinuationAndWickWithoutCloseConfirmationCannotEnter() {
        var state=new BollingerReversalConfirmation();
        var tape=trigger(true);
        state.onClosedBar(tape);
        tape.add(bar(20,"82","84","70","76",true));
        assertNull(state.onClosedBar(tape).confirmed());
        tape.add(bar(21,"78","88","75","78",true)); // green? doji; no confirmation
        assertNull(state.onClosedBar(tape).confirmed());
    }

    @Test
    void targetReachedBeforeConfirmationInvalidatesExpiredOpportunity() {
        var state=new BollingerReversalConfirmation();
        var tape=trigger(true);
        state.onClosedBar(tape);
        tape.add(bar(20,"81","102","80","92",true)); // reaches frozen SMA 99
        var step=state.onClosedBar(tape);
        assertTrue(step.invalidated());
        assertNull(step.confirmed());
    }

    @Test
    void noTradeConfirmationCandleInvalidatesInsteadOfInventingPriceAction() {
        var state=new BollingerReversalConfirmation();
        var tape=trigger(true);
        state.onClosedBar(tape);
        tape.add(bar(20,"79","83","70","82",false));
        assertTrue(state.onClosedBar(tape).invalidated());
    }

    @Test
    void nineUnconfirmedCandlesExpireAndDoNotEnter() {
        var state=new BollingerReversalConfirmation();
        var tape=trigger(true);
        state.onClosedBar(tape);
        for(int i=20;i<=29;i++){
            tape.add(bar(i,"80","84","78","80",true));
            var step=state.onClosedBar(tape);
            assertNull(step.confirmed());
            if(i==29)assertTrue(step.expired());
        }
    }

    @Test
    void skipBarCancelsOldSetupAndDoesNotCauseLookahead() {
        var state=new BollingerReversalConfirmation();
        var tape=trigger(true);
        state.onClosedBar(tape);
        var next=bar(20,"79","83","70","82",true);
        state.skipClosedBar(next);
        tape.add(next);
        tape.add(bar(21,"82","84","80","83",true));
        assertNull(state.onClosedBar(tape).confirmed());
        assertThrows(IllegalArgumentException.class,()->state.onClosedBar(tape));
    }
}
