# Embabel Stock Agent

A Spring Boot 4.1 / Spring AI 2.0 / Embabel 1.5.4 application that runs a
multi-agent stock analysis workflow over live market data, with streaming chat,
scoped RAG, MCP server/client and a Vue 3 dashboard.

## What it does

1. The user types a ticker (or asks a question containing one).
2. `StockAnalysisAgent` launches four research branches **in parallel** via
   Embabel's `ScatterGatherBuilder`:
   - **Price** — Finnhub real-time quote + Yahoo Finance chart history
   - **News** — Finnhub headlines and sentiment
   - **Fundamentals** — next earnings date, EPS estimate, dividend and ex-date
   - **Technicals** — deterministic SMA/RSI/ATR, pivot, support and resistance
3. The results are consolidated and a `Verdict` (BUY / SELL / HOLD / WAIT) is
   produced with entry, stop, target and reasoning.
4. The final Markdown report is **streamed token by token** through Embabel's
   `StreamingPromptRunnerBuilder`, with a deterministic fallback if the model
   is unavailable.

Every answer is delivered over SSE (`event: stage | tool_call | token | report | done | error`).
The Vue frontend renders Markdown incrementally while tokens arrive, holding back
incomplete `**bold**`, code fences and table rows so formatting never flickers.

## Scoped documents

- **Global upload** (sidebar) → visible in every conversation.
- **Per-chat upload** (chat header) → visible only in that conversation.
- Chunks are stored in pgvector with metadata `scope = GLOBAL | CHAT:<id>`;
  retrieval applies a filtered vector search, so isolation is enforced in code.
- Tika parses PDF/DOCX/TXT/MD; MiniLM (ONNX, local, 384-dim) creates embeddings.

## Conversations

Chat memory is stored in Postgres via Spring AI's JDBC chat-memory repository,
keyed by `conversationId`. Create/rename/delete conversations in the sidebar;
each chat keeps its own history and document scope.

## MCP

- **Server**: `@McpTool` methods (`stock_quote`, `stock_technicals`, `stock_news`,
  `stock_analyze`) plus the built-in banner, published at `/mcp` using Streamable
  HTTP (default port 8092).
- **Client**: run with `--spring.profiles.active=mcp-client` to connect to an
  external MCP server (example config in `application-mcp-client.yml`). Combine it
  with the provider profile when needed: `--spring.profiles.active=azure,mcp-client`.
  (`AI_PROVIDER=azure` selects the default profile; an explicit active profile
  overrides that default, so include `azure` in the list.)

## Run

```bash
# 1. Start Postgres + pgvector
docker compose up -d

# 2. Configure keys — .env is imported automatically by Spring Boot
cp .env.example .env
# edit the key for your provider (AI_PROVIDER=deeprelay or azure) and FINNHUB_API_KEY

# 3. Build UI into the Spring Boot jar (first time, or after UI changes)
./scripts/build-frontend.sh

# 4. Build and run
mvn -DskipTests package
java -jar target/embabel-stock-agent-0.0.1-SNAPSHOT.jar
```

Open <http://localhost:8092>. For UI development, run `npm run dev` inside
`frontend/` (Vite proxies `/api` to port 8092).

Azure / Microsoft Foundry alternative (in `.env`):

```properties
AI_PROVIDER=azure
AZURE_OPENAI_ENDPOINT=https://<resource-name>.openai.azure.com/openai/v1
AZURE_OPENAI_API_KEY=your-azure-key
AZURE_CHAT_DEPLOYMENT=gpt-4.1
```

Key environment variables:

| Variable | Purpose |
|---|---|
| `AI_PROVIDER` | `deeprelay` (default) or `azure` |
| `DEEPRELAY_BASE_URL` / `DEEPRELAY_API_KEY` / `DEEPRELAY_MODEL` | DeepRelay OpenAI-compatible chat model (also registered as Embabel's default LLM) |
| `AZURE_OPENAI_ENDPOINT` / `AZURE_OPENAI_API_KEY` / `AZURE_CHAT_DEPLOYMENT` | Microsoft Foundry OpenAI-compatible endpoint, key and deployment; used when `AI_PROVIDER=azure` |
| `FINNHUB_API_KEY` | Real-time quote, news, earnings calendar, dividends |
| `POSTGRES_URL` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | Postgres/pgvector connection |

RAG keeps using local MiniLM embeddings (384 dimensions) in both provider modes, so
the existing pgvector table and uploaded documents continue to work. No Azure
embedding deployment is required.

Yahoo Finance chart JSON is keyless and used for historical candles; it is
delayed and unofficial. Finnhub free keys are for personal use — check licensing
before any public deployment.

## API

| Endpoint | Description |
|---|---|
| `POST /api/chat/stream` | SSE chat; extracts a ticker and switches to the analysis agents when present |
| `POST /api/analysis/stream` | SSE analysis for an explicit ticker |
| `GET/POST/PATCH/DELETE /api/conversations` | Conversation lifecycle |
| `GET /api/conversations/{id}/messages` | Full message history, loaded when switching chats |
| `GET /api/conversations/{id}/active` | Whether an agent process is running (survives reload / other tabs) |
| `POST /api/documents/global` | Global file upload |
| `POST /api/documents/chat/{id}` | Per-chat file upload |
| `GET /api/documents?conversationId=` | Visible documents |
| `GET /api/documents/search?query=&conversationId=` | Scoped retrieval (debug) |
| `DELETE /api/documents/{id}` | Delete a document |
| `/mcp` | MCP server endpoint |

## Tests

```bash
mvn test
```

`TechnicalIndicatorsTest` verifies SMA/RSI/ATR/support/resistance calculations.
A live smoke test was performed against `AAPL`, producing stage/tool/token/report/done
SSE events and a typed report with live data.
