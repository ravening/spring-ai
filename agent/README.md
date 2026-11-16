# Spring AI Agent Demo

A small, self-contained demo that goes beyond a chat box:

- **Chat with persistent memory** and tool calling
- **Saved conversation history** that survives restarts and can be reopened or deleted
- **Local `@Tool` methods** (calculator, clock, support tickets)
- **MCP server** exposing inventory, shipping, FX and weather tools
- **MCP client** that discovers and calls those tools over streamable HTTP
- **Multi-step agent run** with a planner and specialist agents
- **Multiple agents working together**: planner, researcher, analyst, writer, critic
- **Live trace** of every agent event and tool call in the browser UI

Spring Boot 4.1.2, Spring AI 2.0.2, Java 21, Maven. No tests, by design.

## Interactive architecture map

Start the app, then open <http://localhost:8090/architecture.html>. It is also
linked as **Architecture** in the app header.

The map has three clickable flows: **Chat**, **Multi-agent** and **MCP recovery**
(`#chat`, `#agents`, `#mcp`). Choose a flow, press **Play flow**, or click any
box to see its role, inputs, outputs and the Java files that implement it. The
diagram is self-contained: no CDN, no external diagram library.

## Architecture

```
Browser
   |
   v
Agent app :8090  (com.example.agent)
   |-- ChatService --------------------------> OpenAI-compatible model
   |-- AgentOrchestrator
   |     |-- PlannerAgent
   |     |-- ResearchAgent  --.
   |     |-- AnalystAgent   --+--> Local @Tools
   |     |-- WriterAgent       +--> MCP client ---> MCP server :8091
   |     `-- CriticAgent   --'                        (com.example.mcpdemo)
   `-- REST API + static UI
```

The agent app lazily connects to the MCP server, retries if it is down, and
filters tools by allowed origin (`local`, `mcp`).

## Project map

| Path | What it does |
|---|---|
| `src/main/java/com/example/agent/AgentApplication.java` | Agent app entrypoint, port 8090 |
| `agent/` | `AgentOrchestrator`, planner, specialists, writer and critic |
| `chat/` | `ChatService`, request/response records, JDBC memory config and conversation API |
| `tools/` | Local `@Tool` methods and the combined tool catalog |
| `mcpclient/` | Lazy streamable-HTTP MCP client, status and reconnect |
| `core/` | Trace, events, tool-call records and tracing wrapper |
| `api/` | REST controller and JSON error handler |
| `com/example/mcpdemo/` | MCP server entrypoint and `@McpTool` methods |
| `resources/static/` | Chat UI, interactive architecture map and styles |
| `application.yml` | Agent app configuration |
| `application-mcp.yml` | MCP server profile, port 8091 and `/mcp` |
| `run-agent.sh` / `run-mcp-server.sh` | One-command startup scripts |

## Prerequisites

- JDK 21+
- Maven 3.9+
- An OpenAI-compatible endpoint. This demo defaults to DeepRelay.

```bash
export DEEPRELAY_API_KEY=your-key
# optional
export DEEPRELAY_BASE_URL=https://api.deeprelay.ai/v1
export DEEPRELAY_MODEL=deeprelay/deepseek-v4.1-flash
```

## Run it

Open two terminals. Each script starts a long-running server and blocks until
you press `Ctrl+C`, so leave one terminal open per server.

Terminal 1 - MCP server on port 8091 (start this first):

```bash
./run-mcp-server.sh
```

Terminal 2 - agent app + UI on port 8090:

```bash
./run-agent.sh
```

Then open <http://localhost:8090>.

The scripts also check that their port is free before starting. If a port is
already in use, stop the old instance first:

```bash
kill $(lsof -tiTCP:8090 -sTCP:LISTEN)
kill $(lsof -tiTCP:8091 -sTCP:LISTEN)
```

If you prefer explicit Maven commands:

```bash
# MCP server
mvn -Pmcp-server spring-boot:run

# agent app
mvn spring-boot:run
```

The `mcp-server` Maven profile switches the main class. Do not run both
commands in the same terminal; the first one blocks.

## Try these

Chat, single tool:

