const nodes = {
    browser: {
        eyebrow: "Client",
        title: "Browser UI",
        subtitle: "One page for chat, multi-agent runs and MCP status.",
        description: "The UI sends JSON to the agent app, renders the Markdown answer, lists every tool call and draws the live trace of a multi-agent run.",
        io: [
            ["In", "Answers, tool calls and agent events"],
            ["Out", "POST /api/chat or POST /api/agent/run"]
        ],
        files: ["index.html", "app.js", "styles.css"]
    },
    chat: {
        eyebrow: "Chat",
        title: "ChatService",
        subtitle: "A single agent with persistent memory and tools.",
        description: "Builds a ChatClient prompt with a MessageChatMemoryAdvisor backed by JDBC chat memory, attaches local and MCP tool callbacks, and records every tool call in the AgentTrace. The full conversation is stored in H2, while the model receives the most recent messages.",
        io: [
            ["In", "message + conversationId"],
            ["Out", "answer + toolCalls"],
            ["Store", "GET/DELETE /api/conversations"]
        ],
        files: ["ChatService.java", "ChatMemoryConfig.java", "ConversationController.java", "AgentConfig.java"]
    },
    orchestrator: {
        eyebrow: "Orchestration",
        title: "AgentOrchestrator",
        subtitle: "Plans, delegates, reviews and returns the final answer.",
        description: "Creates a trace, asks the planner for a plan, runs each specialist in order, then asks the writer to synthesize and the critic to review. A failed review triggers one writer revision.",
        io: [
            ["In", "task + conversationId"],
            ["Out", "answer + plan + events + toolCalls"]
        ],
        files: ["AgentOrchestrator.java", "AgentRunResponse.java", "AgentSupport.java"]
    },
    planner: {
        eyebrow: "Specialist",
        title: "PlannerAgent",
        subtitle: "Turns a task into 2-4 concrete steps.",
        description: "Chooses RESEARCHER, ANALYST and WRITER steps, adding ANALYST only when actual arithmetic is needed. Falls back to a simple research step if planning fails.",
        io: [
            ["In", "Goal + recent conversation"],
            ["Out", "AgentPlan with PlanStep list"]
        ],
        files: ["PlannerAgent.java", "AgentPlan.java", "PlanStep.java"]
    },
    research: {
        eyebrow: "Specialist",
        title: "ResearchAgent",
        subtitle: "Gathers facts with local and MCP tools.",
        description: "Uses inventory, shipping, FX, weather and support-ticket tools. Tool output is recorded in the trace and passed to the writer as evidence.",
        io: [
            ["In", "Assignment + prior step results"],
            ["Out", "Compact Markdown findings"]
        ],
        files: ["ResearchAgent.java", "LocalTools.java", "DemoMcpTools.java"]
    },
    analyst: {
        eyebrow: "Specialist",
        title: "AnalystAgent",
        subtitle: "Does arithmetic with the calculator tool.",
        description: "Receives the research findings and uses only the calculator tool. It must show the calculation and result rather than estimating numbers.",
        io: [
            ["In", "Research findings + calculation request"],
            ["Out", "Calculation, result, interpretation"]
        ],
        files: ["AnalystAgent.java", "LocalTools.java"]
    },
    writer: {
        eyebrow: "Specialist",
        title: "WriterAgent",
        subtitle: "Drafts, synthesizes and revises the answer.",
        description: "Turns team findings into user-facing Markdown. It avoids meta-commentary and honors format limits such as one sentence or one paragraph.",
        io: [
            ["In", "Goal + all step results"],
            ["Out", "Draft or final Markdown"]
        ],
        files: ["WriterAgent.java", "AgentSupport.java"]
    },
    critic: {
        eyebrow: "Specialist",
        title: "CriticAgent",
        subtitle: "Reviews the draft against the evidence.",
        description: "Returns APPROVED or a numbered list of issues. Any review that does not start with APPROVED sends the draft back to the writer once.",
        io: [
            ["In", "Goal + evidence + draft"],
            ["Out", "APPROVED or issue list"]
        ],
        files: ["CriticAgent.java", "AgentOrchestrator.java"]
    },
    local: {
        eyebrow: "Tools",
        title: "Local tools",
        subtitle: "Four in-process Spring AI @Tool methods.",
        description: "Calculator, current time, support-ticket lookup and support-ticket search run directly in the agent app. TracingToolCallback wraps each one so the UI sees arguments, results and duration.",
        io: [
            ["In", "Model tool call with JSON arguments"],
            ["Out", "String result + trace record"]
        ],
        files: ["LocalTools.java", "LocalToolCatalog.java", "TracingToolCallback.java"]
    },
    mcpClient: {
        eyebrow: "MCP",
        title: "MCP client",
        subtitle: "Lazy streamable-HTTP connection with retry.",
        description: "McpToolRegistry connects to http://localhost:8091/mcp, discovers tools, wraps them for tracing and reconnects on demand. Failed refreshes clear stale tool callbacks.",
        io: [
            ["In", "MCP server URL + endpoint"],
            ["Out", "Discovered ToolCallbacks"]
        ],
        files: ["McpToolRegistry.java", "McpClientProperties.java", "McpStatus.java"]
    },
    model: {
        eyebrow: "Model",
        title: "LLM endpoint",
        subtitle: "OpenAI-compatible chat + tool calling.",
        description: "The demo defaults to DeepRelay and the deeprelay/deepseek-v4.1-flash model. Every agent uses the same ChatClient.Builder but with a different system prompt.",
        io: [
            ["In", "System prompt + messages + tool definitions"],
            ["Out", "Text or a tool call"]
        ],
        files: ["application.yml", "DEEPRELAY_API_KEY"]
    },
    mcpServer: {
        eyebrow: "MCP",
        title: "MCP server",
        subtitle: "A second Spring Boot app on port 8091.",
        description: "Starts with the mcp profile and exposes streamable HTTP at /mcp. It disables the chat model and OpenAI auto-configuration because it only serves tools.",
        io: [
            ["In", "MCP initialize, listTools, callTool"],
            ["Out", "Tool results and the returns-policy resource"]
        ],
        files: ["McpServerApplication.java", "application-mcp.yml"]
    },
    mcpTools: {
        eyebrow: "MCP",
        title: "MCP tools",
        subtitle: "Weather, inventory, FX and shipping.",
        description: "Four annotated methods in DemoMcpTools. They use deterministic demo data so the agent can make repeatable tool calls without external services.",
        io: [
            ["In", "city/days, sku, currency pair, country/weight"],
            ["Out", "Forecast, stock, conversion or quote"]
        ],
        files: ["DemoMcpTools.java"]
    },
    resource: {
        eyebrow: "MCP",
        title: "MCP resource",
        subtitle: "Returns policy exposed at a stable URI.",
        description: "The server advertises memo://agent-demo/returns-policy. It is registered next to the tools and can be read by any MCP client.",
        io: [
            ["URI", "memo://agent-demo/returns-policy"],
            ["Out", "Return and refund policy text"]
        ],
        files: ["DemoMcpTools.java"]
    }
};

