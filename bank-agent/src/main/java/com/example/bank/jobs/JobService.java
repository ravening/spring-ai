package com.example.bank.jobs;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.example.bank.auth.AuthSupport;
import com.example.bank.chat.ConversationService;
import com.example.bank.jobs.JobDtos.ClaimedJob;
import com.example.bank.jobs.JobDtos.CreateJobRequest;
import com.example.bank.jobs.JobDtos.JobDetails;
import com.example.bank.jobs.JobDtos.JobDto;
import com.example.bank.jobs.JobDtos.JobResult;
import com.example.bank.jobs.JobDtos.JobRow;
import com.example.bank.jobs.JobDtos.JobRunDto;
import com.example.bank.jobs.JobDtos.UpdateJobRequest;
import com.example.bank.notify.NotificationDtos.NotificationEvent;
import com.example.bank.notify.NotificationService;
import com.example.bank.users.UserDirectory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * CRUD + ownership + durable claim/execution state machine for scheduled jobs.
 * Uses only JdbcTemplate (no Quartz/JPA): a single-server poller atomically claims
 * due rows, each occurrence writes one idempotent job_runs row, and results are
 * emitted through NotificationService so chat message and inbox row never diverge.
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final JobProperties properties;
    private final UserDirectory users;
    private final ConversationService conversations;
    private final NotificationService notifications;
    private final List<JobExecutor> executors;

    private record Schedule(String cron, Integer intervalMinutes, long runAt) {
    }

    private record RunState(String status, String result) {
    }

    public JobService(JdbcTemplate jdbc, JobProperties properties, UserDirectory users,
            ConversationService conversations, NotificationService notifications, List<JobExecutor> executors) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.users = users;
        this.conversations = conversations;
        this.notifications = notifications;
        this.executors = executors;
    }

    @PostConstruct
    void createTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS scheduled_jobs (
                    id VARCHAR(64) PRIMARY KEY,
                    user_id VARCHAR(64) NOT NULL,
                    conversation_id VARCHAR(64) NOT NULL,
                    type VARCHAR(32) NOT NULL,
                    payload CLOB NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    run_at BIGINT,
                    cron VARCHAR(64),
                    interval_minutes INT,
                    timezone VARCHAR(64),
                    attempts INT NOT NULL DEFAULT 0,
                    max_attempts INT NOT NULL DEFAULT 3,
                    run_count INT NOT NULL DEFAULT 0,
                    last_run_at BIGINT,
                    last_result CLOB,
                    lease_until BIGINT,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_jobs_status_runat ON scheduled_jobs (status, run_at)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_jobs_user ON scheduled_jobs (user_id)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS job_runs (
                    id VARCHAR(64) PRIMARY KEY,
                    job_id VARCHAR(64) NOT NULL,
                    idempotency_key VARCHAR(160) NOT NULL UNIQUE,
                    scheduled_for BIGINT NOT NULL,
                    started_at BIGINT,
                    finished_at BIGINT,
                    status VARCHAR(16) NOT NULL,
                    result CLOB
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_job_runs_job ON job_runs (job_id)");
    }

    // ---------------------------------------------------------------- CRUD

    public JobDto create(HttpServletRequest http, CreateJobRequest request) {
        return createForUser(users.resolve(AuthSupport.currentUserId(http, request.userId())).id(), request);
    }

    /** Entry point for agent tools, where the userId comes from the injected tool context. */
    public JobDto createForUser(String userId, CreateJobRequest request) {
        users.resolve(userId);
        conversations.ensureOwnership(request.conversationId(), userId);
        String type = normaliseType(request.type());
        JobExecutor executor = executorFor(type);
        executor.validate(request.payload(), userId);

        String timezone = request.timezone() == null || request.timezone().isBlank()
                ? properties.defaultTimezone() : request.timezone().trim();
        Schedule schedule = schedule(request.runAt(), request.delayMinutes(), request.cron(),
                request.intervalMinutes(), timezone, System.currentTimeMillis());

        Long active = jdbc.queryForObject("""
                SELECT COUNT(*) FROM scheduled_jobs WHERE user_id = ?
                  AND status IN ('PENDING', 'RUNNING', 'PAUSED')
                """, Long.class, userId);
        if (active != null && active >= properties.maxActivePerUser()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Too many active jobs (limit " + properties.maxActivePerUser() + ")");
        }

        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO scheduled_jobs
                    (id, user_id, conversation_id, type, payload, status, run_at, cron,
                     interval_minutes, timezone, attempts, max_attempts, run_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, 0, 3, 0, ?, ?)
                """, id, userId, request.conversationId(), type, request.payload(), schedule.runAt(),
                schedule.cron(), schedule.intervalMinutes(), timezone, now, now);
        return get(userId, id).job();
    }

    public List<JobDto> list(HttpServletRequest http, String userId, String conversationId, String status) {
        return listForUser(users.resolve(AuthSupport.currentUserId(http, userId)).id(), conversationId, status);
    }

    /** Agent-tool entry point with the userId already verified by the tool context. */
    public List<JobDto> listForUser(String userId, String conversationId, String status) {
        String user = users.resolve(userId).id();
        StringBuilder sql = new StringBuilder("SELECT " + JOB_COLUMNS + " FROM scheduled_jobs WHERE user_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(user);
        if (conversationId != null && !conversationId.isBlank()) {
            conversations.ensureOwnership(conversationId, user);
            sql.append(" AND conversation_id = ?");
            args.add(conversationId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim().toUpperCase(Locale.ROOT));
        }
        sql.append(" ORDER BY created_at DESC LIMIT 200");
        return jdbc.query(sql.toString(), (rs, rowNum) -> toDto(mapRow(rs)), args.toArray());
    }

    public JobDetails get(HttpServletRequest http, String id, String userId) {
        return getForUser(users.resolve(AuthSupport.currentUserId(http, userId)).id(), id);
    }

    public JobDetails getForUser(String userId, String id) {
        return get(users.resolve(userId).id(), id);
    }

    private JobDetails get(String userId, String id) {
        JobRow row = jdbc.query("SELECT " + JOB_COLUMNS + " FROM scheduled_jobs WHERE id = ?",
                (rs, rn) -> mapRow(rs), id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));
        if (!row.userId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Job belongs to another user");
        }
        List<JobRunDto> runs = jdbc.query("SELECT " + RUN_COLUMNS + " FROM job_runs WHERE job_id = ? "
                + "ORDER BY scheduled_for DESC LIMIT 20", (rs, rn) -> mapRun(rs), id);
        return new JobDetails(toDto(row), runs);
    }

    public JobDto update(HttpServletRequest http, String id, UpdateJobRequest request) {
        String user = users.resolve(AuthSupport.currentUserId(http, request.userId())).id();
        JobDto job = get(user, id).job();
        if (job.status().equals("CANCELLED") || job.status().equals("DONE") || job.status().equals("FAILED")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot edit a finished job");
        }

        String payload = job.payload();
        if (request.payload() != null && !request.payload().isBlank()) {
            executorFor(job.type()).validate(request.payload(), user);
            payload = request.payload();
        }
        String timezone = request.timezone() == null || request.timezone().isBlank()
                ? (job.timezone() == null ? properties.defaultTimezone() : job.timezone())
                : request.timezone().trim();

        String status = job.status();
        Long runAt = job.runAt();
        String cron = job.cron();
        Integer interval = job.intervalMinutes();
        boolean hasSchedule = request.runAt() != null || request.delayMinutes() != null
                || request.cron() != null || request.intervalMinutes() != null;
        if (hasSchedule) {
            Schedule next = schedule(request.runAt(), request.delayMinutes(), request.cron(),
                    request.intervalMinutes(), timezone, System.currentTimeMillis());
            runAt = next.runAt();
            cron = next.cron();
            interval = next.intervalMinutes();
        }
        String requested = request.status() == null ? null : request.status().trim().toUpperCase(Locale.ROOT);
        if (requested != null) {
            if (!requested.equals("PAUSED") && !requested.equals("PENDING")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be PAUSED or PENDING");
            }
            status = requested;
            if (requested.equals("PENDING") && (runAt == null || runAt <= System.currentTimeMillis())) {
                runAt = System.currentTimeMillis() + 1000;
            }
        }

        jdbc.update("""
                UPDATE scheduled_jobs SET payload = ?, status = ?, run_at = ?, cron = ?,
                    interval_minutes = ?, timezone = ?, updated_at = ?, lease_until = NULL
                WHERE id = ? AND user_id = ?
                """, payload, status, runAt, cron, interval, timezone, System.currentTimeMillis(), id, user);
        return get(user, id).job();
    }

    public void cancel(HttpServletRequest http, String id, String userId) {
        cancelForUser(users.resolve(AuthSupport.currentUserId(http, userId)).id(), id);
    }

    public void cancelForUser(String userId, String id) {
        String user = users.resolve(userId).id();
        JobDto job = get(user, id).job();
        jdbc.update("""
                UPDATE scheduled_jobs SET status = 'CANCELLED', lease_until = NULL, updated_at = ?
                WHERE id = ? AND user_id = ?
                """, System.currentTimeMillis(), job.id(), user);
    }

    /** Runs immediately through the exact same claim + execute path as the poller. */
    public JobDto runNow(HttpServletRequest http, String id, String userId) {
        String user = users.resolve(AuthSupport.currentUserId(http, userId)).id();
        JobDto job = get(user, id).job();
        if (job.status().equals("CANCELLED") || job.status().equals("DONE")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot run a cancelled or finished job");
        }
        long now = System.currentTimeMillis();
        jdbc.update("""
                UPDATE scheduled_jobs SET status = 'RUNNING', lease_until = ?, updated_at = ?
                WHERE id = ? AND user_id = ? AND status <> 'RUNNING'
                """, now + properties.leaseSeconds() * 1000L, now, id, user);
        JobRow claimed = jdbc.query("SELECT " + JOB_COLUMNS + " FROM scheduled_jobs WHERE id = ?",
                (rs, rn) -> mapRow(rs), id).stream().findFirst().orElseThrow();
        long scheduledFor = claimed.runAt() == null ? now : claimed.runAt();
        execute(new ClaimedJob(claimed, scheduledFor));
        return get(user, id).job();
    }

    /** Used when a conversation is deleted: its jobs must never fire into a missing chat. */
    public void cancelJobsForConversation(String conversationId) {
        int count = jdbc.update("""
                UPDATE scheduled_jobs SET status = 'CANCELLED', lease_until = NULL, updated_at = ?
                WHERE conversation_id = ? AND status IN ('PENDING', 'RUNNING', 'PAUSED')
                """, System.currentTimeMillis(), conversationId);
        if (count > 0) {
            log.info("Cancelled {} jobs for deleted conversation {}", count, conversationId);
        }
    }

    // ------------------------------------------------------------ scheduler

    /** Atomically claims up to {@code limit} due jobs; only one worker wins each row. */
    public List<ClaimedJob> claimDue(long now, int limit) {
        List<JobRow> due = jdbc.query("SELECT " + JOB_COLUMNS + " FROM scheduled_jobs "
                        + "WHERE status = 'PENDING' AND run_at IS NOT NULL AND run_at <= ? "
                        + "AND (lease_until IS NULL OR lease_until < ?) ORDER BY run_at LIMIT ?",
                (rs, rn) -> mapRow(rs), now, now, limit);
        List<ClaimedJob> claimed = new ArrayList<>();
        long leaseUntil = now + properties.leaseSeconds() * 1000L;
        for (JobRow job : due) {
            int updated = jdbc.update("""
                    UPDATE scheduled_jobs SET status = 'RUNNING', lease_until = ?, updated_at = ?
                    WHERE id = ? AND status = 'PENDING'
                    """, leaseUntil, now, job.id());
            if (updated == 1) {
                claimed.add(new ClaimedJob(job, job.runAt()));
            }
        }
        return claimed;
    }

    /** Crash recovery: expire stale leases and recompute recurring jobs from now (no backfill). */
    public void recoverOnBoot() {
        long now = System.currentTimeMillis();
        jdbc.update("""
                UPDATE scheduled_jobs SET status = 'PENDING', lease_until = NULL, updated_at = ?
                WHERE status = 'RUNNING' AND (lease_until IS NULL OR lease_until < ?)
                """, now, now);
        List<JobRow> recurring = jdbc.query("SELECT " + JOB_COLUMNS + " FROM scheduled_jobs "
                + "WHERE status = 'PENDING' AND (cron IS NOT NULL OR interval_minutes IS NOT NULL)",
                (rs, rn) -> mapRow(rs));
        for (JobRow job : recurring) {
            Long next = nextRun(now, job.cron(), job.intervalMinutes(), job.timezone());
            if (next != null) {
                jdbc.update("UPDATE scheduled_jobs SET run_at = ?, updated_at = ? WHERE id = ? AND status = 'PENDING'",
                        next, now, job.id());
            }
        }
        // Cap the overdue one-shot catch-up globally: the first maxCatchup keep their
        // due time (the scheduler claims them at boot); the rest are spread into the
        // future so a restart never fires an unbounded burst.
        List<JobRow> overdue = jdbc.query("SELECT " + JOB_COLUMNS + " FROM scheduled_jobs "
                + "WHERE status = 'PENDING' AND run_at IS NOT NULL AND run_at <= ? "
                + "AND cron IS NULL AND interval_minutes IS NULL ORDER BY run_at",
                (rs, rn) -> mapRow(rs), now);
        for (int i = properties.maxCatchup(); i < overdue.size(); i++) {
            long deferred = now + (i - properties.maxCatchup() + 1) * 60_000L;
            jdbc.update("UPDATE scheduled_jobs SET run_at = ?, updated_at = ? WHERE id = ? AND status = 'PENDING'",
                    deferred, now, overdue.get(i).id());
        }
    }

    /** Executes one claimed occurrence with a single idempotent job_runs row. */
    public JobResult execute(ClaimedJob claimed) {
        JobRow job = claimed.job();
        long scheduledFor = claimed.scheduledFor();
        String key = job.id() + ":" + scheduledFor;
        long now = System.currentTimeMillis();

        Optional<RunState> existing = runState(key);
        if (existing.isPresent() && "SUCCESS".equals(existing.get().status())) {
            JobResult previous = parseResult(existing.get().result());
            advance(job, now, previous);
            return previous;
        }
        if (existing.isPresent() && "RUNNING".equals(existing.get().status())) {
            return new JobResult("Job occurrence already running", "", "{}");
        }

        String runId = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO job_runs (id, job_id, idempotency_key, scheduled_for, started_at, status)
                    VALUES (?, ?, ?, ?, ?, 'RUNNING')
                    """, runId, job.id(), key, scheduledFor, now);
        } catch (DuplicateKeyException ex) {
            return new JobResult("Job occurrence already handled", "", "{}");
        }

        try {
            JobExecutor executor = executorFor(job.type());
            JobResult result = executor.execute(job);
            jdbc.update("UPDATE job_runs SET status = 'SUCCESS', result = ?, finished_at = ? WHERE idempotency_key = ?",
                    json(result), System.currentTimeMillis(), key);
            notifications.emit(new NotificationEvent(
                    job.userId(), job.conversationId(), "job_result", "Job completed",
                    result.summary(), result.detail(), job.id(), "job:" + job.id() + ":run:" + runId,
                    result.message()));
            advance(job, System.currentTimeMillis(), result);
            return result;
        } catch (Exception ex) {
            String error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            log.warn("Job {} occurrence {} failed: {}", job.id(), scheduledFor, error);
            JobResult failure = new JobResult("Job failed: " + error, "Job failed: " + error, "{}");
            jdbc.update("UPDATE job_runs SET status = 'FAILED', result = ?, finished_at = ? WHERE idempotency_key = ?",
                    json(failure), System.currentTimeMillis(), key);

            int attempts = job.attempts() + 1;
            long retryAt = System.currentTimeMillis() + Math.min(60_000L * attempts, 3_600_000L);
            if (attempts >= job.maxAttempts()) {
                jdbc.update("""
                        UPDATE scheduled_jobs SET status = 'FAILED', attempts = ?, last_result = ?,
                            lease_until = NULL, updated_at = ? WHERE id = ? AND status = 'RUNNING'
                        """, attempts, error, System.currentTimeMillis(), job.id());
                notifications.emit(new NotificationEvent(
                        job.userId(), job.conversationId(), "job_result", "Job failed",
                        failure.summary(), "{}", job.id(),
                        "job:" + job.id() + ":run:" + runId + ":failed", failure.message()));
            } else {
                jdbc.update("""
                        UPDATE scheduled_jobs SET status = 'PENDING', attempts = ?, run_at = ?, last_result = ?,
                            lease_until = NULL, updated_at = ? WHERE id = ? AND status = 'RUNNING'
                        """, attempts, retryAt, error, System.currentTimeMillis(), job.id());
            }
            return failure;
        }
    }

    private void advance(JobRow job, long now, JobResult result) {
        boolean recurring = job.cron() != null || job.intervalMinutes() != null;
        if (recurring) {
            Long next = nextRun(now, job.cron(), job.intervalMinutes(), job.timezone());
            if (next == null) {
                jdbc.update("""
                        UPDATE scheduled_jobs SET status = 'DONE', run_count = run_count + 1, last_run_at = ?,
                            last_result = ?, attempts = 0, lease_until = NULL, updated_at = ?
                        WHERE id = ? AND status = 'RUNNING'
                        """, now, result.summary(), now, job.id());
                return;
            }
            jdbc.update("""
                    UPDATE scheduled_jobs SET status = 'PENDING', run_at = ?, run_count = run_count + 1,
                        last_run_at = ?, last_result = ?, attempts = 0, lease_until = NULL, updated_at = ?
                    WHERE id = ? AND status = 'RUNNING'
                    """, next, now, result.summary(), now, job.id());
        } else {
            jdbc.update("""
                    UPDATE scheduled_jobs SET status = 'DONE', run_count = run_count + 1, last_run_at = ?,
                        last_result = ?, attempts = 0, lease_until = NULL, updated_at = ?
                    WHERE id = ? AND status = 'RUNNING'
                    """, now, result.summary(), now, job.id());
        }
    }

    // ------------------------------------------------------------- helpers

    private Schedule schedule(String runAt, Integer delayMinutes, String cron,
            Integer intervalMinutes, String timezone, long now) {
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown IANA timezone '" + timezone + "'");
        }
        if (cron != null && !cron.isBlank() && intervalMinutes != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use either cron or intervalMinutes, not both");
        }
        if (cron != null && !cron.isBlank()) {
            CronExpression parsed = parseCron(cron);
            ZonedDateTime next = parsed.next(ZonedDateTime.now(zone));
            if (next == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cron expression has no next run");
            }
            return new Schedule(cron.trim(), null, next.toInstant().toEpochMilli());
        }
        if (intervalMinutes != null) {
            if (intervalMinutes < 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "intervalMinutes must be positive");
            }
            return new Schedule(null, intervalMinutes, now + intervalMinutes * 60_000L);
        }
        if (runAt != null && !runAt.isBlank()) {
            long at;
            try {
                at = Instant.parse(runAt.trim()).toEpochMilli();
            } catch (Exception ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "runAt must be an ISO-8601 timestamp");
            }
            if (at <= now) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "runAt must be in the future");
            }
            return new Schedule(null, null, at);
        }
        if (delayMinutes != null) {
            if (delayMinutes < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "delayMinutes must not be negative");
            }
            return new Schedule(null, null, now + delayMinutes * 60_000L);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A schedule is required (runAt, delayMinutes, cron or intervalMinutes)");
    }

    private Long nextRun(long fromMillis, String cron, Integer intervalMinutes, String timezone) {
        if (cron != null && !cron.isBlank()) {
            try {
                ZoneId zone = ZoneId.of(timezone == null ? properties.defaultTimezone() : timezone);
                ZonedDateTime next = parseCron(cron).next(ZonedDateTime.ofInstant(Instant.ofEpochMilli(fromMillis), zone));
                return next == null ? null : next.toInstant().toEpochMilli();
            } catch (Exception ex) {
                log.warn("Cannot compute next cron run: {}", ex.getMessage());
                return null;
            }
        }
        if (intervalMinutes != null && intervalMinutes > 0) {
            return fromMillis + intervalMinutes * 60_000L;
        }
        return null;
    }

    private static CronExpression parseCron(String cron) {
        try {
            return CronExpression.parse(cron.trim());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid 6-field cron expression '" + cron + "': " + ex.getMessage());
        }
    }

    private JobExecutor executorFor(String type) {
        return executors.stream()
                .filter(executor -> executor.type().equalsIgnoreCase(type))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown job type '" + type + "'"));
    }

    private static String normaliseType(String type) {
        if (type == null || type.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Job type is required");
        }
        return type.trim().toLowerCase(Locale.ROOT);
    }

    private Optional<RunState> runState(String key) {
        return jdbc.query("SELECT status, result FROM job_runs WHERE idempotency_key = ?",
                (rs, rn) -> new RunState(rs.getString("status"), rs.getString("result")), key)
                .stream().findFirst();
    }

    private static JobResult parseResult(String result) {
        try {
            if (result == null || result.isBlank()) {
                return new JobResult("", "", "{}");
            }
            JsonNode node = MAPPER.readTree(result);
            return new JobResult(
                    node.path("summary").asText(""),
                    node.path("message").asText(""),
                    node.path("detail").asText("{}"));
        } catch (Exception ex) {
            return new JobResult("", "", "{}");
        }
    }

    private static final String JOB_COLUMNS = """
            id, user_id, conversation_id, type, payload, status, run_at, cron, interval_minutes,
            timezone, attempts, max_attempts, run_count, last_run_at, last_result, lease_until,
            created_at, updated_at
            """;

    private static final String RUN_COLUMNS = """
            id, job_id, idempotency_key, scheduled_for, started_at, finished_at, status, result
            """;

    private static JobRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new JobRow(
                rs.getString("id"), rs.getString("user_id"), rs.getString("conversation_id"),
                rs.getString("type"), rs.getString("payload"), rs.getString("status"),
                getNullableLong(rs, "run_at"), rs.getString("cron"), getNullableInt(rs, "interval_minutes"),
                rs.getString("timezone"), rs.getInt("attempts"), rs.getInt("max_attempts"),
                rs.getInt("run_count"), getNullableLong(rs, "last_run_at"), rs.getString("last_result"),
                getNullableLong(rs, "lease_until"), rs.getLong("created_at"), rs.getLong("updated_at"));
    }

    private static JobRunDto mapRun(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new JobRunDto(
                rs.getString("id"), rs.getString("job_id"), rs.getString("idempotency_key"),
                getNullableLong(rs, "scheduled_for"), getNullableLong(rs, "started_at"),
                getNullableLong(rs, "finished_at"), rs.getString("status"), rs.getString("result"));
    }

    private static Long getNullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer getNullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static JobDto toDto(JobRow row) {
        return new JobDto(row.id(), row.userId(), row.conversationId(), row.type(), row.payload(),
                row.status(), row.runAt(), row.cron(), row.intervalMinutes(), row.timezone(),
                row.runCount(), row.lastRunAt(), row.lastResult(), row.createdAt());
    }

    /** Serialises structured detail/result JSON; never throws. */
    public static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }
}
