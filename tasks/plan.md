# Implementation Plan: Expanding the `agent/` Demo

## Overview

Extend the `agent/` Spring AI demo with two groups of features the user selected:

1. **Feature parity with `chat/`** — SSE streaming of chat answers, and RAG-backed knowledge retrieval.
2. **New agent capabilities** — parallel plan-step execution, human-in-the-loop plan approval, and structured critic output with a bounded revision loop.

Work is delivered as thin vertical slices. Each slice is implemented, verified with the
Maven build, and committed before the next one starts. Existing behavior (`POST /api/chat`,
`POST /api/agent/run`) stays unchanged; all new behavior is additive and, where risky,
opt-in via configuration.

## Architecture Decisions

- **Streaming**: new `POST /api/chat/stream` returning SSE (`Flux<ServerSentEvent<String>>`).
  Text tokens are normal `data` events; a terminal `tool_calls` event carries the tracing
  records as JSON so the UI can still show what happened. Keeps one JSON request contract
  with `/api/chat`. Spring MVC + Flux is already proven by `chat/StreamingChatController`.
- **RAG**: implemented agent-natively as a `knowledge_search` local `@Tool` backed by a slim
  `SimpleVectorStore` service, so both the chat client and the multi-agent researcher can
  retrieve. Reuses the chat module's transformers/MiniLM embedding setup but with
  agent-local cache and store paths. Classpath docs only — the Tika/OCR upload UI is
  intentionally out of scope.
- **Parallel steps**: opt-in with `agent.agents.parallel` (default `false`, preserving today's
  deterministic sequential behavior). Results are reordered to the original plan order.
- **Plan approval**: additive `POST /api/agent/plan` endpoint plus an optional `approvedPlan`
  field on `AgentRequest`. No approval required by default.
- **Critic**: structured output via `ChatClient.responseEntity(...)`, replacing the fragile
  `startsWith("APPROVED")` string check, with a bounded revision loop (max 2 revisions).
- **Tests**: add `spring-boot-starter-test` to `agent/pom.xml` and focused unit tests for new
  pure logic (plan normalization, review parsing). This is verification of the new features,
  not a general test-writing initiative.

## Task List

### Phase 1: Streaming parity

- [ ] **Task 1 (M): SSE chat endpoint.**
  Add `POST /api/chat/stream` (SSE) to `AgentController`; add a streaming method to
  `ChatService` that uses `.stream().content()` with the same tool callbacks and tool context
  as the non-streaming path, emitting a terminal `tool_calls` event.
  *Acceptance:* tokens stream incrementally; terminal event contains the trace's tool calls;
  `POST /api/chat` is unchanged.
  *Verify:* `mvn -q -DskipTests package`; curl the endpoint and observe events.
  *Files:* `AgentController.java`, `ChatService.java`.

- [ ] **Task 2 (M): UI consumes the stream.**
  Wire the chat tab in `static/app.js` to the SSE endpoint behind a "Stream" toggle; render
  incremental markdown and the final tool-call trace.
  *Acceptance:* toggling streaming on produces progressive output; toggling off uses the
  existing JSON path.
  *Verify:* manual browser check.
  *Files:* `static/app.js`, `static/index.html`.

**Checkpoint:** build succeeds; both chat modes work end to end.

### Phase 2: RAG parity

- [ ] **Task 3 (M): RAG foundation + retrieval tool.**
  Add vector-store/Tika/transformers dependencies to `agent/pom.xml`; add `agent.rag.*`
  properties to `application.yml`; add a small knowledge markdown file; add
  `KnowledgeProperties`, `KnowledgeConfig` (`SimpleVectorStore`), and `KnowledgeService`
  (startup ingestion + `search()`); register `knowledge_search` in `LocalTools`.
  *Acceptance:* app starts, `/api/tools` lists `knowledge_search`, a query answered only by
  the knowledge doc returns grounded content.
  *Verify:* `mvn -q -DskipTests package`; curl `/api/tools` and a RAG-specific chat question.
  *Files:* `pom.xml`, `application.yml`, new `knowledge/` package, `LocalTools.java`.

- [ ] **Task 4 (S): Surface retrieval + docs.**
  Make retrieved context visible in the agent trace (tool calls already cover this via the
  tracing wrapper); update `agent/README.md` and the architecture map text for the new tool.
  *Acceptance:* docs describe RAG; a multi-agent run can use the knowledge tool.
  *Verify:* build + manual multi-agent run.
  *Files:* `agent/README.md`, `static/architecture.js`.

**Checkpoint:** build succeeds; RAG question works in both chat and multi-agent tabs.

### Phase 3: New agent features

- [ ] **Task 5 (M): Parallel plan-step execution.**
  Add `agent.agents.parallel` flag (default `false`). When enabled, run independent steps on
  a bounded executor, collect results, and reorder to plan order. Ensure `AgentTrace` is
  thread-safe (synchronize event/tool-call accumulation).
  *Acceptance:* with the flag on, steps execute concurrently and results/trace are complete
  and correctly ordered; with it off, behavior is byte-for-byte the current behavior.
  *Verify:* build + multi-agent run in both modes.
  *Files:* `AgentOrchestrator.java`, `AgentTrace.java`, `application.yml`.

- [ ] **Task 6 (M): Human-in-the-loop plan approval.**
  Add `AgentOrchestrator.plan(...)` and `POST /api/agent/plan`; add optional `approvedPlan`
  to `AgentRequest` and have `run(...)` honor it. Add "Plan → Approve / Reject" controls to
  the Multi-agent tab.
  *Acceptance:* plan endpoint returns steps without executing; run with an approved plan
  skips replanning; default run path unchanged.
  *Verify:* build + manual UI flow.
  *Files:* `AgentRequest.java`, `AgentOrchestrator.java`, `AgentController.java`, `static/app.js`.

- [ ] **Task 7 (S): Structured critic output + bounded revisions.**
  Introduce a `Review` record; use `ChatClient` structured output in `CriticAgent`; cap
  revisions at 2 in `AgentOrchestrator` with a final "unresolved" trace event.
  *Acceptance:* approval is decided from structured data; a persistently rejecting critic
  cannot loop forever.
  *Verify:* build + focused unit test for the review parsing / loop bound.
  *Files:* `CriticAgent.java`, `AgentOrchestrator.java`, new `Review.java`, test.

**Checkpoint:** full build green; all new flows manually verified.

## Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Transformers embedding model (~87 MB) downloads on first RAG use | Medium | Cache dir configured to `data/model-cache`; ingestion failure is logged and non-fatal; document first-run download. |
| `AgentTrace` / tool callbacks not thread-safe under parallel steps | Medium | Parallel mode defaults off; add synchronization to trace accumulation before enabling. |
| Spring MVC SSE buffering | Low | Proven pattern in `chat/` (`no-cache`, `X-Accel-Buffering: no` headers). |
| Agent module currently has no test dependency | Low | Add `spring-boot-starter-test` in Task 3 and focused tests for new pure logic. |

## Scope Notes (not touching)

- No authentication, rate limiting, Docker, or persistence migration — out of scope here.
- No Tika/OCR upload UI or document-management API in `agent/` — classpath docs only.
- No general test suite for pre-existing code — only verification tests for new logic.

## Human Approval Required

Plan must be reviewed and approved before implementation begins. Recommendation: start with
Task 1 (streaming) as the first increment.