const flows = {
    chat: {
        eyebrow: "Flow 1 of 3",
        title: "Single-agent chat with tools",
        description: "ChatService keeps the conversation, offers local and MCP tools to the model, and records every call in the trace below the answer.",
        nodes: ["browser", "chat", "model", "local", "mcpClient", "mcpServer", "mcpTools"],
        steps: [
            { label: "Browser sends POST /api/chat with a message and conversationId.", nodes: ["browser"] },
            { label: "ChatService loads JDBC-backed history and exposes local tools plus discovered MCP tools.", nodes: ["chat", "local", "mcpClient"] },
            { label: "The model chooses a tool or answers directly from the conversation.", nodes: ["model"] },
            { label: "A local method or the MCP server executes the tool; result flows back to the model.", nodes: ["local", "mcpServer", "mcpTools"] },
            { label: "The model writes the answer; the UI renders Markdown, tool calls and durations.", nodes: ["chat", "browser"] }
        ]
    },
    agents: {
        eyebrow: "Flow 2 of 3",
        title: "Multi-agent collaboration",
        description: "A planner breaks the task into steps. Specialists work in order, then the writer synthesizes and the critic can send it back for one revision.",
        nodes: ["browser", "orchestrator", "planner", "research", "analyst", "writer", "critic", "model", "local", "mcpClient", "mcpServer", "mcpTools"],
        steps: [
            { label: "Browser sends POST /api/agent/run with a task.", nodes: ["browser"] },
            { label: "AgentOrchestrator asks PlannerAgent for a short structured plan.", nodes: ["orchestrator", "planner", "model"] },
            { label: "ResearchAgent gathers data with tools; AnalystAgent handles real arithmetic.", nodes: ["research", "analyst", "local", "mcpClient", "mcpServer"] },
            { label: "WriterAgent synthesizes the final answer from all step results.", nodes: ["writer", "model"] },
            { label: "CriticAgent reviews. If issues are found, WriterAgent revises once.", nodes: ["critic", "writer"] },
            { label: "The final answer, plan, events and tool calls return to the UI.", nodes: ["orchestrator", "browser"] }
        ]
    },
    mcp: {
        eyebrow: "Flow 3 of 3",
        title: "MCP discovery and recovery",
        description: "The MCP client is lazy. Refresh MCP closes the old client, reconnects, rediscovers tools and clears stale callbacks when the server is down.",
        nodes: ["browser", "mcpClient", "mcpServer", "mcpTools", "resource"],
        steps: [
            { label: "The UI calls POST /api/mcp/refresh and updates the MCP badge.", nodes: ["browser"] },
            { label: "McpToolRegistry closes the current client and tries the /mcp endpoint again.", nodes: ["mcpClient"] },
            { label: "The server announces four tools and the returns-policy resource.", nodes: ["mcpServer", "mcpTools", "resource"] },
            { label: "If the server is down, tools are cleared and the error is shown in the UI.", nodes: ["mcpClient", "browser"] },
            { label: "Start the server and refresh again to reconnect and rediscover.", nodes: ["mcpServer", "mcpClient"] }
        ]
    }
};

