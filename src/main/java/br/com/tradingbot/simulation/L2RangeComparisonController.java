package br.com.tradingbot.simulation;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Frozen 34-39 historical study only; never places exchange orders. */
@RestController
@RequestMapping("/api/v1/backtest")
public class L2RangeComparisonController {
    private final L2RangeComparisonService study;

    public L2RangeComparisonController(L2RangeComparisonService study) {
        this.study = study;
    }

    @PostMapping("/compare-range-l2")
    public L2RangeComparisonService.Comparison compare(@RequestBody BacktestRequest request) {
        return study.compare(request);
    }
}
