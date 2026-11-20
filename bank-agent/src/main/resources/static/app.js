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
    voiceBubbleByRun: new Map(),
    voice: { stt: false, tts: false },
    voiceMode: "server",
    sending: false,
    notifications: [],
    notificationCursor: 0
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
    micButton: document.getElementById("mic-button"),
    notificationsButton: document.getElementById("notifications-button"),
    notificationBadge: document.getElementById("notification-badge"),
    notificationsModal: document.getElementById("notifications-modal"),
    notificationsContent: document.getElementById("notifications-content"),
    notificationsClose: document.getElementById("notifications-close"),
    notificationsMarkAll: document.getElementById("notifications-mark-all"),
    toast: document.getElementById("toast")
};

let mediaRecorder = null;
let recordedChunks = [];
let recognition = null;
let recording = false;
let voiceCapture = null; // active browser-STT session: accumulated text + live preview bubble

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
            els.chatTitle.textContent = "Select or start a conversation";
            els.chatSub.textContent = "";
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
    els.micButton.disabled = Boolean(active) || state.voiceMode === "none";
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
        await startChatRun(started, message, false);
    } catch (ex) {
        toast(ex.message, true);
        appendMessage("assistant", "⚠️ " + ex.message);
    } finally {
        state.sending = false;
    }
}

function playRunAudio(runId, text) {
    if (state.voice.tts) {
        const audio = new Audio("/api/runs/" + encodeURIComponent(runId) + "/audio");
        audio.play().catch(() => speakText(text));
        return;
    }
    speakText(text);
}

function speakText(text) {
    if (!("speechSynthesis" in window) || !text) return;
    window.speechSynthesis.cancel();
    window.speechSynthesis.speak(new SpeechSynthesisUtterance(text));
}

async function loadVoiceStatus() {
    try {
        state.voice = await api("/api/voice/status");
    } catch {
        state.voice = { stt: false, tts: false };
    }
    const hasBrowserRecognition = Boolean(window.SpeechRecognition || window.webkitSpeechRecognition);
    state.voiceMode = state.voice.stt ? "server" : (hasBrowserRecognition ? "browser" : "none");
    els.micButton.title = state.voiceMode === "server"
        ? "Record a voice message (server speech-to-text)"
        : state.voiceMode === "browser"
            ? "Record a voice message (browser speech-to-text, no API key needed)"
            : "Voice unavailable: enable server voice or use Chrome/Edge";
}

function setRecordingUi(on) {
    recording = on;
    els.micButton.classList.toggle("recording", on);
    els.micButton.textContent = on ? "⏹" : "🎙";
}

function stopRecording() {
    if (state.voiceMode === "browser" && voiceCapture) {
        // User pressed ⏹: finalize whatever was captured and send it.
        voiceCapture.stopping = true;
        if (voiceCapture.restartTimer) clearTimeout(voiceCapture.restartTimer);
        setRecordingUi(false);
        if (recognition) {
            try { recognition.stop(); } catch { finalizeVoiceCapture(); }
        } else {
            finalizeVoiceCapture();
        }
        return;
    }
    if (mediaRecorder && mediaRecorder.state !== "inactive") mediaRecorder.stop();
}

async function toggleMic() {
    if (recording) {
        stopRecording();
        return;
    }
    if (state.voiceMode === "browser") {
        startBrowserRecognition();
        return;
    }
    if (state.voiceMode === "none") {
        toast("No voice available: set BANK_VOICE_ENABLED+AUDIO_API_KEY on the server, or use Chrome/Edge (browser speech).", true);
        return;
    }
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        toast("Voice recording needs a secure context (HTTPS or localhost) and a browser with getUserMedia.", true);
        return;
    }
    try {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        recordedChunks = [];
        mediaRecorder = new MediaRecorder(stream);
        mediaRecorder.ondataavailable = (ev) => { if (ev.data && ev.data.size) recordedChunks.push(ev.data); };
        mediaRecorder.onstop = () => {
            stream.getTracks().forEach((t) => t.stop());
            setRecordingUi(false);
            const blob = new Blob(recordedChunks, { type: mediaRecorder.mimeType || "audio/webm" });
            sendVoice(blob);
        };
        mediaRecorder.start();
        setRecordingUi(true);
    } catch (ex) {
        toast("Microphone access denied: " + ex.message, true);
    }
}

function updateVoicePreview() {
    if (!voiceCapture) return;
    const text = (voiceCapture.final + (voiceCapture.interim ? " " + voiceCapture.interim : "")).trim();
    if (!text && !voiceCapture.preview) return;
    if (!voiceCapture.preview) {
        voiceCapture.preview = appendMessage("user", "", { pending: true });
    }
    voiceCapture.preview.querySelector(".message-body").textContent = "🎙 " + (text || "listening…");
}

