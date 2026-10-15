package br.com.tradingbot.simulation;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Three independent research-only portfolios; never transmits live orders. */
@RestController
@RequestMapping("/api/v1/backtest")
public class ShortRangeComparisonController {
    private final ShortRangeComparisonService service;

    public ShortRangeComparisonController(ShortRangeComparisonService service) {
        this.service = service;
    }

    @PostMapping("/compare-short-range")
    public ShortRangeComparisonService.Comparison compare(@RequestBody BacktestRequest request) {
        return service.compare(request);
    }
}
