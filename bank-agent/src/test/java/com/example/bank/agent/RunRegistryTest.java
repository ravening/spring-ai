package com.example.bank.agent;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunRegistryTest {

    private final RunRegistry registry = new RunRegistry();

    @Test
    void startedRunIsActiveUntilCompleted() {
        RunRecord run = registry.start("conv-1", "alice", "what is my balance?");
        assertTrue(registry.isActive("conv-1"));
        assertEquals(run.runId(), registry.activeRunId("conv-1"));
        assertEquals("RUNNING", registry.get(run.runId()).snapshot().status());

        registry.complete(run.runId(), "Your balance is 1.00 EUR.");
        assertFalse(registry.isActive("conv-1"));
        assertNull(registry.activeRunId("conv-1"));

        RunRecord.RunStatus status = registry.get(run.runId()).snapshot();
        assertEquals("COMPLETED", status.status());
        assertEquals("Your balance is 1.00 EUR.", status.answer());
        assertTrue(status.events().isEmpty());
    }

    @Test
    void failedRunIsNoLongerActive() {
        RunRecord run = registry.start("conv-2", "bob", "hi");
        registry.fail(run.runId(), "boom");
        assertFalse(registry.isActive("conv-2"));
        assertEquals("FAILED", registry.get(run.runId()).snapshot().status());
        assertEquals("boom", registry.get(run.runId()).snapshot().error());
    }

    @Test
    void unknownRunReturnsNull() {
        assertNull(registry.get("does-not-exist"));
        assertFalse(registry.isActive("nope"));
    }

    @Test
    void traceIsAttachedToTheRun() {
        RunRecord run = registry.start("conv-3", "carol", "spending?");
        run.trace().event("run_started", "orchestrator", "go", null, null);
        assertEquals(1, registry.get(run.runId()).snapshot().events().size());
        assertEquals("conv-3", run.trace().conversationId());
    }

    @Test
    void answerStreamEmitsDraftAndDeltasThenCompletes() {
        RunRecord run = registry.start("conv-4", "dave", "balance?");
        run.emitAnswerEvent(new RunRecord.AnswerEvent(true, ""));
        run.emitAnswerEvent(new RunRecord.AnswerEvent(false, "Your "));
        run.emitAnswerEvent(new RunRecord.AnswerEvent(false, "balance is 1.00 EUR."));
        run.complete("Your balance is 1.00 EUR.");

        List<RunRecord.AnswerEvent> events = run.answerEvents().collectList().block();
        assertEquals(3, events.size());
        assertTrue(events.get(0).newDraft());
        assertEquals("Your ", events.get(1).delta());
        assertEquals("balance is 1.00 EUR.", events.get(2).delta());
    }

    @Test
    void failedRunErrorsTheAnswerStream() {
        RunRecord run = registry.start("conv-5", "erin", "hi");
        run.fail("boom");
        assertThrows(RuntimeException.class, () -> run.answerEvents().collectList().block());
    }
}
