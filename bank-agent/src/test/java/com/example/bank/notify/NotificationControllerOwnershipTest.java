package com.example.bank.notify;

import java.util.UUID;

import com.example.bank.users.UserDirectory;
import com.example.bank.web.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationControllerOwnershipTest {

    private NotificationStore store;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:nc_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        store = new NotificationStore(new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")));
        store.createTable();
        mvc = MockMvcBuilders
                .standaloneSetup(new NotificationController(store, new NotificationProperties(100), new UserDirectory()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private long aliceNotification() {
        return store.insert("alice", "conv-a", "job_result", "Job completed", "All done", "{}", "j1", "k1", 1);
    }

    @Test
    void listIsScopedToTheRequestingUser() throws Exception {
        long id = aliceNotification();
        mvc.perform(get("/api/notifications").param("userId", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id));
        mvc.perform(get("/api/notifications").param("userId", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void bobCannotReadOrDeleteAliceNotification() throws Exception {
        long id = aliceNotification();
        mvc.perform(post("/api/notifications/{id}/read", id).param("userId", "bob"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/notifications/{id}", id).param("userId", "bob"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/notifications/{id}/read", id).param("userId", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));
        mvc.perform(get("/api/notifications/unread-count").param("userId", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void readAllAndCursorRespectUser() throws Exception {
        aliceNotification();
        store.insert("alice", "conv-a", "answer_ready", "Answer ready", "Done", null, null, "k2", 2);
        store.insert("bob", "conv-b", "answer_ready", "Answer ready", "Done", null, null, "k3", 3);

        mvc.perform(post("/api/notifications/read-all").param("userId", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(2));
        mvc.perform(get("/api/notifications/unread-count").param("userId", "bob"))
                .andExpect(jsonPath("$.count").value(1));
        mvc.perform(get("/api/notifications").param("userId", "alice").param("unreadOnly", "true"))
                .andExpect(jsonPath("$.items.length()").value(0));
    }
}
