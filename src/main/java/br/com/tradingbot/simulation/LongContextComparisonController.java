package br.com.tradingbot.simulation;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Simulation-only frozen 40..45 LONG context experiment. */
@RestController
@RequestMapping("/api/v1/backtest")
public class LongContextComparisonController {
    private final LongContextComparisonService service;

    public LongContextComparisonController(LongContextComparisonService service) {
        this.service = service;
    }

    @PostMapping("/compare-long-context")
    public LongContextComparisonService.Comparison compare(@RequestBody BacktestRequest request) {
        return service.compare(request);
    }
}
