package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.service.HistoricalElliottWaveService;
import org.example.stockwatch247.service.SignalScoringPreferencesService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ConcurrentModel;

import java.security.Principal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoricalElliottWavePageControllerTest {

    @Test
    void opensCachedHistoricalWaveDetailByCycleStageAndEndpoint() {
        UserRepository userRepository = mock(UserRepository.class);
        HistoricalElliottWaveService service = mock(HistoricalElliottWaveService.class);
        HistoricalElliottWaveService.HistoricalElliottWaveDetail detail =
                mock(HistoricalElliottWaveService.HistoricalElliottWaveDetail.class);
        User user = new User();
        user.setFirstName("Jan");
        user.setEmail("jan@example.com");
        when(userRepository.findByEmailIgnoreCase("jan@example.com")).thenReturn(Optional.of(user));
        when(service.findDetail(
                "MARA",
                "1d",
                ElliottSignalStage.WAVE_V_END,
                1_515_974_400L,
                "BULLISH:1:2:3:4:5"
        )).thenReturn(detail);
        HistoricalElliottWavePageController controller =
                new HistoricalElliottWavePageController(userRepository, service);
        ConcurrentModel model = new ConcurrentModel();
        MockHttpServletResponse response = new MockHttpServletResponse();
        Principal principal = () -> "jan@example.com";

        String view = controller.historicalElliottWaveDetail(
                "mara",
                "1d",
                ElliottSignalStage.WAVE_V_END,
                1_515_974_400L,
                "BULLISH:1:2:3:4:5",
                principal,
                model,
                response
        );

        assertThat(view).isEqualTo("historical-elliott-detail");
        assertThat(model.getAttribute("wave")).isSameAs(detail);
        assertThat(model.getAttribute("firstName")).isEqualTo("Jan");
        assertThat(model.getAttribute("returnUrl")).isEqualTo("/stock/MARA#general");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        verify(service).findDetail(
                "MARA",
                "1d",
                ElliottSignalStage.WAVE_V_END,
                1_515_974_400L,
                "BULLISH:1:2:3:4:5"
        );
    }

    @Test
    void opensPatternCandidateDetailWithItsOwnUnweightedScore() {
        UserRepository userRepository = mock(UserRepository.class);
        HistoricalElliottWaveService service = mock(HistoricalElliottWaveService.class);
        HistoricalElliottWaveService.HistoricalElliottWaveDetail detail =
                mock(HistoricalElliottWaveService.HistoricalElliottWaveDetail.class);
        when(detail.candidate()).thenReturn(true);
        when(detail.qualityScore()).thenReturn(81);
        when(service.findCandidateDetail("MARA", "1d", 1_515_974_400L, "FLAT:1-2-3-4", null)).thenReturn(detail);
        HistoricalElliottWavePageController controller =
                new HistoricalElliottWavePageController(userRepository, service);
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.historicalElliottCandidateDetail(
                "mara", "1d", 1_515_974_400L, "FLAT:1-2-3-4", () -> "jan@example.com",
                model, new MockHttpServletResponse());

        assertThat(view).isEqualTo("historical-elliott-detail");
        assertThat(model.getAttribute("wave")).isSameAs(detail);
        assertThat(model.getAttribute("displayScore"))
                .isInstanceOfSatisfying(SignalScoringPreferencesService.DisplayScore.class, score -> {
                    assertThat(score.score()).isEqualTo(81);
                    assertThat(score.strengthLabel()).isEqualTo("Moderate fit");
                    assertThat(score.customApplied()).isFalse();
                });
    }
}
