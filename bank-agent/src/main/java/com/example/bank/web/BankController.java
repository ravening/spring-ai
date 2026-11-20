package com.example.bank.web;

import java.util.List;

import com.example.bank.auth.AuthSupport;
import com.example.bank.bank.BankStore;
import com.example.bank.bank.BankStore.AccountRow;
import com.example.bank.bank.BankStore.TransferRow;
import com.example.bank.bank.TransferService;
import com.example.bank.bank.TransferService.TransferParams;
import com.example.bank.bank.BankStore.TransferResult;
import com.example.bank.mcpclient.McpStatus;
import com.example.bank.ratelimit.RateLimitProperties;
import com.example.bank.ratelimit.RateLimiter;
import com.example.bank.tools.ToolCatalog;
import com.example.bank.users.BankUser;
import com.example.bank.users.UserDirectory;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class BankController {

    private final UserDirectory users;
    private final ToolCatalog toolCatalog;
    private final BankStore bankStore;
    private final TransferService transfers;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties rateLimitProperties;

    public BankController(UserDirectory users, ToolCatalog toolCatalog, BankStore bankStore,
            TransferService transfers, RateLimiter rateLimiter, RateLimitProperties rateLimitProperties) {
        this.users = users;
        this.toolCatalog = toolCatalog;
        this.bankStore = bankStore;
        this.transfers = transfers;
        this.rateLimiter = rateLimiter;
        this.rateLimitProperties = rateLimitProperties;
    }

    @GetMapping("/users")
    public List<BankUser> users() {
        return users.list();
    }

    @GetMapping("/tools")
    public ToolsResponse tools() {
        return new ToolsResponse(toolCatalog.localNames(), toolCatalog.mcpStatus());
    }

    @GetMapping("/mcp/status")
    public McpStatus mcpStatus() {
        return toolCatalog.mcpStatus();
    }

    @PostMapping("/mcp/refresh")
    public McpStatus refreshMcp() {
        return toolCatalog.refreshMcp();
    }

    /** Authoritative balances from the H2 BankStore (the MCP server serves stale demo data). */
    @GetMapping("/accounts")
    public List<AccountRow> accounts(HttpServletRequest http, @RequestParam(required = false) String userId) {
        return bankStore.accounts(resolve(http, userId));
    }

    /** Full audit trail of transfers involving the user, from the authoritative store. */
    @GetMapping("/transfers")
    public List<TransferRow> transfers(HttpServletRequest http, @RequestParam(required = false) String userId) {
        return bankStore.transfers(resolve(http, userId));
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResult transfer(HttpServletRequest http, @RequestBody TransferRequest request,
            @RequestParam(required = false) String userId) {
        String supplied = request.userId() == null || request.userId().isBlank() ? userId : request.userId();
        String from = resolve(http, supplied);
        rateLimiter.check("transfers", from, rateLimitProperties.transfersPerMinute());
        TransferParams params = transfers.validate(from, request.fromAccountId(), request.toUserId(),
                request.toAccountId(), request.amount(), request.currency(), request.note());
        return transfers.transfer(params, request.idempotencyKey());
    }

    private String resolve(HttpServletRequest http, String supplied) {
        return users.resolve(AuthSupport.currentUserId(http, supplied)).id();
    }

    public record ToolsResponse(List<String> localTools, McpStatus mcp) {
    }

    public record TransferRequest(String userId, String fromAccountId, String toUserId, String toAccountId,
            String amount, String currency, String note, String idempotencyKey) {
    }
}
