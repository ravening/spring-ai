package com.example.stockagent.tools;

import com.example.stockagent.config.AppProperties;
import com.example.stockagent.domain.FundamentalResearch;
import com.example.stockagent.domain.NewsItem;
import com.example.stockagent.domain.PriceQuote;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/** Finnhub REST client: real-time quote, news, earnings and dividends. */
@Component
public class FinnhubClient {

    private static final Logger log = LoggerFactory.getLogger(FinnhubClient.class);

    private final AppProperties.Finnhub properties;
    private final RestClient restClient;
    private final TtlCache cache = new TtlCache();

    public FinnhubClient(AppProperties properties) {
        this.properties = properties.finnhub();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(this.properties.timeout());
        requestFactory.setReadTimeout(this.properties.timeout());
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl())
                .defaultHeader("X-Finnhub-Token", apiKey())
                .defaultHeader("Accept", "application/json")
                .build();
    }

    private String apiKey() {
        return properties.apiKey() == null ? "not-set" : properties.apiKey();
    }

    private String baseUrl() {
        return properties.baseUrl() == null ? "https://finnhub.io/api/v1" : properties.baseUrl();
    }

    public boolean configured() {
        return !apiKey().isBlank() && !apiKey().equals("not-set");
    }

    /** Cached GET that degrades gracefully to an empty JSON node. */
    private JsonNode cached(String key, Supplier<JsonNode> loader) {
        return cache.get(key, cacheTtl(), () -> {
            if (!configured()) {
                return JsonNodeFactory.instance.objectNode();
            }
            try {
                JsonNode body = loader.get();
                return body == null ? JsonNodeFactory.instance.objectNode() : body;
            } catch (Exception e) {
                log.warn("Finnhub request failed for {}: {}", key, e.getMessage());
                return JsonNodeFactory.instance.objectNode();
            }
        });
    }

    private JsonNode get(String key, String path, Object... queryParams) {
        return cached(key, () -> restClient.get()
                .uri(uriBuilder -> {
                    var spec = uriBuilder.path(path);
                    for (int i = 0; i < queryParams.length; i += 2) {
                        spec.queryParam(queryParams[i].toString(), queryParams[i + 1]);
                    }
                    return spec.build();
                })
                .retrieve()
                .body(JsonNode.class));
    }

    private Duration cacheTtl() {
        return properties.cacheTtl() == null ? Duration.ofSeconds(30) : properties.cacheTtl();
    }

    public PriceQuote quote(String symbol) {
        JsonNode node = get("quote:" + symbol, "/quote", "symbol", symbol);
        double price = node.path("c").asDouble(0);
        return new PriceQuote(
                symbol, price,
                node.path("d").asDouble(0),
                node.path("dp").asDouble(0),
                node.path("h").asDouble(0),
                node.path("l").asDouble(0),
                node.path("o").asDouble(0),
                node.path("pc").asDouble(0),
                node.path("t").asLong(Instant.now().getEpochSecond()),
                configured() ? "Finnhub real-time" : "Finnhub unavailable (set FINNHUB_API_KEY)"
        );
    }

    public List<NewsItem> news(String symbol, int days) {
        JsonNode node = get("news:" + symbol + ":" + days, "/news",
                "symbol", symbol,
                "from", LocalDate.now().minusDays(days).toString(),
                "to", LocalDate.now().plusDays(1).toString());
        List<NewsItem> items = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                items.add(new NewsItem(
                        item.path("headline").asText(""),
                        item.path("source").asText(""),
                        item.path("url").asText(""),
                        item.path("datetime").asLong(0),
                        item.path("summary").asText("")
                ));
            }
        }
        items.sort(Comparator.comparingLong(NewsItem::publishedAtEpochSeconds).reversed());
        return items;
    }

    public FundamentalResearch fundamentals(String symbol) {
        JsonNode profile = get("profile:" + symbol, "/stock/profile2", "symbol", symbol);
        JsonNode calendar = get("earnings:" + symbol, "/calendar/earnings",
                "symbol", symbol,
                "from", LocalDate.now().minusDays(1).toString(),
                "to", LocalDate.now().plusMonths(6).toString());
        JsonNode dividends = get("dividend:" + symbol, "/dividend",
                "symbol", symbol,
                "from", LocalDate.now().minusYears(1).toString());

        String earningsDate = "";
        String epsEstimate = "";
        String earningsHour = "";
        String earningsQuarter = "";
        long best = Long.MAX_VALUE;
        if (calendar.has("earningsCalendar") && calendar.path("earningsCalendar").isArray()) {
            for (JsonNode e : calendar.path("earningsCalendar")) {
                try {
                    LocalDate date = LocalDate.parse(e.path("date").asText());
                    long epoch = date.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
                    if (!date.isBefore(LocalDate.now()) && epoch < best) {
                        best = epoch;
                        earningsDate = date.toString();
                        epsEstimate = e.path("epsEstimate").asText("");
                        earningsHour = e.path("hour").asText("");
                        earningsQuarter = "Q" + e.path("quarter").asText("") + " FY" + e.path("year").asText("");
                    }
                } catch (DateTimeParseException ignored) {
                    // ignore malformed dates from the provider
                }
            }
        }

        String dividendPerShare = "";
        String exDividendDate = "";
        if (dividends.isArray() && !dividends.isEmpty()) {
            JsonNode latest = dividends.get(0);
            dividendPerShare = latest.path("amount").asText("");
            exDividendDate = latest.path("exDate").asText("");
        }

        return new FundamentalResearch(
                symbol,
                profile.path("name").asText(""),
                profile.path("finnhubIndustry").asText(""),
                earningsDate,
                earningsHour,
                earningsQuarter,
                epsEstimate,
                dividendPerShare,
                "",
                exDividendDate,
                profile.path("marketCapitalization").asText(""),
                profile.path("description").asText(""),
                Instant.now().getEpochSecond()
        );
    }
}
