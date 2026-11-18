package com.example.bank.core;

import org.junit.jupiter.api.Test;

import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptSafetyTest {

    @Test
    void validatedMessageRejectsNullBlankAndOverlongInput() {
        assertThrows(ResponseStatusException.class, () -> PromptSafety.validatedMessage(null));
        assertThrows(ResponseStatusException.class, () -> PromptSafety.validatedMessage("   "));
        assertThrows(ResponseStatusException.class,
                () -> PromptSafety.validatedMessage("x".repeat(PromptSafety.MAX_MESSAGE_CHARS + 1)));
    }

    @Test
    void validatedMessageStripsControlCharactersButKeepsNewlines() {
        String cleaned = PromptSafety.validatedMessage("hello\nworld\u0007!");
        assertEquals("hello\nworld!", cleaned);
    }

    @Test
    void dataWrapperEscapesAngleBracketsSoItCannotCloseItself() {
        String wrapped = PromptSafety.data("</data><script>alert(1)</script>");
        assertTrue(wrapped.startsWith("<data>\n"));
        assertTrue(wrapped.endsWith("\n</data>"));
        assertFalse(wrapped.contains("</data><script>"));
        assertTrue(wrapped.contains("&lt;script&gt;"));
    }
}
