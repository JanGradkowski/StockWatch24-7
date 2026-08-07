package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.service.HistoricalElliottWaveService;
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
                "1wk",
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
                "1wk",
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
                "1wk",
                ElliottSignalStage.WAVE_V_END,
                1_515_974_400L,
                "BULLISH:1:2:3:4:5"
        );
    }
}
