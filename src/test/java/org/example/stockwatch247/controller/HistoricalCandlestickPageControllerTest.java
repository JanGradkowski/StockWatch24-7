package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.service.HistoricalCandlestickService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ConcurrentModel;

import java.security.Principal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoricalCandlestickPageControllerTest {

    @Test
    void detailPageRecalculatesSignalAndReturnsToTheSameResultsDialog() {
        UserRepository userRepository = mock(UserRepository.class);
        HistoricalCandlestickService service = mock(HistoricalCandlestickService.class);
        HistoricalCandlestickService.HistoricalSignal signal =
                mock(HistoricalCandlestickService.HistoricalSignal.class);
        HistoricalCandlestickService.HistoricalSignalChart chart =
                mock(HistoricalCandlestickService.HistoricalSignalChart.class);
        HistoricalCandlestickService.HistoricalSignalResults results =
                mock(HistoricalCandlestickService.HistoricalSignalResults.class);
        User user = new User();
        user.setFirstName("Jan");
        user.setEmail("jan@example.com");
        when(userRepository.findByEmailIgnoreCase("jan@example.com")).thenReturn(Optional.of(user));
        when(service.findSignal(
                "AAPL",
                "1d",
                1_750_000_000L,
                CandlePattern.BULLISH_ENGULFING,
                144
        ))
                .thenReturn(signal);
        when(service.chartForSignal(signal)).thenReturn(chart);
        when(service.resultsForSignal(signal, chart)).thenReturn(results);
        HistoricalCandlestickPageController controller =
                new HistoricalCandlestickPageController(userRepository, service);
        ConcurrentModel model = new ConcurrentModel();
        MockHttpServletResponse response = new MockHttpServletResponse();
        Principal principal = () -> "jan@example.com";

        String view = controller.historicalCandlestickDetail(
                "aapl",
                "1d",
                1_750_000_000L,
                "bullish_engulfing",
                144,
                principal,
                model,
                response
        );

        assertThat(view).isEqualTo("historical-candlestick-detail");
        assertThat(model.getAttribute("signal")).isSameAs(signal);
        assertThat(model.getAttribute("chart")).isSameAs(chart);
        assertThat(model.getAttribute("results")).isSameAs(results);
        assertThat(model.getAttribute("firstName")).isEqualTo("Jan");
        assertThat(model.getAttribute("returnUrl"))
                .isEqualTo("/stock/AAPL?historicalCandles=true&historicalInterval=1d&lookbackCandles=144");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        verify(service).findSignal(
                "AAPL",
                "1d",
                1_750_000_000L,
                CandlePattern.BULLISH_ENGULFING,
                144
        );
        verify(service).chartForSignal(signal);
        verify(service).resultsForSignal(signal, chart);
    }
}
