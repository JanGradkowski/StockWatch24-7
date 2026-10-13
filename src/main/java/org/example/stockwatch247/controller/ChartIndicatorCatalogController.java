package org.example.stockwatch247.controller;

import org.example.stockwatch247.service.chartindicator.ChartIndicatorCatalog;
import org.example.stockwatch247.service.chartindicator.FormulaIndicator;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/** The indicator catalog the chart builds its indicator menu, settings dialog and legend from. */
@RestController
@RequestMapping("/api/chart-indicators")
public class ChartIndicatorCatalogController {
    private final ChartIndicatorCatalog catalog;

    public ChartIndicatorCatalogController(ChartIndicatorCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/catalog")
    public ResponseEntity<CatalogView> catalog() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(new CatalogView(catalog.all(), FormulaIndicator.functions(), FormulaIndicator.series()));
    }

    public record CatalogView(List<IndicatorDefinition> indicators, List<String> formulaFunctions, List<String> formulaSeries) { }
}
