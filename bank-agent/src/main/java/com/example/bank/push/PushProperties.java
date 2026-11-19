package com.example.bank.push;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Remote push configuration. Disabled by default; Expo's push service needs no keys. */
@Component
public class PushProperties {

    private final boolean enabled;
    private final String url;

    public PushProperties(
            @Value("${bank.push.enabled:false}") boolean enabled,
            @Value("${bank.push.url:https://exp.host/--/api/v2/push/send}") String url) {
        this.enabled = enabled;
        this.url = url;
    }

    public boolean enabled() {
        return enabled;
    }

    public String url() {
        return url;
    }
}
