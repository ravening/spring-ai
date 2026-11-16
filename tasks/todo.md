# Task Checklist: Expanding the `agent/` Demo

Ordered, verifiable tasks. See `tasks/plan.md` for details, acceptance criteria, and files.

## Phase 1: Streaming parity

- [x] Task 1 — SSE chat endpoint (`POST /api/chat/stream`)
- [x] Task 2 — UI consumes the stream with a toggle

### Checkpoint: Phase 1
- [x] `mvn -q -DskipTests package` succeeds
- [x] `/api/chat` unchanged; streaming emits `conversation`, tokens and terminal `tool_calls` event
- [x] Verified live with curl: correct SSE framing, headers and error event
- [x] Committed

## Phase 2: RAG parity

- [x] Task 3 — RAG dependencies, config, knowledge doc, `KnowledgeService`, `knowledge_search` tool
- [x] Task 4 — Surface retrieval in docs/architecture map; verify multi-agent use

### Checkpoint: Phase 2
- [x] Build succeeds; `/api/tools` lists `knowledge_search`
- [x] Live smoke test indexed 1 document into 1 chunk and persisted the vector store
- [x] `knowledge_search` available to chat and the research agent via `ToolCatalog.all()`
- [x] Committed

## Phase 3: New agent features

- [ ] Task 5 — Parallel plan steps (`agent.agents.parallel`, default false)
- [ ] Task 6 — Human-in-the-loop plan approval (`/api/agent/plan`, `approvedPlan`, UI)
- [ ] Task 7 — Structured critic output + bounded revision loop

### Checkpoint: Phase 3
- [ ] Full build green; focused tests pass
- [ ] Parallel, approval, and critic flows manually verified
- [ ] Committed

## Final gate

- [ ] All acceptance criteria met
- [ ] `agent/README.md` reflects the new features
- [ ] Human review of the complete change
