package org.example.stockwatch247.controller;

import org.example.stockwatch247.service.ElliottWaveDrilldownService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stocks")
public class ElliottWaveDrilldownController {
    private final ElliottWaveDrilldownService drilldownService;

    public ElliottWaveDrilldownController(ElliottWaveDrilldownService drilldownService) {
        this.drilldownService = drilldownService;
    }

    @GetMapping("/{symbol}/elliott-waves/drilldown")
    public ElliottWaveDrilldownService.DrilldownView drillDown(
            @PathVariable String symbol,
            @RequestParam String parentInterval,
            @RequestParam String parentLabel,
            @RequestParam long parentStart,
            @RequestParam long parentEnd,
            @RequestParam double parentStartPrice,
            @RequestParam double parentEndPrice,
            @RequestParam(required = false) Long asOfExclusive) {
        return drilldownService.drillDown(symbol, parentInterval, parentLabel,
                parentStart, parentEnd, parentStartPrice, parentEndPrice, asOfExclusive);
    }
}
