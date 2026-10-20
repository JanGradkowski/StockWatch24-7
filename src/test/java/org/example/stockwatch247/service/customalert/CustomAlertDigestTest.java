package org.example.stockwatch247.service.customalert;

import org.example.stockwatch247.i18n.AppLanguage;
import org.example.stockwatch247.i18n.ServerMessages;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** One digest email per check: a stock that caught up on missed candles shows its latest trigger and the earlier dates. */
class CustomAlertDigestTest {
    private static Map<String, Object> event(String symbol, String company, LocalDate day, double close) {
        Map<String, Object> event = new java.util.HashMap<>();
        event.put("ticker_symbol", symbol);
        event.put("company_name", company);
        event.put("candle_timestamp", day.atStartOfDay(ZoneOffset.UTC).toEpochSecond());
        event.put("close_price", close);
        event.put("matched_conditions", 1);
        event.put("total_conditions", 1);
        event.put("rule_text", "- Close price is above 1");
        return event;
    }

    @Test
    void groupsTriggersByStockNewestFirst() {
        List<Map<String, Object>> events = new ArrayList<>();
        // As the digest query orders them: by stock, newest candle first.
        for (int day = 16; day >= 5; day--) events.add(event("INTC", "INTC", LocalDate.of(2026, 10, day), 90 + day));
        events.add(event("NVDA", "NVIDIA Corp.", LocalDate.of(2026, 10, 16), 214.13));
        String body = CustomAlertEngine.digestBody("Every close", TimeInterval.DAILY, events);

        assertThat(body).contains("INTC\nCompleted candle: 16 Oct 2026\nClose: 106\n")
                .as("a company name that only repeats the symbol is left out").doesNotContain("INTC · INTC")
                .contains("Earlier triggers (11): 15 Oct 2026, 14 Oct 2026, 13 Oct 2026, 12 Oct 2026, 11 Oct 2026, "
                        + "10 Oct 2026, 9 Oct 2026, 8 Oct 2026, …\n")
                .contains("NVDA · NVIDIA Corp.\nCompleted candle: 16 Oct 2026\nClose: 214.13\nConditions met: 1 of 1\n\n")
                .doesNotContain("And ");
        assertThat(ServerMessages.translateLines(body, AppLanguage.PL.locale()))
                .contains("Wcześniejsze wyzwolenia (11): ").doesNotContain("Earlier triggers");
    }

    @Test
    void listsAtMostTwentyFiveStocks() {
        List<Map<String, Object>> events = new ArrayList<>();
        for (int stock = 0; stock < 30; stock++) events.add(event("S" + (100 + stock), null, LocalDate.of(2026, 10, 16), 10));
        String body = CustomAlertEngine.digestBody("Wide", TimeInterval.DAILY, events);
        assertThat(body).contains("S124\n").doesNotContain("S125\n").contains("And 5 more\n");
    }
}
