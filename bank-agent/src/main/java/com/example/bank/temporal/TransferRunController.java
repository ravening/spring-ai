package com.example.bank.temporal;

import java.net.URI;
import java.util.UUID;

import com.example.bank.auth.AuthSupport;

import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import jakarta.servlet.http.HttpServletRequest;

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
 * Durable transfer runs on Temporal. Additive to the existing immediate chat/tool path
 * and the {@code JobService} scheduler, which stay unchanged.
 */
@RestController
@RequestMapping("/api/bank/transfers/runs")
public class TransferRunController {

    private final TransferRunService runService;

    public TransferRunController(TransferRunService runService) {
        this.runService = runService;
    }

    public record StartResponse(String runId, String statusUrl) {
    }

    public record ApproveRequest(boolean approved) {
    }

    @PostMapping
    public ResponseEntity<StartResponse> start(@RequestBody TransferRequest request,
            HttpServletRequest httpRequest) {
        if (request == null
                || !StringUtils.hasText(request.fromAccountId())
                || !StringUtils.hasText(request.toUserId())
                || !StringUtils.hasText(request.toAccountId())
                || !StringUtils.hasText(request.amount())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "fromAccountId, toUserId, toAccountId and amount are required");
        }
        String fromUserId = AuthSupport.currentUserId(httpRequest, request.fromUserId());
        if (!StringUtils.hasText(fromUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fromUserId is required");
        }
        String runId = StringUtils.hasText(request.runId()) ? request.runId() : UUID.randomUUID().toString();

        TransferRequest effective = new TransferRequest(
                runId, fromUserId, request.fromAccountId(), request.toUserId(), request.toAccountId(),
                request.amount(), request.currency(), request.note(), request.requireApproval());

        try {
            runService.start(effective, runId);
        }
        catch (WorkflowExecutionAlreadyStarted ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Run already started: " + runId);
        }

        String statusUrl = "/api/bank/transfers/runs/" + runId;
        return ResponseEntity.accepted()
                .location(URI.create(statusUrl))
                .body(new StartResponse(runId, statusUrl));
    }

    @GetMapping("/{runId}")
    public TransferView view(@PathVariable String runId) {
        try {
            return runService.view(runId);
        }
        catch (WorkflowNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown run: " + runId);
        }
    }

    @PostMapping("/{runId}/approve")
    public ResponseEntity<Void> approve(@PathVariable String runId, @RequestBody ApproveRequest request) {
        TransferView current;
        try {
            current = runService.view(runId);
        }
        catch (WorkflowNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown run: " + runId);
        }
        if (current.status() != TransferView.Status.WAITING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Run " + runId + " is not waiting for approval (status: " + current.status() + ")");
        }
        runService.approve(runId, request != null && request.approved());
        return ResponseEntity.accepted().build();
    }
}