const svg = document.getElementById("arch-svg");
const nodeEls = Array.from(svg.querySelectorAll(".node"));
const edgeEls = Array.from(svg.querySelectorAll(".edge"));
const edgeLabels = Array.from(svg.querySelectorAll(".edge-label"));
const modeButtons = Array.from(document.querySelectorAll(".mode-button"));
const playButton = document.getElementById("play-flow");
const flowEyebrow = document.getElementById("flow-eyebrow");
const flowTitle = document.getElementById("flow-title");
const flowDescription = document.getElementById("flow-description");
const flowSteps = document.getElementById("flow-steps");
const detailEyebrow = document.getElementById("detail-eyebrow");
const detailTitle = document.getElementById("detail-title");
const detailSubtitle = document.getElementById("detail-subtitle");
const detailDescription = document.getElementById("detail-description");
const detailIo = document.getElementById("detail-io");
const detailFiles = document.getElementById("detail-files");

let currentMode = "chat";
let currentStep = -1;
let playTimer = null;

function flowMatches(element, mode) {
    return (element.dataset.flow || "").split(/\s+/).includes(mode);
}

function setMode(mode) {
    if (!flows[mode]) {
        return;
    }
    stopPlayback(true);
    currentMode = mode;
    svg.dataset.mode = mode;
    if (window.location.hash !== "#" + mode) {
        window.history.replaceState(null, "", "#" + mode);
    }

    modeButtons.forEach((button) => {
        const active = button.dataset.mode === mode;
        button.classList.toggle("is-active", active);
        button.setAttribute("aria-pressed", String(active));
    });

    const flow = flows[mode];
    flowEyebrow.textContent = flow.eyebrow;
    flowTitle.textContent = flow.title;
    flowDescription.textContent = flow.description;

    nodeEls.forEach((element) => {
        element.classList.toggle("is-context", flow.nodes.includes(element.dataset.node));
        element.classList.remove("is-active", "is-selected");
    });
    edgeEls.forEach((element) => element.classList.toggle("is-context", flowMatches(element, mode)));
    edgeLabels.forEach((element) => element.classList.toggle("is-context", flowMatches(element, mode)));

    renderSteps(flow);
    resetDetail();
}

