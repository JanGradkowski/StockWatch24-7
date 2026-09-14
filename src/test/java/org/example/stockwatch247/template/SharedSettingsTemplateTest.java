package org.example.stockwatch247.template;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserSignalScoringPreferencesRepository;
import org.example.stockwatch247.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SharedSettingsTemplateTest {
    @Test void settingsRenderOneControlPerParameterWithoutIntervalSpecificInputs() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        var request = new MockHttpServletRequest();
        var app = JakartaServletWebApplication.buildApplication(request.getServletContext());
        var context = new WebContext(app.buildExchange(request, new MockHttpServletResponse()));
        context.setVariable("preferences", AnalysisPreferencesService.factoryPreferences());
        String analysis = engine.process("fragments/analysis-settings", context);
        assertThat(analysis).containsOnlyOnce("name=\"shared.rsiPeriod\"")
                .containsOnlyOnce("name=\"email.signals\"");
        String detection = engine.process("fragments/detection-settings", context);
        assertThat(detection).containsOnlyOnce("name=\"shared.trendMinimumMovePercent\"");

        context.setVariable("preferences", ElliottWavePreferencesService.factoryPreferences());
        String elliott = engine.process("fragments/elliott-wave-settings", context);
        assertThat(elliott).containsOnlyOnce("name=\"shared.waveTwoMaximumPercent\"");

        var candles = CandlestickPatternPreferencesService.factoryPreferences();
        context.setVariable("preferences", candles);
        context.setVariable("candlestickRewardRiskProfiles", candles.rewardRiskProfiles());
        context.setVariable("candlestickCircuitBreakerProfiles", candles.circuitBreakerProfiles());
        context.setVariable("trendRequirements", CandlestickPatternPreferencesService.TrendRequirement.values());
        context.setVariable("stopLossModes", CandlestickPatternPreferencesService.StopLossMode.values());
        String candlestick = engine.process("fragments/candlestick-pattern-settings", context);
        assertThat(candlestick).containsOnlyOnce("name=\"rewardRisk.shared\"")
                .containsOnlyOnce("name=\"circuitBreaker.shared.atrPeriod\"");

        var scoring = new SignalScoringPreferencesService(mock(UserSignalScoringPreferencesRepository.class),
                tools.jackson.databind.json.JsonMapper.builder().build()).get(new User());
        context.setVariable("preferences", scoring);
        String scores = engine.process("fragments/scoring-settings", context);
        assertThat(scores).containsOnlyOnce("name=\"candlestick.patternQuality.points\"")
                .containsOnlyOnce("name=\"elliott.structure.points\"")
                .containsOnlyOnce("name=\"harmonic.primaryB.points\"");
        assertThat(analysis + detection + elliott + candlestick + scores)
                .doesNotContain("name=\"daily.", "name=\"weekly.", "name=\"monthly.",
                        "name=\"interval\"", "Reset this interval", "stored separately",
                        "name=\"email.daily\"", "name=\"email.weekly\"", "name=\"email.monthly\"");
    }
}
