const USER_KEY = "bankUserId";
const CONV_KEY = "bankConversationId";

const state = {
    users: [],
    userId: localStorage.getItem(USER_KEY) || "",
    conversations: [],
    conversationId: localStorage.getItem(CONV_KEY) || "",
    documents: [],
    runPolls: new Map(),
    activeByConv: new Map(),
    sending: false
};

const els = {
    userSelect: document.getElementById("user-select"),
    mcpBadge: document.getElementById("mcp-badge"),
    refreshMcp: document.getElementById("refresh-mcp"),
    toolsButton: document.getElementById("tools-button"),
    toolsModal: document.getElementById("tools-modal"),
    toolsClose: document.getElementById("tools-close"),
    toolsContent: document.getElementById("tools-content"),
    newConversation: document.getElementById("new-conversation"),
    conversationList: document.getElementById("conversation-list"),
    uploadScope: document.getElementById("upload-scope"),
    attachButton: document.getElementById("attach-button"),
    fileInput: document.getElementById("file-input"),
    documentList: document.getElementById("document-list"),
    chatTitle: document.getElementById("chat-title"),
    chatSub: document.getElementById("chat-sub"),
    workingBadge: document.getElementById("working-badge"),
    messages: document.getElementById("messages"),
    tracePanel: document.getElementById("trace-panel"),
    traceToggle: document.getElementById("trace-toggle"),
    traceEvents: document.getElementById("trace-events"),
    traceTools: document.getElementById("trace-tools"),
    composer: document.getElementById("composer"),
    chatInput: document.getElementById("chat-input"),
    sendButton: document.getElementById("send-button"),
    toast: document.getElementById("toast")
};

async function api(path, options = {}) {
    const response = await fetch(path, options);
    let data = null;
    const text = await response.text();
    if (text) {
        try { data = JSON.parse(text); } catch { data = { error: text }; }
    }
    if (!response.ok) {
        throw new Error((data && data.error) || ("Request failed: " + response.status));
    }
    return data;
}

function toast(message, isError = false) {
    els.toast.textContent = message;
    els.toast.classList.remove("hidden");
    els.toast.classList.toggle("error", isError);
    clearTimeout(toast._timer);
    toast._timer = setTimeout(() => els.toast.classList.add("hidden"), 4000);
}

function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
}

function renderMarkdown(text) {
    if (!text) return "";
    return DOMPurify.sanitize(marked.parse(text));
}

function fmtTime(iso) {
    if (!iso) return "";
    const d = new Date(iso);
    return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

async function loadUsers() {
    state.users = await api("/api/users");
    els.userSelect.innerHTML = "";
    for (const user of state.users) {
        const option = el("option", "", user.displayName + " (" + user.id + ")");
        option.value = user.id;
        els.userSelect.appendChild(option);
    }
    if (!state.userId || !state.users.some(u => u.id === state.userId)) {
        state.userId = state.users[0] ? state.users[0].id : "";
    }
    els.userSelect.value = state.userId;
    localStorage.setItem(USER_KEY, state.userId);
}

async function loadMcpStatus() {
    try {
        const status = await api("/api/mcp/status");
        state.mcp = status;
        els.mcpBadge.textContent = "MCP: " + status.state + (status.tools && status.tools.length
            ? " (" + status.tools.length + " tools)" : "");
        els.mcpBadge.className = "badge " + (status.state === "CONNECTED" ? "badge-ok" : "badge-warn");
    } catch (ex) {
        els.mcpBadge.textContent = "MCP: error";
        els.mcpBadge.className = "badge badge-warn";
    }
}

async function loadConversations(silent = false) {
    if (!state.userId) return;
    try {
        state.conversations = await api("/api/conversations?userId=" + encodeURIComponent(state.userId));
    } catch (ex) {
        if (!silent) toast(ex.message, true);
        return;
    }
    state.activeByConv = new Map();
    for (const conv of state.conversations) {
        if (conv.active && conv.activeRunId) state.activeByConv.set(conv.id, conv.activeRunId);
    }
    renderConversationList();
    updateWorkingBadge();
    // If the open conversation started working elsewhere (or after a reload), resume polling it.
    if (state.conversationId) {
        const runId = state.activeByConv.get(state.conversationId);
        if (runId && !state.runPolls.has(runId)) startRunPoll(runId);
    }
}

function renderConversationList() {
    els.conversationList.innerHTML = "";
    if (!state.conversations.length) {
        els.conversationList.appendChild(el("p", "hint", "No conversations yet."));
        return;
    }
    for (const conv of state.conversations) {
        const item = el("div", "conversation-item" + (conv.id === state.conversationId ? " active" : ""));
        const main = el("button", "conversation-main", "");
        main.type = "button";
        main.appendChild(el("span", "conversation-title", conv.title || "New conversation"));
        const meta = el("span", "conversation-meta",
            conv.messageCount + " msg" + (conv.active ? " · working…" : ""));
        main.appendChild(meta);
        if (conv.active) main.appendChild(el("span", "badge badge-working small", "⏳"));
        main.addEventListener("click", () => openConversation(conv.id));
        item.appendChild(main);

        const del = el("button", "ghost conversation-delete", "✕");
        del.type = "button";
        del.title = "Delete conversation";
        del.addEventListener("click", (ev) => { ev.stopPropagation(); deleteConversation(conv.id); });
        item.appendChild(del);
        els.conversationList.appendChild(item);
    }
}

async function newConversation() {
    try {
        const created = await api("/api/conversations", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ userId: state.userId })
        });
        await loadConversations();
        await openConversation(created.id);
    } catch (ex) {
        toast(ex.message, true);
    }
}