function finalizeVoiceCapture() {
    const cap = voiceCapture;
    voiceCapture = null;
    recognition = null;
    if (!cap) return;
    if (cap.restartTimer) clearTimeout(cap.restartTimer);
    const text = (cap.final + (cap.interim ? " " + cap.interim : "")).trim();
    if (cap.preview) cap.preview.remove();
    if (text) {
        sendBrowserVoice(text);
    } else {
        toast("No transcript captured. Press 🎙, speak, then press ⏹ when you're done.", true);
    }
}

function startBrowserRecognition() {
    const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SR) {
        state.voiceMode = "none";
        toast("This browser has no speech recognition; use Chrome or Edge.", true);
        return;
    }
    recognition = new SR();
    // Pin to a locale Chrome's speech service always has a model for. Using
    // navigator.language can yield a locale that produces no results at all.
    recognition.lang = "en-US";
    // continuous = keep listening through pauses instead of ending after ~3-5s.
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.maxAlternatives = 1;

    voiceCapture = { final: "", interim: "", preview: null, stopping: false, restarts: 0, restartTimer: null };
    const vlog = (event, detail) => console.log("[voice]", event, detail === undefined ? "" : detail);

    recognition.onstart = () => vlog("onstart");
    recognition.onaudiostart = () => vlog("audiostart");
    recognition.onspeechstart = () => vlog("speechstart");
    recognition.onspeechend = () => vlog("speechend");
    recognition.onresult = (ev) => {
        // With continuous=true, ev.results holds every result from this session;
        // rebuild the full text each time instead of taking only the last one.
        let final = "";
        let interim = "";
        for (let i = 0; i < ev.results.length; i++) {
            const result = ev.results[i];
            if (result.isFinal) final += result[0].transcript;
            else interim += result[0].transcript;
        }
        voiceCapture.final = final.trim();
        voiceCapture.interim = interim.trim();
        vlog("result", voiceCapture.final + " | " + voiceCapture.interim);
        updateVoicePreview();
    };
    recognition.onerror = (ev) => {
        vlog("error", ev.error);
        if (ev.error === "no-speech") {
            // Expected while the user is thinking; stay in listening mode.
            return;
        }
        if (ev.error !== "aborted") {
            toast("Speech recognition error: " + ev.error, true);
        }
    };
    recognition.onend = () => {
        vlog("onend", voiceCapture && voiceCapture.stopping ? "stopped by user" : "ended by browser");
        if (!voiceCapture) return;
        if (voiceCapture.stopping) {
            finalizeVoiceCapture();
            return;
        }
        // Chrome may still end the session after a pause. Restart it so the mic
        // keeps waiting for the user until they explicitly press ⏹.
        if (recording && voiceCapture.restarts < 20) {
            voiceCapture.restarts++;
            voiceCapture.restartTimer = setTimeout(() => {
                if (!recording || !voiceCapture) return;
                try {
                    recognition.start();
                    vlog("restarted", voiceCapture.restarts);
                } catch (ex) {
                    vlog("restart failed", ex.message);
                    finalizeVoiceCapture();
                }
            }, 150);
        } else {
            setRecordingUi(false);
            finalizeVoiceCapture();
        }
    };

    if (navigator.permissions && navigator.permissions.query) {
        navigator.permissions.query({ name: "microphone" }).then((result) => {
            vlog("mic permission", result.state);
            if (result.state === "denied") {
                toast("Microphone access is blocked. Allow it for this site (and in macOS System Settings → Privacy & Security → Microphone).", true);
            }
        }).catch(() => {});
    }

    updateVoicePreview(); // show "🎙 listening…" immediately
    try {
        recognition.start();
        setRecordingUi(true);
    } catch (ex) {
        setRecordingUi(false);
        vlog("start failed", ex.message);
        toast("Could not start speech recognition: " + ex.message, true);
    }
}

async function sendBrowserVoice(text) {
    if (!state.userId || state.sending) return;
    if (state.conversationId && state.activeByConv.has(state.conversationId)) {
        toast("This conversation is still working on the previous question.", true);
        return;
    }
    state.sending = true;
    try {
        const started = await api("/api/chat", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                userId: state.userId,
                conversationId: state.conversationId || null,
                message: text
            })
        });
        await startChatRun(started, "🎙 " + text, false);
    } catch (ex) {
        toast(ex.message, true);
        appendMessage("assistant", "⚠️ " + ex.message);
    } finally {
        state.sending = false;
    }
}

