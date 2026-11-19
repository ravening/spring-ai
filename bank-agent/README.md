# Spring AI Bank (`bank-agent/`)

A fully working demo **banking assistant**: a Spring Boot + Spring AI app where
customers chat with a **team of agents** that call a separate **MCP server** for all
bank data, remember the full conversation, and can read **uploaded documents (RAG)**.

## Quick start

```bash
cd bank-agent
export DEEPRELAY_API_KEY=your-key
./run-bank-mcp.sh   # terminal 1: bank MCP server on http://localhost:8096/mcp
./run-bank-app.sh   # terminal 2: bank app + UI on http://localhost:8095
```

Open <http://localhost:8095>, pick a demo customer (alice, bob or carol) and ask:

- "What is my account balance?"
- "Show my last 10 transactions"
- "What did I spend this month, broken down by category?"
- "What is my income for last month?"
- "Any Amazon payments?" / "What are the fees for a SWIFT transfer?"
- Upload a file (e.g. a statement or a contract) and ask "summarize the important
  points of my uploaded file" — then ask a follow-up like "and the fees mentioned in it?"

## Architecture

```
Browser UI (static/index.html)
   │  REST: /api/chat, /api/conversations, /api/documents, /api/runs, /api/tools
   ▼
┌───────────────────────────── bank-agent app (:8095) ─────────────────────────────┐
│  BankChatService (async runs, one active run per conversation)                   │
│  BankOrchestrator: router → domain agent → writer → critic (bounded revisions)   │
│    ├─ AccountAgent / TransactionAgent / KnowledgeAgent / GeneralAgent            │
│    └─ ToolCatalog = local @Tools (knowledge_search, calculator, current_time)    │
│                    + MCP client → bank MCP server (discovery, retry, reconnect)  │
│  Memory: H2 JDBC ChatMemoryRepository (full history, windowed for the model)     │
│  Conversations + documents metadata: H2                                          │
│  RAG: SimpleVectorStore (JSON file) + local MiniLM embeddings (no API key)        │
│         classpath knowledge/*.md (global) + uploaded files (scope user|chat)     │
└───────────────────────────────┬───────────────────────────────────────────────────┘
                                │ streamable HTTP MCP
                                ▼
┌──────────────────────── bank MCP server (:8096) ────────────────────────────────┐
│  BankMcpTools (@McpTool): list_accounts, get_account_balance, list_transactions,  │
│  search_transactions, spending_summary, income_summary, list_cards,              │
│  get_customer_profile — every tool requires userId and serves only that user's   │
│  deterministic demo data (BankData).                                             │
└───────────────────────────────────────────────────────────────────────────────────┘
```

All banking facts come from the MCP server — the agent app never touches bank data
in code, so per-user scoping is enforced at the MCP boundary. Local `@Tool`s are only
for app-internal capabilities (RAG retrieval, arithmetic, time).

## Features

- **Multi-user demo**: a customer picker in the header (no login). Each customer has
  their own accounts, transactions, cards, conversations and uploaded documents;
  ownership is checked server-side on every request.
- **Conversation memory**: full history persisted in H2
  (`spring_ai_chat_memory`); the model sees a window of the latest messages, so
  follow-ups ("and last month?", "what about dining?") resolve from context.
- **Async chat with live status**: `POST /api/chat` returns `202` immediately with a
  `runId`; the UI polls `GET /api/runs/{runId}` and shows the agent trace and tool
  calls live. Conversations that are still processing show a "⏳ working…" badge in
  the sidebar and in the chat header — even after switching to another chat and back
  or reloading the page (`GET /api/conversations` exposes `active`/`activeRunId`).
  Only one run per conversation is allowed at a time (HTTP 409 otherwise).
- **RAG with scoped uploads**: `POST /api/documents` (multipart) indexes txt/md/pdf/
  docx/csv/html/… via Tika into the vector store. Scope `chat` = visible only in that
  conversation; scope `user` = visible in all of that user's conversations. The
  classpath `knowledge/*.md` bank handbook is always visible. Retrieval is done by the
  `knowledge_search` local tool, which receives `conversationId`/`userId` through the
  tool context and filters results accordingly.
- **Multi-agent flow**: router classifies the request (accounts / transactions /
  spending / income / search / cards / profile / knowledge / general), the matching
  domain agent gathers facts via tools, the writer drafts the answer in Markdown and
  the critic reviews it (max `bank.agents.max-revisions`, default 2).
- **MCP client resilience**: if the MCP server is down, the app still starts; the
  client retries with a cooldown and "Refresh MCP" reconnects on demand.

## API summary

