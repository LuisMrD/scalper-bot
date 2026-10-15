package br.com.tradingbot.simulation;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit research POST; no portfolio writes, trading orders or LLM calls. */
@RestController
@RequestMapping("/api/v1/backtest")
public class ReentryComparisonController {
    private final ReentryComparisonService service;

    public ReentryComparisonController(ReentryComparisonService service) {
        this.service = service;
    }

    @PostMapping("/compare-reentry-waits")
    public ReentryComparisonService.Comparison compare(@RequestBody BacktestRequest request) {
        return service.compare(request);
    }
}
