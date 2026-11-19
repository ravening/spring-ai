package com.example.bank.web;

import java.util.List;

import com.example.bank.auth.AuthSupport;
import com.example.bank.chat.ConversationService;
import jakarta.servlet.http.HttpServletRequest;
import com.example.bank.knowledge.DocumentService;
import com.example.bank.users.UserDirectory;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final ConversationService conversationService;
    private final UserDirectory users;

    public DocumentController(DocumentService documentService, ConversationService conversationService,
            UserDirectory users) {
        this.documentService = documentService;
        this.conversationService = conversationService;
        this.users = users;
    }

    /**
     * Uploads a file as extra context. scope=chat attaches it to one conversation,
     * scope=user makes it visible in all of the user's conversations.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentService.DocumentInfo upload(
            HttpServletRequest http,
            @RequestParam("file") MultipartFile file,
            @RequestParam String userId,
            @RequestParam(defaultValue = "chat") String scope,
            @RequestParam(required = false) String conversationId) {
        userId = AuthSupport.currentUserId(http, userId);
        users.resolve(userId);
        if ("chat".equalsIgnoreCase(scope)) {
            if (conversationId == null || conversationId.isBlank()) {
                throw new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "conversationId is required when scope=chat");
            }
            conversationService.ensureOwnership(conversationId, userId);
        }
        return documentService.upload(file, scope, conversationId, userId);
    }

    @GetMapping
    public List<DocumentService.DocumentInfo> list(
            HttpServletRequest http,
            @RequestParam String userId,
            @RequestParam(required = false) String conversationId) {
        userId = AuthSupport.currentUserId(http, userId);
        users.resolve(userId);
        if (conversationId != null && !conversationId.isBlank()) {
            conversationService.ensureOwnership(conversationId, userId);
            return documentService.listVisible(conversationId, userId);
        }
        return documentService.listForUser(userId);
    }

    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(HttpServletRequest http, @PathVariable String documentId,
            @RequestParam String userId) {
        userId = AuthSupport.currentUserId(http, userId);
        users.resolve(userId);
        documentService.delete(documentId, userId);
    }
}
