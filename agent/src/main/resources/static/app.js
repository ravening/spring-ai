const STORAGE_KEY = "agentConversationId";

const state = {
    conversationId: localStorage.getItem(STORAGE_KEY) || "",
    conversations: [],
    busy: false,
    mcp: null
};

const els = {
    chatPanel: document.getElementById("chat-panel"),
    agentPanel: document.getElementById("agent-panel"),
    chatMessages: document.getElementById("chat-messages"),
    chatForm: document.getElementById("chat-form"),
    chatInput: document.getElementById("chat-input"),
    chatSend: document.getElementById("chat-send"),
    agentForm: document.getElementById("agent-form"),
    agentInput: document.getElementById("agent-input"),
    agentRun: document.getElementById("agent-run"),
    agentProgress: document.getElementById("agent-progress"),
    agentTimeline: document.getElementById("agent-timeline"),
    agentResult: document.getElementById("agent-result"),
    conversationLabel: document.getElementById("conversation-label"),
    conversations: document.getElementById("conversations"),
    mcpBadge: document.getElementById("mcp-badge"),
    mcpError: document.getElementById("mcp-error"),
    localTools: document.getElementById("local-tools"),
    mcpTools: document.getElementById("mcp-tools"),
    refreshMcp: document.getElementById("refresh-mcp"),
    newChat: document.getElementById("new-chat")
};

function escapeText(value) {
    return String(value == null ? "" : value);
}

function renderMarkdown(target, text) {
    const value = text == null ? "" : String(text);
    if (window.marked && window.DOMPurify) {
        target.innerHTML = window.DOMPurify.sanitize(window.marked.parse(value, { gfm: true, breaks: true }));
    } else {
        target.textContent = value;
    }
    target.classList.add("markdown");
}

