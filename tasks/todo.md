# Task Checklist: Expanding the `agent/` Demo

Ordered, verifiable tasks. See `tasks/plan.md` for details, acceptance criteria, and files.

## Phase 1: Streaming parity

- [ ] Task 1 — SSE chat endpoint (`POST /api/chat/stream`)
- [ ] Task 2 — UI consumes the stream with a toggle

### Checkpoint: Phase 1
- [ ] `mvn -q -DskipTests package` succeeds
- [ ] `/api/chat` unchanged; streaming emits tokens + terminal `tool_calls` event
- [ ] Committed

## Phase 2: RAG parity

- [ ] Task 3 — RAG dependencies, config, knowledge doc, `KnowledgeService`, `knowledge_search` tool
- [ ] Task 4 — Surface retrieval in docs/architecture map; verify multi-agent use

### Checkpoint: Phase 2
- [ ] Build succeeds; `/api/tools` lists `knowledge_search`
- [ ] RAG-only question answered in both chat and multi-agent tabs
- [ ] Committed

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
