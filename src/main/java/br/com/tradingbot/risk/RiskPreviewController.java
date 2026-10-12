package br.com.tradingbot.risk;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints locais de simulacao. NUNCA enviam ordens a Binance. */
@RestController
@RequestMapping("/api/v1/risk")
public class RiskPreviewController {
    private final RiskPreviewService service;
    public RiskPreviewController(RiskPreviewService service) { this.service = service; }

    @GetMapping("/status")
    public Map<String,Object> status() {
        return Map.of("configured", service.configured(), "ordersEnabled", false,
                "mode", "PREVIEW_ONLY", "costFilter", service.costFilterStatus());
    }

    @PostMapping("/preview")
    public RiskPreviewResult preview(@RequestBody RiskPreviewRequest request) {
        return service.preview(request);
    }
}