function formatTime(at) {
    if (!at) {
        return "";
    }
    return new Date(at).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

function summarize(value, maxLength) {
    if (!value) {
        return "(no output)";
    }
    const oneLine = String(value).replace(/\s+/g, " ").trim();
    return oneLine.length > maxLength ? oneLine.slice(0, maxLength) + "..." : oneLine;
}

async function api(path, options = {}) {
    const response = await fetch(path, {
        headers: { "Content-Type": "application/json" },
        ...options
    });
    const text = await response.text();
    let body = {};
    if (text) {
        try {
            body = JSON.parse(text);
        } catch (error) {
            body = { error: text };
        }
    }
    if (!response.ok) {
        throw new Error(body.error || `Request failed (${response.status})`);
    }
    return body;
}

function updateConversationLabel() {
    els.conversationLabel.textContent = state.conversationId
        ? "Conversation: " + state.conversationId
        : "New conversation";
}

function persistConversation() {
    if (state.conversationId) {
        localStorage.setItem(STORAGE_KEY, state.conversationId);
    } else {
        localStorage.removeItem(STORAGE_KEY);
    }
    updateConversationLabel();
}

function formatHistoryTime(value) {
    if (!value) {
        return "";
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return "";
    }
    const now = new Date();
    const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
    const timestamp = date.getTime();
    if (timestamp >= startOfToday) {
        return date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
    }
    if (timestamp >= startOfToday - 86400000) {
        return "Yesterday";
    }
    return date.toLocaleDateString([], { month: "short", day: "numeric" });
}

async function loadConversations() {
    try {
        const data = await api("/api/conversations");
        state.conversations = Array.isArray(data) ? data : [];
    } catch (error) {
        state.conversations = [];
        els.conversations.innerHTML = "";
        const item = document.createElement("li");
        item.className = "conversation-placeholder";
        item.textContent = "Unable to load conversations";
        els.conversations.append(item);
        return;
    }
    renderConversations();
}

function renderConversations() {
    els.conversations.innerHTML = "";
    if (state.conversations.length === 0) {
        const item = document.createElement("li");
        item.className = "conversation-placeholder";
        item.textContent = "No conversations yet.";
        els.conversations.append(item);
        return;
    }

    state.conversations.forEach((conversation) => {
        const item = document.createElement("li");
        item.className = "conversation-item" + (conversation.id === state.conversationId ? " active" : "");

        const open = document.createElement("button");
        open.type = "button";
        open.className = "conversation-open";
        open.dataset.id = conversation.id;

        const title = document.createElement("span");
        title.className = "conversation-title";
        title.textContent = conversation.title || "New conversation";

        const meta = document.createElement("span");
        meta.className = "conversation-meta";
        const when = formatHistoryTime(conversation.updatedAt);
        const count = conversation.messageCount === 1 ? "1 message" : conversation.messageCount + " messages";
        meta.textContent = when ? when + " - " + count : count;

        open.append(title, meta);

        const remove = document.createElement("button");
        remove.type = "button";
        remove.className = "conversation-delete";
        remove.dataset.id = conversation.id;
        remove.title = "Delete conversation";
        remove.setAttribute("aria-label", "Delete " + (conversation.title || "conversation"));
        remove.textContent = "x";

        item.append(open, remove);
        els.conversations.append(item);
    });
}

async function openConversation(id) {
    if (!id || state.busy) {
        return;
    }
    try {
        const data = await api("/api/conversations/" + encodeURIComponent(id));
        state.conversationId = data.id;
        persistConversation();
        switchPanel("chat-panel");
        els.chatMessages.innerHTML = "";
        const messages = (data.messages || []).filter((message) => message.role === "user" || message.role === "assistant");
        if (messages.length === 0) {
            showWelcome();
        } else {
            messages.forEach((message) => addMessage(message.role, message.content));
            scrollChat();
        }
        renderConversations();
    } catch (error) {
        addMessage("assistant", "Could not load that conversation: " + error.message, { error: true });
    }
}

async function deleteConversation(id) {
    if (!id || state.busy) {
        return;
    }
    if (!window.confirm("Delete this conversation and its memory?")) {
        return;
    }
    try {
        await api("/api/conversations/" + encodeURIComponent(id), { method: "DELETE" });
        if (state.conversationId === id) {
            resetChat();
        }
        await loadConversations();
    } catch (error) {
        addMessage("assistant", "Could not delete the conversation: " + error.message, { error: true });
    }
}

function scrollChat() {
    els.chatMessages.scrollTop = els.chatMessages.scrollHeight;
}

function addMessage(role, text, options = {}) {
    const wrapper = document.createElement("article");
    wrapper.className = "msg " + role + (options.error ? " error" : "");

    const meta = document.createElement("div");
    meta.className = "msg-meta";
    meta.textContent = role === "user" ? "You" : "Agent";
    if (options.toolCalls && options.toolCalls.length > 0) {
        const count = document.createElement("span");
        count.textContent = options.toolCalls.length + " tool call" + (options.toolCalls.length === 1 ? "" : "s");
        meta.append(count);
    }

    const bubble = document.createElement("div");
    bubble.className = "bubble";
    if (role === "user" || options.error) {
        bubble.textContent = text;
    } else {
        renderMarkdown(bubble, text);
    }

    if (options.toolCalls && options.toolCalls.length > 0) {
        bubble.append(renderToolCalls(options.toolCalls));
    }

    wrapper.append(meta, bubble);
    els.chatMessages.append(wrapper);
    scrollChat();
    return wrapper;
}

function renderToolCalls(calls) {
    const container = document.createElement("div");
    container.className = "tool-calls";

    calls.forEach((call) => {
        const details = document.createElement("details");
        details.className = "tool-chip";

        const summary = document.createElement("summary");
        const name = document.createElement("span");
        name.textContent = call.name;
        const origin = document.createElement("span");
        origin.className = "tool-origin";
        origin.textContent = call.origin;
        const duration = document.createElement("span");
        duration.className = "event-time";
        duration.textContent = call.durationMs + " ms";
        summary.append(name, origin, duration);

        const payload = document.createElement("div");
        payload.className = "tool-payload";
        payload.append(
            payloadBlock("Arguments", call.arguments),
            payloadBlock("Result" + (call.error ? " (error)" : ""), call.result)
        );

        details.append(summary, payload);
        container.append(details);
    });

    return container;
}

function payloadBlock(label, value) {
    const wrapper = document.createElement("div");
    const heading = document.createElement("label");
    heading.textContent = label;
    const pre = document.createElement("pre");
    pre.textContent = escapeText(value);
    wrapper.append(heading, pre);
    return wrapper;
}

function addThinking() {
    const wrapper = document.createElement("article");
    wrapper.className = "msg assistant";
    const meta = document.createElement("div");
    meta.className = "msg-meta";
    meta.textContent = "Agent";
    const bubble = document.createElement("div");
    bubble.className = "bubble";
    const spinner = document.createElement("span");
    spinner.className = "spinner";
    const label = document.createElement("span");
    label.textContent = " Working...";
    bubble.append(spinner, label);
    wrapper.append(meta, bubble);
    els.chatMessages.append(wrapper);
    scrollChat();
    return wrapper;
}

function setBusy(busy) {
    state.busy = busy;
    els.chatSend.disabled = busy;
    els.agentRun.disabled = busy;
}

async function sendChat(event) {
    event.preventDefault();
    const message = els.chatInput.value.trim();
    if (!message || state.busy) {
        return;
    }

    setBusy(true);
    els.chatInput.value = "";
    addMessage("user", message);
    const thinking = addThinking();

    try {
        const data = await api("/api/chat", {
            method: "POST",
            body: JSON.stringify({ message, conversationId: state.conversationId || null })
        });
        state.conversationId = data.conversationId;
        persistConversation();
        thinking.remove();
        addMessage("assistant", data.answer, { toolCalls: data.toolCalls || [] });
        loadConversations();
    } catch (error) {
        thinking.remove();
        addMessage("assistant", error.message, { error: true });
    } finally {
        setBusy(false);
        els.chatInput.focus();
    }
}

async function runAgents(event) {
    event.preventDefault();
    const task = els.agentInput.value.trim();
    if (!task || state.busy) {
        return;
    }

    setBusy(true);
    els.agentProgress.classList.remove("hidden");
    els.agentTimeline.className = "timeline";
    els.agentTimeline.innerHTML = "";
    els.agentResult.className = "result";
    els.agentResult.innerHTML = "";

    try {
        const data = await api("/api/agent/run", {
            method: "POST",
            body: JSON.stringify({ task, conversationId: state.conversationId || null })
        });
        state.conversationId = data.conversationId;
        persistConversation();
        renderPlan(data.plan);
        renderTimeline(data.events || [], data.toolCalls || []);
        renderMarkdown(els.agentResult, data.answer);
        loadConversations();
    } catch (error) {
        renderTimelineError(error.message);
    } finally {
        setBusy(false);
        els.agentProgress.classList.add("hidden");
    }
}

function renderPlan(plan) {
    if (!plan) {
        return;
    }
    const card = document.createElement("div");
    card.className = "plan-card";

    const heading = document.createElement("h4");
    heading.textContent = "Plan";
    const summary = document.createElement("p");
    summary.textContent = plan.summary || "";

    const steps = document.createElement("ol");
    steps.className = "plan-steps";
    (plan.steps || []).forEach((step) => {
        const item = document.createElement("li");
        const agent = document.createElement("span");
        agent.className = "plan-agent";
        agent.textContent = step.agent;
        item.append(agent, document.createTextNode(" - " + (step.instruction || "")));
        steps.append(item);
    });

    card.append(heading, summary, steps);
    els.agentTimeline.append(card);
}

function renderTimeline(events, toolCalls) {
    const entries = [];
    events.forEach((event) => entries.push({ at: event.at, kind: "event", event }));
    toolCalls.forEach((call) => entries.push({ at: call.at, kind: "tool", call }));
    entries.sort((left, right) => new Date(left.at) - new Date(right.at));

    if (entries.length === 0) {
        els.agentTimeline.className = "timeline empty";
        els.agentTimeline.textContent = "No events were recorded.";
        return;
    }

    entries.forEach((entry) => {
        if (entry.kind === "event") {
            els.agentTimeline.append(renderEvent(entry.event));
        } else {
            els.agentTimeline.append(renderToolEvent(entry.call));
        }
    });
}

function renderEvent(event) {
    const wrapper = document.createElement("div");
    wrapper.className = "event";
    wrapper.dataset.agent = event.agent || "orchestrator";

    const head = document.createElement("div");
    head.className = "event-head";
    const type = document.createElement("span");
    type.className = "event-type";
    type.textContent = (event.type || "event").replaceAll("_", " ");
    const agent = document.createElement("span");
    agent.className = "event-agent";
    agent.textContent = event.agent || "";
    const time = document.createElement("span");
    time.className = "event-time";
    time.textContent = formatTime(event.at) + (event.durationMs == null ? "" : " - " + event.durationMs + " ms");
    head.append(type, agent, time);

    const message = document.createElement("div");
    message.className = "event-message";
    message.textContent = event.message || "";

    wrapper.append(head, message);
    if (event.detail) {
        const details = document.createElement("details");
        details.className = "event-detail";
        const summary = document.createElement("summary");
        summary.textContent = "details";
        const pre = document.createElement("pre");
        pre.textContent = event.detail;
        details.append(summary, pre);
        wrapper.append(details);
    }
    return wrapper;
}

function renderToolEvent(call) {
    const wrapper = document.createElement("div");
    wrapper.className = "event";
    wrapper.dataset.agent = call.agent || "researcher";

    const head = document.createElement("div");
    head.className = "event-head";
    const type = document.createElement("span");
    type.className = "event-type";
    type.textContent = "tool call";
    const agent = document.createElement("span");
    agent.className = "event-agent";
    agent.textContent = call.agent + " / " + call.origin;
    const time = document.createElement("span");
    time.className = "event-time";
    time.textContent = formatTime(call.at) + " - " + call.durationMs + " ms";
    head.append(type, agent, time);

    const message = document.createElement("div");
    message.className = "event-message";
    message.textContent = call.name + " -> " + summarize(call.result, 160);
    if (call.error) {
        message.textContent = "ERROR: " + message.textContent;
    }

    const details = document.createElement("details");
    details.className = "event-detail";
    const summary = document.createElement("summary");
    summary.textContent = "arguments and result";
    const pre = document.createElement("pre");
    pre.textContent = "arguments:\n" + escapeText(call.arguments) + "\n\nresult:\n" + escapeText(call.result);
    details.append(summary, pre);

    wrapper.append(head, message, details);
    return wrapper;
}

function renderTimelineError(message) {
    els.agentTimeline.className = "timeline";
    els.agentTimeline.innerHTML = "";
    const wrapper = document.createElement("div");
    wrapper.className = "event";
    wrapper.dataset.agent = "orchestrator";
    const head = document.createElement("div");
    head.className = "event-head";
    const type = document.createElement("span");
    type.className = "event-type";
    type.textContent = "error";
    head.append(type);
    const body = document.createElement("div");
    body.className = "event-message";
    body.textContent = message;
    wrapper.append(head, body);
    els.agentTimeline.append(wrapper);
    els.agentResult.className = "result";
    els.agentResult.textContent = message;
}

function renderToolList(element, tools, emptyText) {
    element.innerHTML = "";
    if (!tools || tools.length === 0) {
        const item = document.createElement("li");
        item.className = "placeholder";
        item.textContent = emptyText;
        element.append(item);
        return;
    }
    tools.forEach((tool) => {
        const item = document.createElement("li");
        const name = document.createElement("strong");
        name.textContent = tool.name;
        const description = document.createElement("span");
        description.textContent = tool.description || "";
        item.append(name, description);
        element.append(item);
    });
}

function setMcpBadge(mcp) {
    state.mcp = mcp;
    els.mcpBadge.className = "badge";
    els.mcpError.textContent = mcp && mcp.error ? mcp.error : "";

    if (!mcp) {
        els.mcpBadge.classList.add("badge-muted");
        els.mcpBadge.textContent = "MCP: unknown";
        return;
    }
    if (mcp.state === "CONNECTED") {
        els.mcpBadge.classList.add("badge-ok");
        els.mcpBadge.textContent = "MCP: connected - " + (mcp.tools || []).length + " tools";
    } else if (mcp.state === "DISABLED") {
        els.mcpBadge.classList.add("badge-muted");
        els.mcpBadge.textContent = "MCP: disabled";
    } else if (mcp.state === "CONNECTING") {
        els.mcpBadge.classList.add("badge-warn");
        els.mcpBadge.textContent = "MCP: connecting...";
    } else {
        els.mcpBadge.classList.add("badge-warn");
        els.mcpBadge.textContent = "MCP: offline";
    }
}

async function loadTools() {
    try {
        const data = await api("/api/tools");
        renderToolList(els.localTools, (data.localTools || []).map((name) => ({ name })), "No local tools");
        const mcp = data.mcp || {};
        renderToolList(els.mcpTools, mcp.tools || [], "No MCP tools discovered");
        setMcpBadge(mcp);
    } catch (error) {
        setMcpBadge(null);
        els.mcpError.textContent = error.message;
        renderToolList(els.localTools, [], "Unable to load tools");
        renderToolList(els.mcpTools, [], "Unable to load tools");
    }
}

async function refreshMcp() {
    els.refreshMcp.disabled = true;
    try {
        const mcp = await api("/api/mcp/refresh", { method: "POST" });
        setMcpBadge(mcp);
        await loadTools();
    } catch (error) {
        els.mcpError.textContent = error.message;
    } finally {
        els.refreshMcp.disabled = false;
    }
}

function switchPanel(panelId) {
    document.querySelectorAll(".tab").forEach((tab) => {
        tab.classList.toggle("active", tab.dataset.panel === panelId);
    });
    els.chatPanel.classList.toggle("hidden", panelId !== "chat-panel");
    els.agentPanel.classList.toggle("hidden", panelId !== "agent-panel");
}

function showWelcome() {
    addMessage("assistant", "New conversation started. I remember earlier questions in this chat, so you can ask follow-ups. Try: **What is the weather in Berlin for the next 3 days?**");
}

function resetChat() {
    if (state.busy) {
        return;
    }
    state.conversationId = "";
    persistConversation();
    els.chatMessages.innerHTML = "";
    els.agentTimeline.className = "timeline empty";
    els.agentTimeline.textContent = "Run the agents to see the plan, delegations and tool calls.";
    els.agentResult.className = "result empty";
    els.agentResult.textContent = "No run yet.";
    renderConversations();
    showWelcome();
    els.chatInput.focus();
}

function usePrompt(target, text) {
    if (target === "agent") {
        switchPanel("agent-panel");
        els.agentInput.value = text;
        els.agentInput.focus();
    } else {
        switchPanel("chat-panel");
        els.chatInput.value = text;
        els.chatInput.focus();
    }
}

async function init() {
    updateConversationLabel();
    els.chatForm.addEventListener("submit", sendChat);
    els.agentForm.addEventListener("submit", runAgents);
    els.refreshMcp.addEventListener("click", refreshMcp);
    els.newChat.addEventListener("click", resetChat);

    els.conversations.addEventListener("click", (event) => {
        const remove = event.target.closest(".conversation-delete");
        if (remove) {
            event.stopPropagation();
            deleteConversation(remove.dataset.id);
            return;
        }
        const open = event.target.closest(".conversation-open");
        if (open) {
            openConversation(open.dataset.id);
        }
    });

    document.querySelectorAll(".tab").forEach((tab) => {
        tab.addEventListener("click", () => switchPanel(tab.dataset.panel));
    });

    document.querySelectorAll(".prompt-chip").forEach((chip) => {
        chip.addEventListener("click", () => usePrompt(chip.dataset.target, chip.textContent.trim()));
    });

    await loadConversations();
    const stored = state.conversationId;
    if (stored && state.conversations.some((conversation) => conversation.id === stored)) {
        await openConversation(stored);
    } else {
        state.conversationId = "";
        persistConversation();
        renderConversations();
        showWelcome();
    }

    loadTools();
    els.chatInput.focus();
}

init();
