package br.com.tradingbot.strategy.independent;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Locked, read-only BTC OOS research. Not connected to any order gateway. */
@RestController
@RequestMapping("/api/v1/research/independent-setups/shared-oos")
public final class IndependentSharedOosController {
    private final IndependentSharedOosService service;
    public IndependentSharedOosController(IndependentSharedOosService service) {
        this.service=service;
    }
    @GetMapping("/status")
    public Map<String,Object> status() {
        return Map.ofEntries(
                Map.entry("mode","PAPER_ONLY"),
                Map.entry("ordersEnabled",false),
                Map.entry("symbol","BTCUSDT"),
                Map.entry("entryInterval","15m"),
                Map.entry("trendInterval","1d"),
                Map.entry("steps",IndependentSharedOosService.STEPS),
                Map.entry("midasWarmupBars",IndependentSharedOosService.WARMUP),
                Map.entry("capitalUsdt",600),
                Map.entry("sharedLongShortEquity",true),
                Map.entry("maxConcurrentPositions",1),
                Map.entry("sameOpenTieBreak","LONG_FIRST"),
                Map.entry("selectionCutoffUtc","2026-10-15T23:59:59.999Z"),
                Map.entry("earliestFullWindowEndUtc","2026-11-22T11:59:59.999Z"),
                Map.entry("holdingPolicy","DYNAMIC_TREND_60"),
                Map.entry("policies",java.util.Arrays.stream(SharedEntryBookPolicy.values())
                        .map(Enum::name).toList()));
    }
    /**
     * Deliberately separate route: the twelve historical windows are NOT OOS.
     */
    /**
     * Fixed 2023-2024 historical holdout: never relabel as prospective OOS.
     */
    @PostMapping("/backtest-historical-holdout")
    public IndependentSharedOosService.Report backtestHistoricalHoldout(
            @RequestBody IndependentResearchRequest request) {
        return service.backtestHistoricalHoldout(request);
    }
    @PostMapping("/backtest-insample")
    public IndependentSharedOosService.Report backtestInsample(
            @RequestBody IndependentResearchRequest request) {
        return service.backtestInsample(request);
    }
    @PostMapping("/backtest")
    public IndependentSharedOosService.Report backtest(
            @RequestBody IndependentResearchRequest request) {
        return service.backtest(request);
    }
}
