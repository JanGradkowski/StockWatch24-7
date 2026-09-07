package org.example.stockwatch247;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@SpringBootApplication
@EnableScheduling
public class StockWatch247Application {

    public static void main(String[] args) {
        SpringApplication.run(StockWatch247Application.class, args);
    }
    @Bean
    public RestTemplate restTemplate(@Value("${market-data.http.connect-timeout-seconds:10}") long connectTimeoutSeconds,
                                     @Value("${market-data.http.read-timeout-seconds:30}") long readTimeoutSeconds) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(Math.max(1L, connectTimeoutSeconds)));
        requestFactory.setReadTimeout(Duration.ofSeconds(Math.max(1L, readTimeoutSeconds)));
        return new RestTemplate(requestFactory);
    }

    @Bean
    public ObjectMapper objectMapper() {
        return tools.jackson.databind.json.JsonMapper.builder()
                .disable(tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    }
}
