package com.example.bank.jobs;

import java.util.List;

import com.example.bank.auth.AuthSupport;
import com.example.bank.jobs.JobDtos.CreateJobRequest;
import com.example.bank.jobs.JobDtos.JobDetails;
import com.example.bank.jobs.JobDtos.JobDto;
import com.example.bank.jobs.JobDtos.UpdateJobRequest;
import com.example.bank.ratelimit.RateLimitProperties;
import com.example.bank.ratelimit.RateLimiter;
import com.example.bank.users.UserDirectory;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Full CRUD + run-now over the caller's own jobs. */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobs;
    private final UserDirectory users;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties rateLimitProperties;

    public JobController(JobService jobs, UserDirectory users, RateLimiter rateLimiter,
            RateLimitProperties rateLimitProperties) {
        this.jobs = jobs;
        this.users = users;
        this.rateLimiter = rateLimiter;
        this.rateLimitProperties = rateLimitProperties;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JobDto create(HttpServletRequest http, @RequestBody CreateJobRequest request,
            @RequestParam(required = false) String userId) {
        if (request.userId() == null || request.userId().isBlank()) {
            request = new CreateJobRequest(userId, request.conversationId(), request.type(), request.payload(),
                    request.runAt(), request.delayMinutes(), request.cron(), request.intervalMinutes(),
                    request.timezone());
        }
        String user = users.resolve(AuthSupport.currentUserId(http, request.userId())).id();
        rateLimiter.check("jobs", user, rateLimitProperties.jobsPerMinute());
        return jobs.create(http, request);
    }

    @GetMapping
    public List<JobDto> list(HttpServletRequest http,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false) String status) {
        return jobs.list(http, userId, conversationId, status);
    }

    @GetMapping("/{id}")
    public JobDetails get(HttpServletRequest http, @PathVariable String id,
            @RequestParam(required = false) String userId) {
        return jobs.get(http, id, userId);
    }

    @PatchMapping("/{id}")
    public JobDto update(HttpServletRequest http, @PathVariable String id,
            @RequestBody UpdateJobRequest request,
            @RequestParam(required = false) String userId) {
        if (request.userId() == null || request.userId().isBlank()) {
            request = new UpdateJobRequest(userId, request.payload(), request.runAt(), request.delayMinutes(),
                    request.cron(), request.intervalMinutes(), request.timezone(), request.status());
        }
        return jobs.update(http, id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(HttpServletRequest http, @PathVariable String id,
            @RequestParam(required = false) String userId) {
        jobs.cancel(http, id, userId);
    }

    @PostMapping("/{id}/run")
    public JobDto runNow(HttpServletRequest http, @PathVariable String id,
            @RequestParam(required = false) String userId) {
        return jobs.runNow(http, id, userId);
    }
}
