package com.example.bank.jobs;

import java.util.LinkedHashMap;
import java.util.Map;

import com.example.bank.jobs.JobDtos.JobResult;
import com.example.bank.jobs.JobDtos.JobRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Simple reminder job: writes a rich chat bubble and a notification. */
@Component
public class ReminderJobExecutor implements JobExecutor {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String type() {
        return "reminder";
    }

    @Override
    public void validate(String payload, String userId) {
        String text = text(payload);
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reminder payload needs a non-empty 'text'");
        }
        if (text.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reminder text is too long");
        }
    }

    @Override
    public JobResult execute(JobRow job) {
        String text = text(job.payload());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("text", text);
        String summary = "Reminder: " + (text.length() > 100 ? text.substring(0, 97) + "..." : text);
        String message = "⏰ **Reminder**\n\n" + text;
        return new JobResult(summary, message, JobService.json(detail));
    }

    private String text(String payload) {
        try {
            JsonNode node = mapper.readTree(payload == null ? "" : payload);
            JsonNode text = node.get("text");
            return text == null || text.isNull() ? "" : text.asText().strip();
        } catch (Exception ex) {
            return "";
        }
    }
}