> What is the weather in Berlin for the next 2 days?

Chat, multiple tools:

> Check stock for SKU-1001 and quote expedited shipping of 3 kg to India.

Multi-agent run (use the Multi-agent tab):

> Check SKU-1001 inventory, calculate the cost of 3 units in INR including
> expedited shipping to India, and write an executive summary.

The planner decides which specialists to call. The researcher gathers data with
tools, the analyst does the arithmetic, the writer produces the answer, and the
critic either approves it or asks the writer for one revision.

## Local tools

| Tool | Description |
|---|---|
| `calculator` | add, subtract, multiply, divide |
| `current_time` | current date/time in UTC, optionally a zone |
| `support_ticket_lookup` | look up a ticket by id |
| `support_ticket_search` | search demo tickets by keyword |

## MCP server tools

| Tool | Description |
|---|---|
| `weather_forecast` | short forecast for a city |
| `inventory_check` | stock, warehouse and restock date by SKU |
| `currency_exchange` | convert an amount between currencies |
| `shipping_quote` | demo shipping quote by country/weight/service |

The server also exposes the `memo://agent-demo/returns-policy` resource.

## API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/chat` | chat with memory and all tools |
| `POST` | `/api/agent/run` | planner + specialist agents |
| `GET` | `/api/conversations` | stored conversations with title, time and message count |
| `GET` | `/api/conversations/{id}` | full message history for one conversation |
| `DELETE` | `/api/conversations/{id}` | delete a conversation and its memory |
| `GET` | `/api/tools` | local tools and MCP status |
| `GET` | `/api/mcp/status` | MCP connection state and discovered tools |
| `POST` | `/api/mcp/refresh` | force reconnect and rediscovery |

Chat request:

```json
{
  "message": "What is the weather in Berlin for the next 2 days?",
  "conversationId": "optional-stable-id"
}
```

Agent request:

```json
{
  "task": "Check SKU-1001 inventory and write an executive summary.",
  "conversationId": "optional-stable-id"
}
```

Responses include the final answer plus every `toolCall` and agent `event`, so
the UI can show what actually happened.

## Inspect the MCP server directly

The MCP endpoint is `http://localhost:8091/mcp` (streamable HTTP). You can point
MCP Inspector or an MCP client at it:

```bash
npx @modelcontextprotocol/inspector
```

Use transport **Streamable HTTP** and URL `http://localhost:8091/mcp`.

For Claude Desktop, add a connector/remote MCP server with that URL while the
server is running.

## Configuration

Agent app (`application.yml`):

| Property | Default |
|---|---|
| `server.port` | `8090` |
| `DEEPRELAY_API_KEY` | required |
| `DEEPRELAY_BASE_URL` | `https://api.deeprelay.ai/v1` |
| `DEEPRELAY_MODEL` | `deeprelay/deepseek-v4.1-flash` |
| `AGENT_MCP_ENABLED` | `true` |
| `AGENT_MCP_URL` | `http://localhost:8091` |
| `AGENT_MCP_ENDPOINT` | `/mcp` |
| `AGENT_CHAT_MEMORY_MAX_MESSAGES` | `40` |
| `AGENT_MAX_PLAN_STEPS` | `4` |

MCP server (`application-mcp.yml`, profile `mcp`):

| Property | Default |
|---|---|
| `MCP_SERVER_PORT` | `8091` |
| protocol | `STREAMABLE` |
| endpoint | `/mcp` |

The MCP server runs with the `mcp` profile automatically; it disables the chat
model so it does not need an API key.

## Notes

- Chat memory is persisted per `conversationId` in the H2 file
  `./data/agent-chat-memory`, so history survives restarts. The full conversation
  is stored, while the model receives only the most recent
  `AGENT_CHAT_MEMORY_MAX_MESSAGES` messages.
- Both tabs share the same conversation history: a follow-up in the chat tab can
  refer to an earlier multi-agent task, and the conversations sidebar can reopen
  or delete any stored conversation.
- MCP client connections are lazy and re-established on failure.
- The model must support OpenAI-style tool calling. If it does not, tool calls
  will not happen even though `/api/tools` lists them.
- The included tools use demo data only.
