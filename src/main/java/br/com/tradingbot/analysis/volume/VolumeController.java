package br.com.tradingbot.analysis.volume;

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
@RequestMapping("/api/v1/analysis/volume")
public class VolumeController {
    private final VolumeService service;
    public VolumeController(VolumeService service) { this.service = service; }

    /** Somente leitura: nunca executa ordens e nao invoca modelos de IA. */
    @GetMapping
    public VolumeSnapshot analyze(
            @RequestParam @Pattern(regexp = "[A-Z0-9]{5,32}") String symbol,
            @RequestParam String interval,
            @RequestParam(defaultValue = "150") @Min(6) @Max(500) int limit) {
        return service.analyze(symbol, interval, limit);
    }
}
