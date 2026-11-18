# Spring AI Demos

This repository contains two Spring AI projects:

| Folder | What it is |
|---|---|
| `chat/` | Chat and RAG playground with DeepRelay/OpenAI-compatible models |
| `agent/` | Agent lab: tool calling, MCP client/server, multi-agent workflow and live trace UI |
| `embabel-stock-agent/` | Embabel multi-agent stock analyser: parallel research, live data, streaming chat, scoped RAG, MCP server/client and a Vue dashboard |
| `bank-agent/` | Multi-agent banking assistant: per-customer chat with memory, scoped RAG file uploads, async "still working" runs and a separate bank MCP server exposing account/transaction tools |

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
