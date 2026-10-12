package br.com.tradingbot.decision.luna;

import br.com.tradingbot.analysis.context.MarketContextSnapshot;
import br.com.tradingbot.analysis.context.MultiTimeframeMarketContext;
import br.com.tradingbot.config.OpenAiProperties;
import br.com.tradingbot.decision.DecisionResult;
import br.com.tradingbot.decision.PositionState;
import br.com.tradingbot.integration.openai.OpenAiResponsesClient;
import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LunaDecisionEngineTest {
    private final JsonMapper mapper = new JsonMapper();
    private final OpenAiResponsesClient client = mock(OpenAiResponsesClient.class);
    private static final long OPEN = 60_000L;
    private static final long CLOSE = 119_999L;
    private final MarketCandle candle = new MarketCandle("BTCUSDT", CandleInterval.M1,
            OPEN, CLOSE, bd("100"), bd("105"), bd("99"), bd("104"), bd("10"), bd("1000"),
            15, bd("6"), bd("600"), true);

    private static BigDecimal bd(String v) { return new BigDecimal(v); }
    private static OpenAiProperties enabled() {
        return new OpenAiProperties(true, "gpt-6-luna", "test-secret", 512, 20, 2);
    }
    private MultiTimeframeMarketContext market(long cutoff) {
        MarketContextSnapshot frame = mock(MarketContextSnapshot.class);
        when(frame.symbol()).thenReturn("BTCUSDT");
        when(frame.interval()).thenReturn("1m");
        when(frame.requestedAsOfEpochMs()).thenReturn(cutoff);
        when(frame.asOfCloseTime()).thenReturn(CLOSE);
        when(frame.recentCandles()).thenReturn(List.of(candle));
        return new MultiTimeframeMarketContext("BTCUSDT", cutoff, List.of(frame));
    }

    @Test void offDoesNotCallApi() {
        var engine = new LunaDecisionEngine(
                new OpenAiProperties(false, "gpt-6-luna", "test-secret", 512, 20, 2), client, mapper);
        assertThrows(LunaUnavailableException.class,
                () -> engine.evaluate(market(CLOSE + 1), new PositionState(PositionState.Side.FLAT)));
        verifyNoInteractions(client);
    }

    @Test void missingKeyNeverCallsApi() {
        var engine = new LunaDecisionEngine(
                new OpenAiProperties(true, "gpt-6-luna", "", 512, 20, 2), client, mapper);
        assertThrows(LunaUnavailableException.class,
                () -> engine.evaluate(market(CLOSE + 1), new PositionState(PositionState.Side.FLAT)));
        verifyNoInteractions(client);
    }

    @Test void validWaitReadsSameContextAndAvoidsOrders() {
        when(client.evaluate(eq("test-secret"), eq("gpt-6-luna"), anyString(), eq(512), eq(20)))
                .thenReturn("{\"entry\":\"WAIT\",\"positionAction\":\"NONE\",\"setup\":\"NONE\",\"reason\":\"NO_SETUP\"}");
        var result = new LunaDecisionEngine(enabled(), client, mapper)
                .evaluate(market(CLOSE + 1), new PositionState(PositionState.Side.FLAT));
        assertEquals("LUNA", result.engine());
        assertEquals(DecisionResult.Entry.WAIT, result.entry());
        assertEquals(DecisionResult.Setup.NONE, result.setup());
        verify(client, times(1)).evaluate(eq("test-secret"), eq("gpt-6-luna"), contains("BTCUSDT"), eq(512), eq(20));
    }

    @Test void validLongRequiresSetupAndReason() {
        when(client.evaluate(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn("{\"entry\":\"LONG\",\"positionAction\":\"NONE\",\"setup\":\"PULLBACK\",\"reason\":\"PULLBACK_CONFIRMED\"}");
        var result = new LunaDecisionEngine(enabled(), client, mapper)
                .evaluate(market(CLOSE + 1), new PositionState(PositionState.Side.FLAT));
        assertEquals(DecisionResult.Entry.LONG, result.entry());
        assertEquals(DecisionResult.Setup.PULLBACK, result.setup());
    }

    @Test void existingPositionCannotReceiveNewEntry() {
        var engine = new LunaDecisionEngine(enabled(), client, mapper);
        assertThrows(LunaUnavailableException.class, () -> engine.parseDecision(
                "{\"entry\":\"SHORT\",\"positionAction\":\"NONE\",\"setup\":\"BREAKOUT_RETEST\",\"reason\":\"BREAKOUT_RETEST_CONFIRMED\"}",
                market(CLOSE + 1), new PositionState(PositionState.Side.LONG)));
    }

    @Test void exitRequiresConfirmedReversal() {
        var engine = new LunaDecisionEngine(enabled(), client, mapper);
        assertThrows(LunaUnavailableException.class, () -> engine.parseDecision(
                "{\"entry\":\"WAIT\",\"positionAction\":\"EXIT\",\"setup\":\"NONE\",\"reason\":\"NO_SETUP\"}",
                market(CLOSE + 1), new PositionState(PositionState.Side.LONG)));
        var result = engine.parseDecision(
                "{\"entry\":\"WAIT\",\"positionAction\":\"EXIT\",\"setup\":\"NONE\",\"reason\":\"CONFIRMED_REVERSAL\"}",
                market(CLOSE + 1), new PositionState(PositionState.Side.LONG));
        assertEquals(DecisionResult.PositionAction.EXIT, result.positionAction());
    }

    @Test void invalidJsonAndUnexpectedFieldFailClosed() {
        var engine = new LunaDecisionEngine(enabled(), client, mapper);
        assertThrows(LunaUnavailableException.class, () -> engine.parseDecision("not json",
                market(CLOSE + 1), new PositionState(PositionState.Side.FLAT)));
        assertThrows(LunaUnavailableException.class, () -> engine.parseDecision(
                "{\"entry\":\"WAIT\",\"positionAction\":\"NONE\",\"setup\":\"NONE\",\"reason\":\"NO_SETUP\",\"orderQuantity\":100}",
                market(CLOSE + 1), new PositionState(PositionState.Side.FLAT)));
    }

    @Test void staleCandlesPreventChargeAndReturnWait() {
        var result = new LunaDecisionEngine(enabled(), client, mapper)
                .evaluate(market(CLOSE + 121_000), new PositionState(PositionState.Side.FLAT));
        assertEquals(DecisionResult.Entry.WAIT, result.entry());
        assertEquals(DecisionResult.Reason.STALE_MARKET_DATA, result.reason());
        verifyNoInteractions(client);
    }
}
