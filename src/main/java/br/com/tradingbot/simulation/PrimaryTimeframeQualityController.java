package br.com.tradingbot.simulation;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Diagnostic only: no orders, writes or new research portfolio policy. */
@RestController
@RequestMapping("/api/v1/backtest")
public class PrimaryTimeframeQualityController {
    private final PrimaryTimeframeQualityService audit;

    public PrimaryTimeframeQualityController(PrimaryTimeframeQualityService audit) {
        this.audit = audit;
    }

    @PostMapping("/audit-primary-timeframes")
    public PrimaryTimeframeQualityService.Audit audit(@RequestBody BacktestRequest request) {
        return audit.audit(request);
    }
}
