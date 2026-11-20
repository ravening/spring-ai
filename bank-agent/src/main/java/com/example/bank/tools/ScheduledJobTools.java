package com.example.bank.tools;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.example.bank.core.PromptSafety;
import com.example.bank.jobs.JobDtos.CreateJobRequest;
import com.example.bank.jobs.JobDtos.JobDetails;
import com.example.bank.jobs.JobDtos.JobDto;
import com.example.bank.jobs.JobService;
import com.example.bank.ratelimit.RateLimitProperties;
import com.example.bank.ratelimit.RateLimiter;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** Agent tools for scheduling and inspecting jobs, scoped to the current chat/user. */
@Component
public class ScheduledJobTools {

    private final JobService jobs;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties rateLimitProperties;

    public ScheduledJobTools(JobService jobs, RateLimiter rateLimiter, RateLimitProperties rateLimitProperties) {
        this.jobs = jobs;
        this.rateLimiter = rateLimiter;
        this.rateLimitProperties = rateLimitProperties;
    }

    @Tool(name = "schedule_job", description = """
            Schedule a job to run later or repeatedly. type is 'reminder' (needs text) or
            'transfer' (needs fromAccountId, toUserId, toAccountId, amount, optional currency/note).
            Provide exactly one schedule: runAt (ISO-8601), delayMinutes, cron (6 fields) or
            intervalMinutes. The conversationId and userId are injected automatically.""")
    public String scheduleJob(
            @ToolParam(description = "Job type: reminder or transfer", required = true) String type,
            @ToolParam(description = "Reminder text (for type=reminder)", required = false) String text,
            @ToolParam(description = "Source account id (for type=transfer)", required = false) String fromAccountId,
            @ToolParam(description = "Destination user id (for type=transfer)", required = false) String toUserId,
            @ToolParam(description = "Destination account id (for type=transfer)", required = false) String toAccountId,
            @ToolParam(description = "Amount to transfer (for type=transfer)", required = false) String amount,
            @ToolParam(description = "Currency for the transfer", required = false) String currency,
            @ToolParam(description = "Optional payment reference", required = false) String note,
            @ToolParam(description = "ISO-8601 instant for a one-shot job", required = false) String runAt,
            @ToolParam(description = "Minutes from now for a one-shot job", required = false) Integer delayMinutes,
            @ToolParam(description = "6-field cron expression for a recurring job", required = false) String cron,
            @ToolParam(description = "Repeat interval in minutes", required = false) Integer intervalMinutes,
            @ToolParam(description = "IANA timezone", required = false) String timezone,
            @ToolParam(description = "Injected from the tool context; do not set manually.", required = false) String conversationId,
            @ToolParam(description = "Injected from the tool context; do not set manually.", required = false) String userId) {
        try {
            String normalised = type == null ? "" : type.trim().toLowerCase();
            Map<String, Object> payload = new LinkedHashMap<>();
            if ("reminder".equals(normalised)) {
                payload.put("text", text);
            } else if ("transfer".equals(normalised)) {
                payload.put("fromAccountId", fromAccountId);
                payload.put("toUserId", toUserId);
                payload.put("toAccountId", toAccountId);
                payload.put("amount", amount);
                payload.put("currency", currency);
                payload.put("note", note);
            } else {
                return PromptSafety.data("Unknown job type '" + type + "'. Use reminder or transfer.");
            }
            rateLimiter.check("jobs", userId, rateLimitProperties.jobsPerMinute());
            CreateJobRequest request = new CreateJobRequest(userId, conversationId, normalised,
                    JobService.json(payload), runAt, delayMinutes, cron, intervalMinutes, timezone);
            JobDto job = jobs.createForUser(userId, request);
            String when = job.runAt() == null ? "unknown" : Instant.ofEpochMilli(job.runAt()).toString();
            return PromptSafety.data("Job scheduled.\n"
                    + "Job id: " + job.id() + "\n"
                    + "Type: " + job.type() + "\n"
                    + "Status: " + job.status() + "\n"
                    + "Next run (UTC): " + when);
        } catch (Exception ex) {
            return PromptSafety.data("Could not schedule job: "
                    + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        }
    }

    @Tool(name = "list_jobs", description = "List the current user's scheduled jobs, optionally filtered by status (PENDING/RUNNING/PAUSED/DONE/FAILED/CANCELLED). The userId is injected automatically.")
    public String listJobs(
            @ToolParam(description = "Optional status filter", required = false) String status,
            @ToolParam(description = "Injected from the tool context; do not set manually.", required = false) String userId) {
        try {
            var list = jobs.listForUser(userId, null, status);
            if (list.isEmpty()) {
                return PromptSafety.data("No scheduled jobs found.");
            }
            StringBuilder out = new StringBuilder("Scheduled jobs:\n");
            for (JobDto job : list) {
                out.append("- ").append(job.id()).append(" [").append(job.type()).append("/")
                        .append(job.status()).append("] next run ")
                        .append(job.runAt() == null ? "n/a" : Instant.ofEpochMilli(job.runAt()).toString())
                        .append('\n');
            }
            return PromptSafety.data(out.toString().stripTrailing());
        } catch (Exception ex) {
            return PromptSafety.data("Could not list jobs: " + ex.getMessage());
        }
    }

    @Tool(name = "cancel_job", description = "Cancel one of the current user's scheduled jobs. The userId is injected automatically.")
    public String cancelJob(
            @ToolParam(description = "Job id to cancel", required = true) String jobId,
            @ToolParam(description = "Injected from the tool context; do not set manually.", required = false) String userId) {
        try {
            jobs.cancelForUser(userId, jobId);
            return PromptSafety.data("Job " + jobId + " cancelled.");
        } catch (Exception ex) {
            return PromptSafety.data("Could not cancel job: " + ex.getMessage());
        }
    }

    @Tool(name = "job_details", description = "Show details and recent runs of one of the current user's scheduled jobs. The userId is injected automatically.")
    public String jobDetails(
            @ToolParam(description = "Job id to inspect", required = true) String jobId,
            @ToolParam(description = "Injected from the tool context; do not set manually.", required = false) String userId) {
        try {
            JobDetails details = jobs.getForUser(userId, jobId);
            JobDto job = details.job();
            return PromptSafety.data("Job " + job.id() + "\n"
                    + "Type: " + job.type() + "\nStatus: " + job.status() + "\n"
                    + "Runs: " + job.runCount() + "\nLast result: " + job.lastResult() + "\n"
                    + "Recent runs: " + details.runs().size());
        } catch (Exception ex) {
            return PromptSafety.data("Could not load job details: " + ex.getMessage());
        }
    }
}
