package br.com.tradingbot.simulation;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Research-only entry filter comparison. Never submits trading orders. */
@RestController
@RequestMapping("/api/v1/backtest")
public class EntryFilterComparisonController {
    private final EntryFilterComparisonService service;

    public EntryFilterComparisonController(EntryFilterComparisonService service) {
        this.service = service;
    }

    @PostMapping("/compare-entry-filters")
    public EntryFilterComparisonService.Comparison compare(@RequestBody BacktestRequest request) {
        return service.compare(request);
    }
}
