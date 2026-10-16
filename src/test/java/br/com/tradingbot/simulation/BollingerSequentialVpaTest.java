package br.com.tradingbot.simulation;

import static org.junit.jupiter.api.Assertions.*;

import br.com.tradingbot.analysis.brooks.PriceActionSnapshot;
import br.com.tradingbot.analysis.context.MarketContextSnapshot;
import br.com.tradingbot.analysis.context.MultiTimeframeMarketContext;
import br.com.tradingbot.analysis.levels.MarketLevelsSnapshot;
import br.com.tradingbot.analysis.volume.VolumeSnapshot;
import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import br.com.tradingbot.risk.RiskPolicy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BollingerSequentialVpaTest {
    private static final long DAY = CandleInterval.D1.millis();
    private static final long START = 1_800_000_000_000L
            - 1_800_000_000_000L % DAY;
    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private static MarketCandle bar(int day, String o, String h,
            String l, String c, String volume) {
        long open = START + day * DAY;
        return new MarketCandle("SOLUSDT", CandleInterval.D1, open, open + DAY - 1,
                bd(o), bd(h), bd(l), bd(c), bd(volume), bd(volume),
                100, bd(volume).multiply(bd("0.5")),
                bd(volume).multiply(bd("0.5")), true);
    }

    private static List<MarketCandle> tape(boolean exhaustion) {
        var tape = new ArrayList<MarketCandle>();
        for (int i = 0; i < 20; i++)
            tape.add(bar(i, "100", "102", "98", "100", "1000"));
        if (exhaustion) {
            // Trigger volume 2x baseline; lower wick 33%, then falling
            // volume/spread and recovery, with BALANCED taker flows.
            tape.add(bar(20, "100", "102", "90", "94", "2000"));
            tape.add(bar(21, "93", "100", "92", "98", "1100"));
        } else {
            // No spike, rejection, or weakening of effort relative to range.
            tape.add(bar(20, "100", "101", "94", "94.5", "1000"));
            tape.add(bar(21, "94", "101", "93", "98", "1000"));
        }
        return tape;
    }

    private static BollingerReversalConfirmation.Confirmation signal(
            List<MarketCandle> tape) {
        var t = tape.get(20);
        var trigger = new BollingerMeanReversion.Signal(
                BollingerMeanReversion.Side.LONG, t.closeTime(),
                t.close(), t.high(), t.low(), bd("93"), bd("112"), bd("120"));
        return new BollingerReversalConfirmation.Confirmation(trigger,
                tape.getLast().closeTime(),
                BollingerReversalConfirmation.Pattern.STRONG_REVERSAL_CANDLE,
                t.low().min(tape.getLast().low()),
                t.high().max(tape.getLast().high()));
    }

    private static VolumeSnapshot.Observation obs(MarketCandle last,
            VolumeSnapshot.EffortResult effort) {
        return new VolumeSnapshot.Observation(
                last.openTime(), last.closeTime(), last.baseVolume(),
                last.quoteVolume(), bd("1.1"), bd("1.0"), bd("0.5"),
                VolumeSnapshot.VolumeLevel.NORMAL, VolumeSnapshot.SpreadLevel.NORMAL,
                VolumeSnapshot.TakerBalance.BALANCED, effort);
    }

    private static MultiTimeframeMarketContext market(List<MarketCandle> tape,
            VolumeSnapshot.Observation obs) {
        var last = tape.getLast();
        var pa = new PriceActionSnapshot("SOLUSDT", "1d", last.closeTime(), 22,
                PriceActionSnapshot.Trend.RANGE, List.of(),
                List.of(new PriceActionSnapshot.ConfirmedPivot(
                        PriceActionSnapshot.PivotType.LOW,
                        tape.get(17).openTime(), tape.get(19).closeTime(), bd("90"))),
                PriceActionSnapshot.Breakout.none(), false);
        var vpa = new VolumeSnapshot("SOLUSDT", "1d", last.closeTime(),
                22, 20, List.of(obs));
        var levels = new MarketLevelsSnapshot("SOLUSDT", "1d",
                last.closeTime(), 22, last.close(), bd("1"), bd("1"),
                List.of(), null);
        var frame = new MarketContextSnapshot("SOLUSDT", "1d", last.closeTime(),
                last.closeTime(), 22, tape, pa, vpa, levels);
        return new MultiTimeframeMarketContext("SOLUSDT", last.closeTime(),
                List.of(frame));
    }

    private static RiskPolicy risk() {
        return new RiskPolicy(bd("1500"), bd("0.5"), bd("3"), 1,
                bd("0.05"), bd("0.02"), bd("1"), bd("1"), bd("50"));
    }

    private static BacktestRequest request(long end) {
        return new BacktestRequest("SOLUSDT", List.of("1d"), 30, 50, end, false,
                bd("0.01"), bd("0.001"), bd("0.001"), bd("5"),
                BigDecimal.ONE, bd("50"), null, false, true, false);
    }

    @Test
    void balancedTakerIsEligibleWithCausalEffortFadeAndRecovery() {
        var tape = tape(true);
        var last = tape.getLast();
        var signal = signal(tape);
        var context = market(tape, obs(last, VolumeSnapshot.EffortResult.NONE));
        var policy = new BollingerFilteredPolicy();
        var legacy = policy.diagnose(context, signal, request(last.closeTime()), risk(),
                BollingerFilteredPolicy.StopMode.EXTREME_CANDLE);
        var sequence = policy.diagnose(context, signal, request(last.closeTime()), risk(),
                BollingerFilteredPolicy.StopMode.EXTREME_CANDLE,
                BollingerFilteredPolicy.VolumeMode.SEQUENCE_EFFORT_RESULT);
        assertEquals("VOLUME_NO_REVERSAL_EVIDENCE", legacy.firstRejection());
        assertTrue(sequence.approved(), sequence.failedChecks().toString());
        assertNotNull(sequence.sequenceEvidence());
        assertTrue(sequence.sequenceEvidence().triggerAbsorption());
        assertTrue(sequence.sequenceEvidence().continuationPressureFaded());
        assertTrue(sequence.sequenceEvidence().failedContinuation());
        assertEquals(2, sequence.sequenceEvidence().sequenceCandles());
    }

    @Test
    void balancedFlowAloneWithoutSequentialEvidenceDoesNotQualify() {
        var tape = tape(false);
        var signal = signal(tape);
        var market = market(tape, obs(tape.getLast(), VolumeSnapshot.EffortResult.NONE));
        var result = new BollingerFilteredPolicy().diagnose(market, signal,
                request(tape.getLast().closeTime()), risk(),
                BollingerFilteredPolicy.StopMode.EXTREME_CANDLE,
                BollingerFilteredPolicy.VolumeMode.SEQUENCE_EFFORT_RESULT);
        assertFalse(result.approved());
        assertEquals("VPA_SEQUENCE_NO_EFFORT_RESULT_EVIDENCE", result.firstRejection());
    }

    @Test
    void highVolumeContinuationStillVetoesSequenceEvenWithAbsorption() {
        var tape = tape(true);
        var market = market(tape, obs(tape.getLast(),
                VolumeSnapshot.EffortResult.BEARISH_WIDE_SPREAD_HIGH_VOLUME));
        var result = new BollingerFilteredPolicy().diagnose(market,
                signal(tape), request(tape.getLast().closeTime()), risk(),
                BollingerFilteredPolicy.StopMode.EXTREME_CANDLE,
                BollingerFilteredPolicy.VolumeMode.SEQUENCE_EFFORT_RESULT);
        assertFalse(result.approved());
        assertEquals("VPA_SEQUENCE_STRONG_CONTINUATION", result.firstRejection());
    }

    @Test
    void refusesTriggerMissingFromCompletedContextInsteadOfInventingVolume() {
        var tape = tape(true);
        var incomplete = tape.subList(19, tape.size());
        var evidence = new BollingerSequenceVpa().assess(incomplete,
                signal(tape), obs(tape.getLast(), VolumeSnapshot.EffortResult.NONE));
        assertEquals("VPA_SEQUENCE_INSUFFICIENT_HISTORY", evidence.rejection());
    }
}
