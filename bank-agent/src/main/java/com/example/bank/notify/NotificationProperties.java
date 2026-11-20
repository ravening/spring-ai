package com.example.bank.notify;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Notification inbox configuration (config gate kept alongside the push config). */
@Component
public class NotificationProperties {

    private final int pageSize;

    public NotificationProperties(@Value("${bank.notifications.page-size:100}") int pageSize) {
        this.pageSize = pageSize < 1 ? 100 : Math.min(pageSize, 500);
    }

    public int pageSize() {
        return pageSize;
    }
}
