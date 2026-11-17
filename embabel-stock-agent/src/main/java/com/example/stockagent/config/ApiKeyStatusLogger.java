package com.example.stockagent.config;

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
        boolean deepRelay = present(environment.getProperty("DEEPRELAY_API_KEY"));
        boolean finnhubKey = finnhub.configured();
        log.info("Credentials loaded from .env/environment: DeepRelay API key present={}, Finnhub API key present={}",
                deepRelay, finnhubKey);
        if (!deepRelay || !finnhubKey) {
            log.warn("A key is missing. Add KEY=value lines to .env in the project root "
                    + "or export them as environment variables, then restart.");
        }
    }

    private boolean present(String value) {
        return value != null && !value.isBlank() && !value.equals("not-set");
    }
}
