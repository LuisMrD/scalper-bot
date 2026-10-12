package br.com.tradingbot.analysis.levels;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Endpoint descritivo, somente leitura, sem sinal ou ordem de trading. */
@Validated
@RestController
@RequestMapping("/api/v1/analysis/market-levels")
public class MarketLevelsController {
    private final MarketLevelsService service;

    public MarketLevelsController(MarketLevelsService service) {
        this.service = service;
    }

    @GetMapping
    public MarketLevelsSnapshot analyze(
            @RequestParam @Pattern(regexp = "[A-Z0-9]{5,32}") String symbol,
            @RequestParam String interval,
            @RequestParam(defaultValue = "200") @Min(20) @Max(500) int limit) {
        return service.analyze(symbol, interval, limit);
    }
}
