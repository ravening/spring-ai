package com.example.agent.temporal;

import java.net.URI;
import java.util.UUID;

import com.example.agent.agent.AgentPlan;
import com.example.agent.core.ConversationId;

import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Durable agent runs on Temporal. The existing synchronous {@code /api/agent/run} and
 * {@code /api/agent/plan} endpoints are unchanged; this API is additive.
 */
@RestController
@RequestMapping("/api/agent/runs")
public class AgentRunController {

    private final AgentRunService runService;

    public AgentRunController(AgentRunService runService) {
        this.runService = runService;
    }

    public record StartRunRequest(
            String task,
            String conversationId,
            AgentPlan approvedPlan,
            Boolean requireApproval,
            String runId) {
    }

    public record StartRunResponse(String runId, String statusUrl) {
    }

    public record ApproveRequest(boolean approved) {
    }

    @PostMapping
    public ResponseEntity<StartRunResponse> start(@RequestBody StartRunRequest request) {
        if (request == null || !StringUtils.hasText(request.task())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "task is required");
        }
        String runId = StringUtils.hasText(request.runId) ? request.runId() : UUID.randomUUID().toString();
        String conversationId = ConversationId.resolve(request.conversationId());

        AgentRunInput input = new AgentRunInput(
                runId,
                conversationId,
                "local",
                request.task().trim(),
                request.approvedPlan(),
                Boolean.TRUE.equals(request.requireApproval()));

        try {
            runService.start(input, runId);
        }
        catch (WorkflowExecutionAlreadyStarted ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Run already started: " + runId);
        }

        String statusUrl = "/api/agent/runs/" + runId;
        return ResponseEntity.accepted()
                .location(URI.create(statusUrl))
                .body(new StartRunResponse(runId, statusUrl));
    }

    @GetMapping("/{runId}")
    public AgentRunView view(@PathVariable String runId) {
        try {
            return runService.view(runId);
        }
        catch (WorkflowNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown run: " + runId);
        }
    }

    @PostMapping("/{runId}/approve")
    public ResponseEntity<Void> approve(@PathVariable String runId, @RequestBody ApproveRequest request) {
        AgentRunView current;
        try {
            current = runService.view(runId);
        }
        catch (WorkflowNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown run: " + runId);
        }
        if (current.status() != RunStatus.WAITING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Run " + runId + " is not waiting for approval (status: " + current.status() + ")");
        }
        runService.approve(runId, request != null && request.approved());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{runId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable String runId) {
        try {
            runService.cancel(runId);
        }
        catch (WorkflowNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown run: " + runId);
        }
        return ResponseEntity.accepted().build();
    }
}