async function deleteConversation(id) {
    if (!confirm("Delete this conversation and its memory?")) return;
    try {
        await api("/api/conversations/" + encodeURIComponent(id) + "?userId=" + encodeURIComponent(state.userId),
            { method: "DELETE" });
        if (state.conversationId === id) {
            state.conversationId = "";
            localStorage.removeItem(CONV_KEY);
            els.messages.innerHTML = "";
            els.documentList.innerHTML = "";
            hideTrace();
            updateWorkingBadge();
        }
        await loadConversations();
    } catch (ex) {
        toast(ex.message, true);
    }
}

async function openConversation(id) {
    state.conversationId = id;
    localStorage.setItem(CONV_KEY, id);
    try {
        const detail = await api("/api/conversations/" + encodeURIComponent(id) +
            "?userId=" + encodeURIComponent(state.userId));
        els.chatTitle.textContent = detail.title || "New conversation";
        els.chatSub.textContent = (detail.messages ? detail.messages.length : 0) + " messages";
        state.documents = detail.documents || [];
        renderMessages(detail.messages || []);
        renderDocuments();
        updateWorkingBadge();
        if (detail.active && detail.activeRunId) {
            startRunPoll(detail.activeRunId);
        }
        await loadConversations();
    } catch (ex) {
        toast(ex.message, true);
    }
}

function renderMessages(messages) {
    els.messages.innerHTML = "";
    if (!messages.length) {
        els.messages.appendChild(el("p", "hint", "Ask something about your accounts, spending, income, transactions or bank policies."));
        return;
    }
    for (const message of messages) {
        appendMessage(message.role, message.content);
    }
    scrollToBottom();
}

function appendMessage(role, content, opts = {}) {
    const bubble = el("div", "message " + role);
    const body = el("div", "message-body");
    if (role === "assistant") {
        body.innerHTML = renderMarkdown(content);
    } else {
        body.textContent = content;
    }
    if (opts.pending) body.classList.add("pending");
    bubble.appendChild(body);
    els.messages.appendChild(bubble);
    scrollToBottom();
    return bubble;
}

function scrollToBottom() {
    els.messages.scrollTop = els.messages.scrollHeight;
}

function updateWorkingBadge() {
    const active = state.conversationId && state.activeByConv.has(state.conversationId);
    els.workingBadge.classList.toggle("hidden", !active);
    els.sendButton.disabled = Boolean(active);
    els.chatInput.placeholder = active
        ? "Waiting for the answer to your previous question…"
        : "Ask about your balance, spending, income, last transactions, card limits, bank fees… or upload a file first";
}

