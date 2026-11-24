# Spring AI Demos

This repository contains several Spring AI projects:

| Folder | What it is |
|---|---|
| `chat/` | Chat and RAG playground with DeepRelay/OpenAI-compatible models |
| `agent/` | Agent lab: tool calling, MCP client/server, multi-agent workflow and live trace UI |
| `embabel-stock-agent/` | Embabel multi-agent stock analyser: parallel research, live data, streaming chat, scoped RAG, MCP server/client and a Vue dashboard |
| `bank-agent/` | Multi-agent banking assistant: per-customer chat with memory, scoped RAG file uploads, async "still working" runs and a separate bank MCP server exposing account/transaction tools |

## Choosing an AI provider

All Spring Boot modules can switch their chat/LLM provider between **DeepRelay** and
**Microsoft Foundry / Azure OpenAI** without code changes. Set `AI_PROVIDER` (or activate
the matching Spring profile) and provide that provider's key/endpoint:

```bash
# DeepRelay (default)
export AI_PROVIDER=deeprelay
export DEEPRELAY_API_KEY=your-key

# Azure / Microsoft Foundry
export AI_PROVIDER=azure
export AZURE_OPENAI_ENDPOINT=https://<resource-name>.openai.azure.com/openai/v1
export AZURE_OPENAI_API_KEY=your-key
export AZURE_CHAT_DEPLOYMENT=gpt-4.1
```

- `AZURE_OPENAI_ENDPOINT` must be the OpenAI-compatible `/openai/v1` endpoint. For a
  Foundry resource, `https://<resource-name>.services.ai.azure.com/openai/v1` also works.
- `AZURE_CHAT_DEPLOYMENT` is the **deployment name** in Azure. If you name the deployment
  exactly like the model (`gpt-4.1`), the default works.
- RAG keeps using the local MiniLM embedding model in both modes, so no Azure embedding
  deployment is needed and existing vector stores keep working.
- Suggested Azure models:

  | Capability | Model to deploy |
  |---|---|
  | Chat, agents, RAG answers | `gpt-4.1` (or `gpt-4.1-mini` for lower cost) |
  | Text-to-speech | `gpt-4o-mini-tts` |
  | Speech-to-text | `gpt-4o-mini-transcribe` (or `whisper` for wider region support) |

- The mobile app (`bank-agent-mobile/`) needs no changes: it uses the same REST/SSE
  endpoints and discovers server voice through `GET /api/voice/status`.

## Agent demo

The `agent/` project is the newer demo. It includes:

- Chat with conversation memory, tool calling and optional SSE streaming
- A local RAG knowledge base (vector store + MiniLM embeddings) exposed as a tool
- Local Spring AI `@Tool` methods
- An MCP server with weather, inventory, FX and shipping tools
- An MCP client with discovery, retry and reconnect
- A planner plus researcher, analyst, writer and critic agents
- A browser UI with a live event/tool-call trace
- An interactive architecture map at `/architecture.html`

Read the full guide: [`agent/README.md`](agent/README.md)

### Quick start

```bash
cd agent
export AI_PROVIDER=deeprelay
export DEEPRELAY_API_KEY=your-key
./run-mcp-server.sh   # terminal 1, port 8091
./run-agent.sh        # terminal 2, port 8090
```

Open <http://localhost:8090> for the demo and
<http://localhost:8090/architecture.html> for the interactive diagram.

## Bank demo

The `bank-agent/` project is a multi-agent banking assistant: customers chat with a
team of agents (router → domain agents → writer → critic), all bank data comes from a
separate MCP server (`list_accounts`, `get_account_balance`, `list_transactions`,
`spending_summary`, `income_summary`, …), conversations are remembered per customer,
and uploaded files are indexed as scoped RAG context.

```bash
cd bank-agent
export AI_PROVIDER=deeprelay
export DEEPRELAY_API_KEY=your-key
./run-bank-mcp.sh   # terminal 1, port 8096
./run-bank-app.sh   # terminal 2, port 8095
```

Open <http://localhost:8095>, pick a demo customer (alice, bob, carol) and chat.
See [`bank-agent/README.md`](bank-agent/README.md) for the full guide.

## Security notes

Both projects apply defence in depth against prompt injection: input length caps,
escaped `<data>` wrappers around retrieved/tool content, and instructions that treat
wrapped text as data. This reduces but cannot eliminate the risk — the system prompt
is not a security boundary, so permissions must be enforced in code. See the
[agent security notes](agent/README.md#prompt-injection-notes) for details.
