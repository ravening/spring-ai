package com.example.agent.tools;

import java.util.List;
import java.util.Map;

import com.example.agent.knowledge.KnowledgeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalToolsKnowledgeTests {

    @Mock
    private KnowledgeService knowledgeService;

    @Test
    void knowledgeSearchFormatsSourcesAndPassages() {
        when(knowledgeService.search("refund window", 4)).thenReturn(List.of(
                new Document("Items may be returned within 45 days.", Map.of("source", "handbook.md"))));

        LocalTools tools = new LocalTools(knowledgeService);
        String result = tools.knowledgeSearch("refund window", null);

        assertThat(result).startsWith("<data>").contains("Source: handbook.md", "45 days");
        verify(knowledgeService).search("refund window", 4);
    }

    @Test
    void knowledgeSearchReportsNoMatches() {
        when(knowledgeService.search("unknown", 4)).thenReturn(List.of());
        LocalTools tools = new LocalTools(knowledgeService);
        assertThat(tools.knowledgeSearch("unknown", 4)).contains("No knowledge-base passages matched");
    }
}
