package org.example.stockwatch247.service;

import org.example.stockwatch247.model.EnrichedCandle;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PrecisionPatternDetectionService {
    private final CandlePatternDetectionService detectionService;

    public PrecisionPatternDetectionService(CandlePatternDetectionService detectionService) {
        this.detectionService = detectionService;
    }

    public List<CandlePatternDetectionService.DetectedSignal> detect(List<EnrichedCandle> recentCandles) {
        if (recentCandles == null || recentCandles.isEmpty()) {
            return List.of();
        }
        return detectionService.detect(recentCandles);
    }
}