async function startChatRun(started, userBubbleText, voiceRun) {
    if (started.conversationId !== state.conversationId) {
        state.conversationId = started.conversationId;
        localStorage.setItem(CONV_KEY, started.conversationId);
    }
    const bubble = appendMessage("user", userBubbleText);
    if (voiceRun) state.voiceBubbleByRun.set(started.runId, bubble);
    startRunPoll(started.runId);
    await loadConversations();
}

async function sendVoice(blob) {
    if (!state.userId || state.sending) return;
    if (state.conversationId && state.activeByConv.has(state.conversationId)) {
        toast("This conversation is still working on the previous question.", true);
        return;
    }
    state.sending = true;
    try {
        const form = new FormData();
        form.append("audio", blob, "voice-message.webm");
        form.append("userId", state.userId);
        if (state.conversationId) form.append("conversationId", state.conversationId);
        const started = await api("/api/chat/voice", { method: "POST", body: form });
        // The server transcribes before responding, so show the real question right away.
        await startChatRun(started, "🎙 " + (started.message || "transcribing…"), true);
    } catch (ex) {
        if (ex.message && ex.message.includes("disabled")) {
            state.voiceMode = "browser";
            state.voice.stt = false;
            toast("Server voice is disabled; switched to browser speech-to-text. Click the mic again.", true);
        } else {
            toast(ex.message, true);
            appendMessage("assistant", "⚠️ " + ex.message);
        }
    } finally {
        state.sending = false;
    }
}

function startAnswerStream(runId, poll) {
    if (!window.EventSource) return null;
    let current = "";
    let source;
    try {
        source = new EventSource("/api/runs/" + encodeURIComponent(runId) + "/stream");
    } catch {
        return null;
    }
    source.addEventListener("draft", () => {
        current = "";
        if (!poll.streamBubble) {
            poll.streamBubble = appendMessage("assistant", "", { pending: true });
        } else {
            poll.streamBubble.querySelector(".message-body").innerHTML = "";
        }
    });
    source.addEventListener("delta", (ev) => {
        let delta = "";
        try { delta = JSON.parse(ev.data).delta || ""; } catch { delta = ev.data; }
        current += delta;
        if (poll.streamBubble) {
            poll.streamBubble.querySelector(".message-body").innerHTML = renderMarkdown(current);
            scrollToBottom();
        }
    });
    source.addEventListener("done", () => source.close());
    source.onerror = () => {
        source.close();
        if (poll.stream === source) poll.stream = null;
    };
    return source;
}

function startRunPoll(runId) {
    if (state.runPolls.has(runId)) return;
    showTrace();
    const poll = { runId: runId, timer: null, stream: null, streamBubble: null };
    const tick = async () => {
        try {
            const run = await api("/api/runs/" + encodeURIComponent(runId));
            renderTrace(run);
            // Voice runs know the transcript as soon as the run exists. Show it on the
            // first poll rather than waiting for the answer (fallback for older servers
            // that do not return the transcript in the voice-chat response).
            const voiceBubble = state.voiceBubbleByRun.get(runId);
            if (voiceBubble && run.message) {
                voiceBubble.querySelector(".message-body").textContent = "🎙 " + run.message;
            }
            if (run.status === "COMPLETED") {
                stopRunPoll(runId);
                const answer = run.answer || "(empty answer)";
                let bubble;
                if (poll.streamBubble) {
                    bubble = poll.streamBubble;
                    const body = bubble.querySelector(".message-body");
                    body.innerHTML = renderMarkdown(answer);
                    body.classList.remove("pending");
                } else {
                    bubble = appendMessage("assistant", answer);
                }
                const play = el("button", "ghost play-audio", "🔊");
                play.type = "button";
                play.title = "Play the answer";
                play.addEventListener("click", () => playRunAudio(runId, answer));
                bubble.appendChild(play);
                state.voiceBubbleByRun.delete(runId);
                playRunAudio(runId, answer);
                hideTraceSoon();
                await loadConversations();
            } else if (run.status === "FAILED") {
                stopRunPoll(runId);
                state.voiceBubbleByRun.delete(runId);
                appendMessage("assistant", "⚠️ Something went wrong: " + (run.error || "unknown error"));
                await loadConversations();
            }
        } catch (ex) {
            stopRunPoll(runId);
            toast(ex.message, true);
        }
    };
    poll.timer = setInterval(tick, 1500);
    poll.stream = startAnswerStream(runId, poll);
    state.runPolls.set(runId, poll);
    tick();
}

