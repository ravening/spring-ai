package com.example.bank.jobs;

import java.util.List;

/** Wire types and internal records for scheduled jobs. */
public final class JobDtos {

    private JobDtos() {
    }

    public record JobRow(String id, String userId, String conversationId, String type, String payload,
            String status, Long runAt, String cron, Integer intervalMinutes, String timezone,
            int attempts, int maxAttempts, int runCount, Long lastRunAt, String lastResult,
            Long leaseUntil, long createdAt, long updatedAt) {
    }

    public record JobDto(String id, String userId, String conversationId, String type, String payload,
            String status, Long runAt, String cron, Integer intervalMinutes, String timezone,
            int runCount, Long lastRunAt, String lastResult, long createdAt) {
    }

    public record JobRunDto(String id, String jobId, String idempotencyKey, Long scheduledFor,
            Long startedAt, Long finishedAt, String status, String result) {
    }

    public record JobDetails(JobDto job, List<JobRunDto> runs) {
    }

    public record JobResult(String summary, String message, String detail) {
    }

    public record ClaimedJob(JobRow job, long scheduledFor) {
    }

    public record CreateJobRequest(String userId, String conversationId, String type, String payload,
            String runAt, Integer delayMinutes, String cron, Integer intervalMinutes, String timezone) {
    }

    public record UpdateJobRequest(String userId, String payload, String runAt, Integer delayMinutes,
            String cron, Integer intervalMinutes, String timezone, String status) {
    }
}