async function sendMessage(event) {
    event.preventDefault();
    const message = els.chatInput.value.trim();
    if (!message || !state.userId) return;
    if (state.conversationId && state.activeByConv.has(state.conversationId)) {
        toast("This conversation is still working on the previous question.", true);
        return;
    }
    els.chatInput.value = "";
    state.sending = true;
    try {
        const started = await api("/api/chat", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                userId: state.userId,
                conversationId: state.conversationId || null,
                message: message
            })
        });
        if (started.conversationId !== state.conversationId) {
            state.conversationId = started.conversationId;
            localStorage.setItem(CONV_KEY, started.conversationId);
        }
        appendMessage("user", message);
        startRunPoll(started.runId);
        await loadConversations();
    } catch (ex) {
        toast(ex.message, true);
        appendMessage("assistant", "⚠️ " + ex.message);
    } finally {
        state.sending = false;
    }
}

function startRunPoll(runId) {
    if (state.runPolls.has(runId)) return;
    showTrace();
    const poll = { runId: runId, timer: null };
    const tick = async () => {
        try {
            const run = await api("/api/runs/" + encodeURIComponent(runId));
            renderTrace(run);
            if (run.status === "COMPLETED") {
                stopRunPoll(runId);
                appendMessage("assistant", run.answer || "(empty answer)");
                hideTraceSoon();
                await loadConversations();
            } else if (run.status === "FAILED") {
                stopRunPoll(runId);
                appendMessage("assistant", "⚠️ Something went wrong: " + (run.error || "unknown error"));
                await loadConversations();
            }
        } catch (ex) {
            stopRunPoll(runId);
            toast(ex.message, true);
        }
    };
    poll.timer = setInterval(tick, 1500);
    state.runPolls.set(runId, poll);
    tick();
}

function stopRunPoll(runId) {
    const poll = state.runPolls.get(runId);
    if (poll) {
        clearInterval(poll.timer);
        state.runPolls.delete(runId);
    }
}

function showTrace() {
    els.tracePanel.classList.remove("hidden");
}

function hideTrace() {
    els.tracePanel.classList.add("hidden");
    els.traceEvents.innerHTML = "";
    els.traceTools.innerHTML = "";
}

function hideTraceSoon() {
    // Keep the trace visible so the user can inspect what happened; they can hide it.
}

function renderTrace(run) {
    showTrace();
    els.traceEvents.innerHTML = "";
    for (const ev of run.events || []) {
        const row = el("div", "trace-event");
        row.appendChild(el("span", "trace-time", fmtTime(ev.at)));
        row.appendChild(el("span", "trace-agent", ev.agent || ""));
        row.appendChild(el("span", "trace-message", ev.message || ev.type));
        if (ev.detail) {
            const detail = el("div", "trace-detail", ev.detail);
            row.appendChild(detail);
        }
        els.traceEvents.appendChild(row);
    }
    els.traceTools.innerHTML = "";
    for (const call of run.toolCalls || []) {
        const row = el("div", "trace-tool" + (call.error ? " error" : ""));
        const head = el("div", "trace-tool-head",
            (call.origin || "") + " · " + (call.name || "") + " · " + (call.durationMs ?? 0) + " ms");
        row.appendChild(head);
        if (call.arguments) row.appendChild(el("pre", "trace-args", call.arguments));
        if (call.result) row.appendChild(el("pre", "trace-result", call.result));
        els.traceTools.appendChild(row);
    }
    els.traceEvents.scrollTop = els.traceEvents.scrollHeight;
}

async function refreshDocuments() {
    if (!state.conversationId) {
        state.documents = await api("/api/documents?userId=" + encodeURIComponent(state.userId));
    } else {
        state.documents = await api("/api/documents?userId=" + encodeURIComponent(state.userId) +
            "&conversationId=" + encodeURIComponent(state.conversationId));
    }
    renderDocuments();
}