function stopRunPoll(runId) {
    const poll = state.runPolls.get(runId);
    if (poll) {
        clearInterval(poll.timer);
        if (poll.stream) poll.stream.close();
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

async function loadNotifications(silent = true) {
    if (!state.userId) return;
    try {
        const page = await api("/api/notifications?userId=" + encodeURIComponent(state.userId) +
            "&sinceId=" + state.notificationCursor + "&limit=100");
        for (const item of page.items) {
            if (!state.notifications.some((n) => n.id === item.id)) state.notifications.push(item);
        }
        state.notifications.sort((a, b) => b.id - a.id);
        for (const item of page.items) state.notificationCursor = Math.max(state.notificationCursor, item.id);
        els.notificationBadge.textContent = page.unreadCount > 0 ? String(page.unreadCount) : "";
        renderNotifications();
    } catch (ex) {
        if (!silent) toast(ex.message, true);
    }
}

function notificationDetail(detail) {
    if (!detail) return "";
    try {
        const value = JSON.parse(detail);
        const parts = [];
        if (value.amount && value.currency) parts.push(value.amount + " " + value.currency);
        if (value.oldBalance && value.newBalance) parts.push(value.oldBalance + " → " + value.newBalance);
        if (value.txnId) parts.push(value.txnId);
        if (value.text) parts.push(String(value.text).slice(0, 120));
        return parts.join(" · ");
    } catch { return ""; }
}

function renderNotifications() {
    els.notificationsContent.innerHTML = "";
    if (!state.notifications.length) {
        els.notificationsContent.appendChild(el("p", "hint", "No notifications yet."));
        return;
    }
    for (const item of state.notifications) {
        const row = el("div", "notification-row" + (item.read ? "" : " unread"));
        const head = el("div", "notification-head");
        if (!item.read) head.appendChild(el("span", "notification-dot", "●"));
        head.appendChild(el("strong", "", item.title));
        row.appendChild(head);
        row.appendChild(el("p", "", item.body));
        const detail = notificationDetail(item.detail);
        if (detail) row.appendChild(el("p", "hint", detail));
        if (!item.read) {
            const markRead = el("button", "ghost", "Mark read");
            markRead.type = "button";
            markRead.addEventListener("click", async () => {
                try {
                    await api("/api/notifications/" + item.id + "/read?userId=" +
                        encodeURIComponent(state.userId), { method: "POST" });
                    item.read = true;
                    await loadNotifications();
                } catch (ex) { toast(ex.message, true); }
            });
            row.appendChild(markRead);
        }
        const open = el("button", "ghost", "Open chat");
        open.type = "button";
        open.addEventListener("click", async () => {
            if (!item.read) {
                await api("/api/notifications/" + item.id + "/read?userId=" +
                    encodeURIComponent(state.userId), { method: "POST" }).catch(() => null);
            }
            els.notificationsModal.classList.add("hidden");
            await openConversation(item.conversationId);
            await loadNotifications();
        });
        row.appendChild(open);
        els.notificationsContent.appendChild(row);
    }
}

els.userSelect.addEventListener("change", async () => {
    state.userId = els.userSelect.value;
    localStorage.setItem(USER_KEY, state.userId);
    state.conversationId = "";
    localStorage.removeItem(CONV_KEY);
    state.notifications = [];
    state.notificationCursor = 0;
    els.notificationBadge.textContent = "";
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
els.micButton.addEventListener("click", toggleMic);
els.refreshMcp.addEventListener("click", async () => { await loadMcpStatus(); toast("MCP status refreshed"); });
els.toolsButton.addEventListener("click", showTools);
els.toolsClose.addEventListener("click", () => els.toolsModal.classList.add("hidden"));
els.toolsModal.addEventListener("click", (ev) => { if (ev.target === els.toolsModal) els.toolsModal.classList.add("hidden"); });
els.notificationsButton.addEventListener("click", () => {
    void loadNotifications();
    els.notificationsModal.classList.remove("hidden");
});
els.notificationsClose.addEventListener("click", () => els.notificationsModal.classList.add("hidden"));
els.notificationsModal.addEventListener("click", (ev) => {
    if (ev.target === els.notificationsModal) els.notificationsModal.classList.add("hidden");
});
els.notificationsMarkAll.addEventListener("click", async () => {
    try {
        await api("/api/notifications/read-all?userId=" + encodeURIComponent(state.userId), { method: "POST" });
        state.notifications.forEach((item) => { item.read = true; });
        await loadNotifications();
    } catch (ex) { toast(ex.message, true); }
});
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
    loadVoiceStatus();
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
    void loadNotifications();
    setInterval(() => loadNotifications(true), 10_000);
})();
