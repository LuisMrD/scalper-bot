package br.com.tradingbot.analysis.brooks;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/analysis/price-action")
public class PriceActionController {
    private final PriceActionService service;

    public PriceActionController(PriceActionService service) { this.service = service; }

    /** Consulta local e somente leitura; nao dispara ordens nem consultas a GPT. */
    @GetMapping
    public PriceActionSnapshot analyze(
            @RequestParam @Pattern(regexp = "[A-Z0-9]{5,32}") String symbol,
            @RequestParam String interval,
            @RequestParam(defaultValue = "150") @Min(20) @Max(500) int limit) {
        return service.analyze(symbol, interval, limit);
    }
}
