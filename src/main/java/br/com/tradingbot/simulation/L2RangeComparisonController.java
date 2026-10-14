package br.com.tradingbot.simulation;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
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

    /**
     * Spring hides ResponseStatusException.reason by default, leaving HTTP 422
     * with no actionable body. Restrict disclosure to the diagnostic endpoint:
     * only synthetic scenario names, candle times and integrity reasons.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> historicalError(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                "code", "REPLAY_REJECTED",
                "message", ex.getReason() == null ? "Historical replay rejected" : ex.getReason()));
    }

    @PostMapping("/compare-range-l2")
    public L2RangeComparisonService.Comparison compare(@RequestBody BacktestRequest request) {
        return study.compare(request);
    }
}
