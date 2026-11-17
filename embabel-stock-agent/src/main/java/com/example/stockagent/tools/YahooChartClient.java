package com.example.stockagent.tools;

import com.example.stockagent.config.AppProperties;
import com.example.stockagent.domain.Candle;
import com.example.stockagent.domain.PriceHistory;
import tools.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Keyless Yahoo Finance chart client. Finnhub's free tier has no historical
 * candles, so this is the source for support/resistance and indicators.
 */
@Component
public class YahooChartClient {

    private static final Logger log = LoggerFactory.getLogger(YahooChartClient.class);

    private final AppProperties.Yahoo properties;
    private final RestClient restClient;
    private final TtlCache cache = new TtlCache();

    public YahooChartClient(AppProperties properties) {
        this.properties = properties.yahoo();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.yahoo().timeout());
        requestFactory.setReadTimeout(properties.yahoo().timeout());
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(properties.yahoo().baseUrl())
                .defaultHeader("User-Agent", "Mozilla/5.0 (compatible; stock-agent/1.0)")
                .defaultHeader("Accept", "application/json")
                .build();
    }

    public PriceHistory daily(String symbol) {
        return history(symbol, "1y", "1d");
    }

    public PriceHistory intraday(String symbol) {
        return history(symbol, "5d", "5m");
    }

    public PriceHistory history(String symbol, String range, String interval) {
        String key = "yahoo:" + symbol + ":" + range + ":" + interval;
        return cache.get(key, ttl(), () -> {
            try {
                JsonNode root = restClient.get()
                        .uri(uriBuilder -> uriBuilder.path("/{symbol}")
                                .queryParam("range", range)
                                .queryParam("interval", interval)
                                .queryParam("includeAdjustedClose", "true")
                                .build(symbol))
                        .retrieve()
                        .body(JsonNode.class);
                if (root == null) {
                    return empty(symbol, interval);
                }
                JsonNode result = root.path("chart").path("result");
                if (!result.isArray() || result.isEmpty()) {
                    return empty(symbol, interval);
                }
                JsonNode timestamps = result.get(0).path("timestamp");
                JsonNode quote = result.get(0).path("indicators").path("quote");
                if (!quote.isArray() || quote.isEmpty() || !timestamps.isArray()) {
                    return empty(symbol, interval);
                }
                JsonNode open = quote.get(0).path("open");
                JsonNode high = quote.get(0).path("high");
                JsonNode low = quote.get(0).path("low");
                JsonNode close = quote.get(0).path("close");
                JsonNode volume = quote.get(0).path("volume");

                List<Candle> candles = new ArrayList<>();
                for (int i = 0; i < timestamps.size(); i++) {
                    if (close.path(i).isNull() || high.path(i).isNull() || low.path(i).isNull()) {
                        continue;
                    }
                    candles.add(new Candle(
                            timestamps.path(i).asLong(),
                            open.path(i).asDouble(),
                            high.path(i).asDouble(),
                            low.path(i).asDouble(),
                            close.path(i).asDouble(),
                            volume.path(i).asLong()
                    ));
                }
                return new PriceHistory(symbol, interval, candles,
                        Instant.now().getEpochSecond(), "Yahoo Finance chart");
            } catch (Exception e) {
                log.warn("Yahoo chart request failed for {}: {}", symbol, e.getMessage());
                return empty(symbol, interval);
            }
        });
    }

    private PriceHistory empty(String symbol, String interval) {
        return new PriceHistory(symbol, interval, List.of(),
                Instant.now().getEpochSecond(), "Yahoo Finance chart (unavailable)");
    }

    private Duration ttl() {
        return properties.cacheTtl() == null ? Duration.ofMinutes(5) : properties.cacheTtl();
    }
}
