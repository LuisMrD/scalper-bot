package br.com.tradingbot.strategy.independent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import br.com.tradingbot.analysis.context.MarketContextService;
import br.com.tradingbot.config.TradingProperties;
import br.com.tradingbot.market.service.MarketDataService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SharedEntryOosResearchTest {
    private static BigDecimal b(String v) { return new BigDecimal(v); }

    private IndependentResearchRequest request(long asOf) {
        return new IndependentResearchRequest("BTCUSDT",3600,60,"15m","1d",asOf,
                new IndependentSetupParameters(2,2,3,5,b("0.5"),b("0.85"),
                        b("0.85"),b("0.65"),b("2"),b("1.2"),b("2"),
                        10,b("2"),b("0.85"),b("0.85"),b("1.2"),
                        6,b("0.2"),b("1"),14,20,
                        IndependentSetupParameters.DirectStopRule.BOX_BASE,b("1")),
                b("600"),b("0.5"),b("3"),1,
                b("0.05"),b("0.02"),b("0.01"),b("0"),b("0.5"),
                b("0.01"),b("0.001"),b("0.001"),b("5"),b("50"),30);
    }
    private long end(String iso) { return Instant.parse(iso).toEpochMilli(); }

    @Test void threeFrozenPoliciesHaveAsymmetricSides() {
        assertEquals(EntrySelectionPolicy.BASELINE,
                SharedEntryBookPolicy.CONTROL.longGate());
        assertEquals(EntrySelectionPolicy.BASELINE,
                SharedEntryBookPolicy.CONTROL.shortGate());
        assertEquals(EntrySelectionPolicy.WEEKDAYS_ONLY,
                SharedEntryBookPolicy.CANDIDATE_A.longGate());
        assertEquals(EntrySelectionPolicy.COMBINED_045,
                SharedEntryBookPolicy.CANDIDATE_A.shortGate());
        assertEquals(EntrySelectionPolicy.WEEKDAYS_ONLY,
                SharedEntryBookPolicy.CANDIDATE_B.longGate());
        assertEquals(EntrySelectionPolicy.COMBINED_050,
                SharedEntryBookPolicy.CANDIDATE_B.shortGate());
        assertEquals(3,SharedEntryBookPolicy.values().length);
    }

    @Test void studyRejectsInsampleAndPartial900hWithoutTouchingMarket() {
        var market=mock(MarketDataService.class);
        var contexts=mock(MarketContextService.class);
        var trading=mock(TradingProperties.class);
        var service=new IndependentSharedOosService(market,contexts,trading,
                Clock.fixed(Instant.parse("2026-12-01T00:00:00Z"),ZoneOffset.UTC));
        for(var close:new String[]{
                "2026-10-15T23:59:59.999Z",
                "2026-11-22T11:44:59.999Z"}) {
            var error=assertThrows(ResponseStatusException.class,
                    ()->service.backtest(request(end(close))));
            assertEquals(400,error.getStatusCode().value());
        }
        verifyNoInteractions(market,contexts,trading);
    }

    @Test void firstCompleteWindowStartsExactlyAtOosBoundary() {
        var service=new IndependentSharedOosService(
                mock(MarketDataService.class),mock(MarketContextService.class),
                mock(TradingProperties.class),
                Clock.fixed(Instant.parse("2026-12-01T00:00:00Z"),ZoneOffset.UTC));
        var request=request(end("2026-11-22T11:59:59.999Z"));
        IndependentSharedOosService.validateFrozen(request);
        service.validatePeriod(request);
        assertEquals(end("2026-10-16T00:00:00Z"),
                IndependentSharedOosService.firstOpen(request.asOf()));
    }

    @Test void rejectsFutureAndParameterDrift() {
        var service=new IndependentSharedOosService(
                mock(MarketDataService.class),mock(MarketContextService.class),
                mock(TradingProperties.class),
                Clock.fixed(Instant.parse("2026-10-17T23:00:00Z"),ZoneOffset.UTC));
        var future=request(end("2026-11-22T11:59:59.999Z"));
        assertEquals(400,assertThrows(ResponseStatusException.class,
                ()->service.backtest(future)).getStatusCode().value());
        var changed=new IndependentResearchRequest(future.symbol(),future.steps(),
                future.contextCandles(),future.entryInterval(),future.trendInterval(),
                future.asOf(),future.parameters(),future.capitalUsdt(),
                b("1"),future.dailyStopPct(),future.leverage(),future.feePerSidePct(),
                future.slippagePerSidePct(),future.spreadPct(),
                future.assumedFundingPer8hPct(),future.assumedMaintenanceMarginPct(),
                future.priceTick(),future.quantityStep(),future.minimumQuantity(),
                future.minimumNotionalUsdt(),future.maxCostToInitialRiskPct(),
                future.maxHoldingCandles());
        assertEquals(400,assertThrows(ResponseStatusException.class,
                ()->service.backtest(changed)).getStatusCode().value());
    }

    @Test void sharedWalletLocksPositionAndCombinesDailyPnl() {
        var wallet=new SharedPaperWallet(b("600"));
        var longBook=mock(IndependentPaperPortfolio.class);
        var shortBook=mock(IndependentPaperPortfolio.class);
        wallet.beginDay(end("2026-10-16T12:00:00Z"));
        wallet.acquired(longBook,b("599.70"));
        assertTrue(wallet.occupied());
        assertThrows(IllegalStateException.class,
                ()->wallet.acquired(shortBook,b("599.40")));
        wallet.funded(longBook,b("599.60"));
        wallet.released(longBook,b("598.00"),b("-2.00"));
        assertFalse(wallet.occupied());
        assertEquals(0,wallet.cash().compareTo(b("598")));
        assertEquals(0,wallet.dailyRealized().compareTo(b("-2")));
        wallet.acquired(shortBook,b("597.80"));
        wallet.released(shortBook,b("596.50"),b("-3.50"));
        assertEquals(0,wallet.dailyRealized().compareTo(b("-3.5")));
        wallet.mark(b("100"));
        assertTrue(wallet.worstDrawdownPct().compareTo(b("0.58"))>0);
        assertTrue(wallet.worstDrawdownPct().compareTo(b("0.59"))<0);
        wallet.beginDay(end("2026-10-17T00:00:00Z"));
        assertEquals(0,wallet.dailyRealized().signum());
    }
}
