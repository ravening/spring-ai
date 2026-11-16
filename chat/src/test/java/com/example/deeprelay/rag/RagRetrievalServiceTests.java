package com.example.deeprelay.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

@ExtendWith(MockitoExtension.class)
class RagRetrievalServiceTests {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private ChatMemory chatMemory;

    @Test
    void expandsFollowUpQueryWhenRawSearchFindsNothing() {
        RagProperties properties = new RagProperties(4, 0.3, 800, "data/vector-store.json", false,
                "classpath*:knowledge/**/*", true, "eng", 300);
        RagRetrievalService service = new RagRetrievalService(this.vectorStore, this.chatMemory, properties);
        when(this.chatMemory.get("conversation-1"))
                .thenReturn(List.of(new UserMessage("what is nFlow?"), new AssistantMessage("nFlow is a workflow engine.")));
        when(this.vectorStore.similaritySearch(any(SearchRequest.class))).thenAnswer(invocation -> {
            SearchRequest request = invocation.getArgument(0);
            if (request.getQuery().contains("nFlow")) {
                return List.of(new Document("nFlow is a workflow engine.", Map.of("source", "nflow.txt")));
            }
            return List.of();
        });

        List<Document> documents = service.retrieve("what are its features?", "conversation-1", 4);

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).getMetadata()).containsEntry("source", "nflow.txt");
        verify(this.vectorStore).similaritySearch(argThat((SearchRequest request) ->
                request.getQuery().contains("nFlow")));
    }

    @Test
    void keepsRawSearchResultsWhenAvailable() {
        RagProperties properties = new RagProperties(4, 0.3, 800, "data/vector-store.json", false,
                "classpath*:knowledge/**/*", true, "eng", 300);
        RagRetrievalService service = new RagRetrievalService(this.vectorStore, this.chatMemory, properties);
        when(this.vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("nFlow is a workflow engine.", Map.of("source", "nflow.txt"))));

        List<Document> documents = service.retrieve("what is nFlow?", "conversation-1", 4);

        assertThat(documents).hasSize(1);
        verify(this.vectorStore).similaritySearch(any(SearchRequest.class));
    }
}
