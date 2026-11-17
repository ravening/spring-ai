# Spring AI Demos

This repository contains two Spring AI projects:

| Folder | What it is |
|---|---|
| `chat/` | Chat and RAG playground with DeepRelay/OpenAI-compatible models |
| `agent/` | Agent lab: tool calling, MCP client/server, multi-agent workflow and live trace UI |

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

## Security notes

Both projects apply defence in depth against prompt injection: input length caps,
escaped `<data>` wrappers around retrieved/tool content, and instructions that treat
wrapped text as data. This reduces but cannot eliminate the risk — the system prompt
is not a security boundary, so permissions must be enforced in code. See the
[agent security notes](agent/README.md#prompt-injection-notes) for details.