function renderDocuments() {
    els.documentList.innerHTML = "";
    if (!state.documents.length) {
        els.documentList.appendChild(el("p", "hint", "No files uploaded yet."));
        return;
    }
    for (const doc of state.documents) {
        const row = el("div", "document-item");
        const label = el("span", "document-name",
            doc.filename + " (" + (doc.scope === "chat" ? "this chat" : "all my chats") + ", " + doc.chunks + " chunks)");
        row.appendChild(label);
        const del = el("button", "ghost conversation-delete", "✕");
        del.type = "button";
        del.title = "Delete document";
        del.addEventListener("click", async () => {
            try {
                await api("/api/documents/" + encodeURIComponent(doc.id) + "?userId=" + encodeURIComponent(state.userId),
                    { method: "DELETE" });
                toast("Document deleted");
                await refreshDocuments();
            } catch (ex) {
                toast(ex.message, true);
            }
        });
        row.appendChild(del);
        els.documentList.appendChild(row);
    }
}

async function uploadFile(file) {
    if (!state.userId) return;
    const scope = els.uploadScope.value;
    if (scope === "chat" && !state.conversationId) {
        toast("Start or select a conversation first to attach a file to this chat.", true);
        return;
    }
    const form = new FormData();
    form.append("file", file);
    form.append("userId", state.userId);
    form.append("scope", scope);
    if (scope === "chat") form.append("conversationId", state.conversationId);
    try {
        const info = await api("/api/documents", { method: "POST", body: form });
        toast("Indexed '" + info.filename + "' into " + info.chunks + " chunk(s)");
        await refreshDocuments();
    } catch (ex) {
        toast(ex.message, true);
    }
}

async function showTools() {
    els.toolsModal.classList.remove("hidden");
    els.toolsContent.innerHTML = "<p class='hint'>Loading…</p>";
    try {
        const data = await api("/api/tools");
        let html = "<h3>Local tools</h3><ul>";
        for (const name of data.localTools || []) html += "<li><code>" + name + "</code></li>";
        html += "</ul><h3>MCP server: " + (data.mcp ? data.mcp.state : "?") + "</h3>";
        if (data.mcp && data.mcp.error) html += "<p class='hint'>" + data.mcp.error + "</p>";
        html += "<ul>";
        for (const tool of (data.mcp && data.mcp.tools) || []) {
            html += "<li><code>" + tool.name + "</code> — " + (tool.description || "") + "</li>";
        }
        html += "</ul>";
        els.toolsContent.innerHTML = html;
    } catch (ex) {
        els.toolsContent.innerHTML = "<p class='hint'>" + ex.message + "</p>";
    }
}

els.userSelect.addEventListener("change", async () => {
    state.userId = els.userSelect.value;
    localStorage.setItem(USER_KEY, state.userId);
    state.conversationId = "";
    localStorage.removeItem(CONV_KEY);
    els.messages.innerHTML = "";
    hideTrace();
    await loadConversations();
    if (state.conversations.length) {
        await openConversation(state.conversations[0].id);
    } else {
        els.chatTitle.textContent = "Select or start a conversation";
        els.chatSub.textContent = "";
        renderDocuments();
    }
});

els.newConversation.addEventListener("click", newConversation);
els.composer.addEventListener("submit", sendMessage);
els.refreshMcp.addEventListener("click", async () => { await loadMcpStatus(); toast("MCP status refreshed"); });
els.toolsButton.addEventListener("click", showTools);
els.toolsClose.addEventListener("click", () => els.toolsModal.classList.add("hidden"));
els.toolsModal.addEventListener("click", (ev) => { if (ev.target === els.toolsModal) els.toolsModal.classList.add("hidden"); });
els.traceToggle.addEventListener("click", () => {
    const hidden = els.traceEvents.classList.toggle("hidden");
    els.traceTools.classList.toggle("hidden", hidden);
    els.traceToggle.textContent = hidden ? "Show" : "Hide";
});
els.attachButton.addEventListener("click", () => els.fileInput.click());
els.fileInput.addEventListener("change", () => {
    if (els.fileInput.files.length) uploadFile(els.fileInput.files[0]);
    els.fileInput.value = "";
});

(async function init() {
    await loadUsers();
    loadMcpStatus();
    await loadConversations();
    if (state.conversationId) {
        await openConversation(state.conversationId);
    } else if (state.conversations.length) {
        await openConversation(state.conversations[0].id);
    } else {
        renderDocuments();
    }
    setInterval(() => loadConversations(true), 3000);
})();
