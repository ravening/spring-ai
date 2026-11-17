package com.example.stockagent.web;

import com.example.stockagent.domain.DocumentScope;
import com.example.stockagent.service.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documents;

    public DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @PostMapping("/global")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentService.DocumentInfo uploadGlobal(@RequestParam("file") MultipartFile file) throws Exception {
        return documents.upload(file, DocumentScope.GLOBAL, null);
    }

    @PostMapping("/chat/{conversationId}")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentService.DocumentInfo uploadForChat(@PathVariable String conversationId,
                                                      @RequestParam("file") MultipartFile file) throws Exception {
        return documents.upload(file, DocumentScope.CHAT, conversationId);
    }

    @GetMapping
    public List<DocumentService.DocumentInfo> list(@RequestParam(required = false) String conversationId) {
        return documents.listVisible(conversationId);
    }

    @GetMapping("/global")
    public List<DocumentService.DocumentInfo> listGlobal() {
        return documents.listGlobal();
    }

    @GetMapping("/chat/{conversationId}")
    public List<DocumentService.DocumentInfo> listForChat(@PathVariable String conversationId) {
        return documents.listForChat(conversationId);
    }

    /** Debug/verification endpoint: shows exactly which chunks a chat can see. */
    @GetMapping("/search")
    public List<com.example.stockagent.domain.DocumentSnippet> search(
            @RequestParam String query,
            @RequestParam(required = false) String conversationId) {
        return documents.search(query, conversationId);
    }

    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String documentId) {
        documents.delete(documentId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
    }
}
