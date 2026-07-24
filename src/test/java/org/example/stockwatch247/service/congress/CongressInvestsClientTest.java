package org.example.stockwatch247.service.congress;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.stockwatch247.model.enums.CongressionalTradeType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CongressInvestsClientTest {

    @Test
    void parsesRelevantPurchasesAndSalesAndRejectsUnsafeFilingLinks() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        CongressionalProviderRequestBudget budget = mock(CongressionalProviderRequestBudget.class);
        CongressInvestsClient client = new CongressInvestsClient(
                restTemplate,
                new ObjectMapper(),
                budget,
                true,
                "https://congress.example.test/",
                500,
                1,
                500,
                90,
                "");
        server.expect(requestTo("https://congress.example.test/trades/AAPL?limit=500&offset=0"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "last_updated": "2026-07-23T10:00:00Z",
                          "has_more": false,
                          "trades": [
                            {
                              "member": "Example Senator",
                              "chamber": "Senate",
                              "ticker": "aapl",
                              "trade_type": "buy",
                              "amount": "$1,001 - $15,000",
                              "tx_date": "2026-07-01",
                              "disclosed": "2026-07-20",
                              "asset": "Apple Inc. - Common Stock",
                              "link": "https://efdsearch.senate.gov/search/view/ptr/example"
                            },
                            {
                              "member": "Example Representative",
                              "chamber": "House",
                              "ticker": "AAPL",
                              "trade_type": "sale",
                              "amount": "$15,001 - $50,000",
                              "tx_date": "2026-06-29",
                              "disclosed": "2026-07-18",
                              "asset": "Apple Inc.",
                              "link": "https://attacker.example/phishing"
                            },
                            {
                              "member": "Ignored Member",
                              "chamber": "House",
                              "ticker": "AAPL",
                              "trade_type": "exchange",
                              "amount": "$1,001 - $15,000",
                              "tx_date": "2026-06-01",
                              "disclosed": "2026-06-10"
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        var batch = client.fetchTickerHistory("aapl");

        assertThat(batch.trades()).hasSize(2);
        assertThat(batch.trades().getFirst().transactionType())
                .isEqualTo(CongressionalTradeType.PURCHASE);
        assertThat(batch.trades().getFirst().sourceUrl())
                .startsWith("https://efdsearch.senate.gov/");
        assertThat(batch.trades().get(1).transactionType())
                .isEqualTo(CongressionalTradeType.SALE);
        assertThat(batch.trades().get(1).sourceUrl()).isNull();
        assertThat(batch.providerUpdatedAt()).hasToString("2026-07-23T10:00:00Z");
        verify(budget).consume(CongressInvestsClient.PROVIDER_NAME, 90);
        server.verify();
    }

    @Test
    void globalPollUsesOneFreeTierFeedRequest() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        CongressionalProviderRequestBudget budget = mock(CongressionalProviderRequestBudget.class);
        CongressInvestsClient client = new CongressInvestsClient(
                restTemplate,
                new ObjectMapper(),
                budget,
                true,
                "https://congress.example.test",
                500,
                3,
                500,
                90,
                "");
        server.expect(requestTo("https://congress.example.test/trades?limit=500"))
                .andRespond(withSuccess("""
                        {"has_more": false, "trades": []}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.fetchRecentTrades().trades()).isEmpty();

        verify(budget).consume(CongressInvestsClient.PROVIDER_NAME, 90);
        server.verify();
    }

    @Test
    void optionalPaidPlanKeyIsSentOnlyAsARequestHeader() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        CongressionalProviderRequestBudget budget = mock(CongressionalProviderRequestBudget.class);
        CongressInvestsClient client = new CongressInvestsClient(
                restTemplate,
                new ObjectMapper(),
                budget,
                true,
                "https://congress.example.test",
                500,
                1,
                500,
                1_000,
                "paid-test-key");
        server.expect(requestTo("https://congress.example.test/trades?limit=500"))
                .andExpect(header("X-Api-Key", "paid-test-key"))
                .andRespond(withSuccess("""
                        {"has_more": false, "trades": []}
                        """, MediaType.APPLICATION_JSON));

        client.fetchRecentTrades();

        verify(budget).consume(CongressInvestsClient.PROVIDER_NAME, 1_000);
        server.verify();
    }
}
