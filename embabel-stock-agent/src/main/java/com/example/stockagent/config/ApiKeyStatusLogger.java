package com.example.stockagent.config;

import java.util.Arrays;

import com.example.stockagent.tools.FinnhubClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Reports whether credentials were loaded, without ever printing their values. */
@Component
public class ApiKeyStatusLogger {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyStatusLogger.class);

    private final Environment environment;
    private final FinnhubClient finnhub;

    public ApiKeyStatusLogger(Environment environment, FinnhubClient finnhub) {
        this.environment = environment;
        this.finnhub = finnhub;
    }

    @PostConstruct
    public void report() {
        boolean azure = Arrays.stream(environment.getActiveProfiles()).anyMatch("azure"::equals)
                || Arrays.stream(environment.getDefaultProfiles()).anyMatch("azure"::equals);
        String provider = azure ? "azure" : "deeprelay";
        boolean aiKey = present(environment.getProperty("spring.ai.openai.api-key"));
        boolean finnhubKey = finnhub.configured();
        log.info("AI provider={}, AI API key present={}, Finnhub API key present={}",
                provider, aiKey, finnhubKey);
        if (!aiKey || !finnhubKey) {
            log.warn("A key is missing. Set the {} key for the active provider and FINNHUB_API_KEY "
                    + "(environment variables or .env), then restart.",
                    azure ? "AZURE_OPENAI_API_KEY" : "DEEPRELAY_API_KEY");
        }
    }

    private boolean present(String value) {
        return value != null && !value.isBlank() && !value.equals("not-set");
    }
}