function renderSteps(flow) {
    flowSteps.innerHTML = "";
    flow.steps.forEach((step, index) => {
        const item = document.createElement("li");
        const button = document.createElement("button");
        button.type = "button";
        button.dataset.step = String(index);

        const number = document.createElement("span");
        number.className = "step-number";
        number.textContent = String(index + 1);

        const label = document.createElement("span");
        label.textContent = step.label;

        button.append(number, label);
        button.addEventListener("click", () => {
            stopPlayback(false);
            highlightStep(index);
            selectNode(step.nodes[0]);
        });
        item.append(button);
        flowSteps.append(item);
    });
}

function highlightStep(index) {
    const step = flows[currentMode].steps[index];
    if (!step) {
        return;
    }
    currentStep = index;
    nodeEls.forEach((element) => {
        element.classList.toggle("is-active", step.nodes.includes(element.dataset.node));
    });
    Array.from(flowSteps.querySelectorAll("button")).forEach((button, buttonIndex) => {
        button.classList.toggle("is-current", buttonIndex === index);
    });
}

function nodeElement(id) {
    return nodeEls.find((element) => element.dataset.node === id);
}

function selectNode(id) {
    const info = nodes[id];
    if (!info) {
        return;
    }
    nodeEls.forEach((element) => {
        element.classList.toggle("is-selected", element.dataset.node === id);
    });
    detailEyebrow.textContent = info.eyebrow;
    detailTitle.textContent = info.title;
    detailSubtitle.textContent = info.subtitle;
    detailDescription.textContent = info.description;

    detailIo.innerHTML = "";
    info.io.forEach(([label, value]) => {
        const term = document.createElement("dt");
        term.textContent = label;
        const description = document.createElement("dd");
        description.textContent = value;
        detailIo.append(term, description);
    });

    detailFiles.innerHTML = "";
    info.files.forEach((file) => {
        const code = document.createElement("code");
        code.textContent = file;
        detailFiles.append(code);
    });
}

function resetDetail() {
    detailEyebrow.textContent = "Component";
    detailTitle.textContent = "Click a box";
    detailSubtitle.textContent = "The diagram is interactive.";
    detailDescription.textContent = "Select any component to see what it does, what flows through it and which files implement it.";
    detailIo.innerHTML = "";
    detailFiles.innerHTML = "";
}

function stopPlayback(clear) {
    if (playTimer) {
        window.clearTimeout(playTimer);
        playTimer = null;
    }
    playButton.classList.remove("is-playing");
    playButton.textContent = "Play flow";
    if (clear) {
        currentStep = -1;
        nodeEls.forEach((element) => element.classList.remove("is-active"));
        Array.from(flowSteps.querySelectorAll("button")).forEach((button) => button.classList.remove("is-current"));
    }
}

function playFlow() {
    if (playTimer) {
        stopPlayback(true);
        return;
    }
    const flow = flows[currentMode];
    let index = -1;
    playButton.classList.add("is-playing");
    playButton.textContent = "Stop";

    const advance = () => {
        index += 1;
        if (index >= flow.steps.length) {
            stopPlayback(false);
            return;
        }
        highlightStep(index);
        selectNode(flow.steps[index].nodes[0]);
        playTimer = window.setTimeout(advance, 1400);
    };
    advance();
}

modeButtons.forEach((button) => {
    button.addEventListener("click", () => setMode(button.dataset.mode));
});

nodeEls.forEach((element) => {
    element.addEventListener("click", () => {
        stopPlayback(false);
        const id = element.dataset.node;
        const stepIndex = flows[currentMode].steps.findIndex((step) => step.nodes.includes(id));
        if (stepIndex >= 0) {
            highlightStep(stepIndex);
        }
        selectNode(id);
    });
    element.addEventListener("keydown", (event) => {
        if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            element.click();
        }
    });
});

playButton.addEventListener("click", playFlow);

const initialMode = window.location.hash.replace("#", "");
setMode(flows[initialMode] ? initialMode : "chat");
