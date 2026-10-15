package br.com.tradingbot.simulation;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Matched 5m vs 15m historical research only: no exchange execution. */
@RestController
@RequestMapping("/api/v1/backtest")
public class PrimaryTimeframeComparisonController {
    private final PrimaryTimeframeComparisonService research;

    public PrimaryTimeframeComparisonController(PrimaryTimeframeComparisonService research) {
        this.research = research;
    }

    @PostMapping("/compare-5m-15m")
    public PrimaryTimeframeComparisonService.Comparison compare(@RequestBody BacktestRequest request) {
        return research.compare(request);
    }
}