| Endpoint | Description |
|---|---|
| `GET /api/users` | demo customers |
| `POST /api/chat` | `{userId, conversationId?, message}` → `202 {conversationId, runId}` |
| `POST /api/chat/voice` | multipart `audio`, `userId`, `conversationId?` → transcribes, then `202 {conversationId, runId}` (503 when voice is disabled) |
| `GET /api/runs/{runId}` | run status + live events/tool calls + final answer (+ `message`, the transcript for voice runs) |
| `GET /api/runs/{runId}/audio` | `audio/mpeg` spoken answer (404 when no audio for the run) |
| `GET /api/conversations?userId=` | list with `active` flag and `activeRunId` |
| `POST /api/conversations` | `{userId}` → create |
| `GET /api/conversations/{id}?userId=` | messages + visible documents + active flag |
| `DELETE /api/conversations/{id}?userId=` | delete conversation and its memory |
| `POST /api/documents` | multipart `file`, `userId`, `scope=chat\|user`, `conversationId?` |
| `GET /api/documents?userId=&conversationId=` | visible uploads |
| `DELETE /api/documents/{id}?userId=` | delete upload and its chunks |
| `GET /api/tools`, `GET /api/mcp/status`, `POST /api/mcp/refresh` | tool/MCP introspection |

## Configuration (env vars)

| Property | Default |
|---|---|
| `BANK_PORT` / `BANK_MCP_PORT` | `8095` / `8096` |
| `DEEPRELAY_API_KEY`, `DEEPRELAY_BASE_URL`, `DEEPRELAY_MODEL`, `DEEPRELAY_REASONING_EFFORT` | DeepRelay endpoint, model `deeprelay/deepseek-v4.1-flash` |
| `BANK_MCP_ENABLED`, `BANK_MCP_URL`, `BANK_MCP_ENDPOINT` | `true`, `http://localhost:8096`, `/mcp` |
| `BANK_RAG_ENABLED`, `BANK_RAG_TOP_K`, `BANK_RAG_SIMILARITY_THRESHOLD`, `BANK_RAG_VECTOR_STORE_FILE` | `true`, `4`, `0.25`, `data/bank-vector-store.json` |
| `BANK_CHAT_MEMORY_MAX_MESSAGES` | `40` |
| `BANK_AGENTS_MAX_REVISIONS` | `2` |
| `BANK_UPLOADS_DIR`, `BANK_EMBEDDING_CACHE_DIR` | `data/uploads`, `data/model-cache` |
| `BANK_VOICE_ENABLED` | `false` — set `true` to enable voice chat (mic button in the UI) |
| `AUDIO_API_KEY`, `AUDIO_BASE_URL` | required when voice is enabled; default base URL `https://api.openai.com/v1` (DeepRelay does not proxy the OpenAI audio endpoints, so STT/TTS use this separate key/endpoint) |
| `AUDIO_STT_MODEL`, `AUDIO_TTS_MODEL`, `AUDIO_TTS_VOICE` | `whisper-1`, `gpt-4o-mini-tts`, `alloy` |
| `BANK_VOICE_MAX_UPLOAD_BYTES`, `BANK_VOICE_MAX_TTS_CHARS`, `BANK_VOICE_AUDIO_DIR` | `10485760`, `4000`, `data/audio` |

Voice chat: the browser records audio (`MediaRecorder`) and posts it to
`POST /api/chat/voice`; the server transcribes it (STT) and runs the normal agent
pipeline, so the transcript is stored in `spring_ai_chat_memory` like any typed
message. After the run completes, the answer is synthesized to MP3
(`data/audio/<runId>.mp3`) and played in the UI next to the text bubble
(`GET /api/runs/{runId}/audio`).

The embedding model (all-MiniLM-L6-v2, ~87 MB) is downloaded once into
`data/model-cache` on first start, so RAG works without any embedding API key.

## Security notes

Same defence-in-depth approach as the other demos in this repo: input length caps,
`<data>` delimiters (escaped) around conversation history, tool output and retrieved
RAG passages, and instructions that treat wrapped text as data, not commands. RAG
documents are scoped per user/conversation, and the MCP server validates `userId` on
every call, so one customer cannot read another customer's data. The system prompt is
not a security boundary — permissions are enforced in code (tool allowlists, userId
validation, one active run per conversation).

## Build and test

```bash
mvn -q -DskipTests package   # build
mvn test                      # unit tests (BankData, RunRegistry, PromptSafety, …)
./run-bank-mcp.sh             # terminal 1
./run-bank-app.sh             # terminal 2 (needs DEEPRELAY_API_KEY)
```
