package com.example.bank.notify;

import com.example.bank.auth.AuthSupport;
import com.example.bank.notify.NotificationDtos.NotificationPage;
import com.example.bank.notify.NotificationDtos.UnreadCount;
import com.example.bank.users.UserDirectory;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Notification inbox REST: cursor polling, unread state and per-user ownership. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationStore store;
    private final NotificationProperties properties;
    private final UserDirectory users;

    public NotificationController(NotificationStore store, NotificationProperties properties, UserDirectory users) {
        this.store = store;
        this.properties = properties;
        this.users = users;
    }

    @GetMapping
    public NotificationPage list(HttpServletRequest http,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "0") long sinceId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(defaultValue = "false") boolean unreadOnly) {
        String user = resolve(http, userId);
        int pageSize = limit == null || limit < 1 ? properties.pageSize() : Math.min(limit, 500);
        return new NotificationPage(store.unreadCount(user), store.list(user, sinceId, pageSize, unreadOnly));
    }

    @GetMapping("/unread-count")
    public UnreadCount unreadCount(HttpServletRequest http, @RequestParam(required = false) String userId) {
        return new UnreadCount(store.unreadCount(resolve(http, userId)));
    }

    @PostMapping("/{id}/read")
    public ReadResponse read(HttpServletRequest http, @PathVariable long id,
            @RequestParam(required = false) String userId) {
        String user = resolve(http, userId);
        if (!store.existsForUser(id, user)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found");
        }
        store.markRead(id, user, System.currentTimeMillis());
        return new ReadResponse(id, true);
    }

    @PostMapping("/read-all")
    public ReadAllResponse readAll(HttpServletRequest http, @RequestParam(required = false) String userId) {
        return new ReadAllResponse(store.markAllRead(resolve(http, userId), System.currentTimeMillis()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(HttpServletRequest http, @PathVariable long id,
            @RequestParam(required = false) String userId) {
        String user = resolve(http, userId);
        if (!store.delete(id, user)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found");
        }
    }

    private String resolve(HttpServletRequest http, String supplied) {
        return users.resolve(AuthSupport.currentUserId(http, supplied)).id();
    }

    public record ReadResponse(long id, boolean read) {
    }

    public record ReadAllResponse(int updated) {
    }
}
