package br.com.tradingbot.simulation;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Only serves pre-entry historical context; never issues trading orders. */
@RestController
@RequestMapping("/api/v1/backtest")
public class CrossAssetMacroController {
    private final CrossAssetMacroService service;

    public CrossAssetMacroController(CrossAssetMacroService service) {
        this.service = service;
    }

    @PostMapping("/cross-asset-macro")
    public CrossAssetMacroService.Report macro(@RequestBody CrossAssetMacroService.Request input) {
        return service.analyze(input);
    }
}
