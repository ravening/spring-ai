(() => {
    "use strict";

    const STORAGE = {
        current: "deeprelay.currentConversation",
        rag: "deeprelay.ragEnabled",
        theme: "deeprelay.theme",
        sources: (id) => `deeprelay.sources.${id}`,
    };

    const TRASH_ICON = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6M14 11v6"/></svg>';

    const els = {
        sidebar: document.getElementById("sidebar"),
        sidebarOverlay: document.getElementById("sidebar-overlay"),
        openSidebar: document.getElementById("open-sidebar"),
        closeSidebar: document.getElementById("close-sidebar"),
        newChat: document.getElementById("new-chat"),
        conversationList: document.getElementById("conversation-list"),
        conversationSearch: document.getElementById("conversation-search"),
        themeToggle: document.getElementById("theme-toggle"),
        title: document.getElementById("conversation-title"),
        subtitle: document.getElementById("conversation-subtitle"),
        ragToggle: document.getElementById("rag-toggle"),
        clearChat: document.getElementById("clear-chat"),
        messages: document.getElementById("messages"),
        messagesInner: document.getElementById("messages-inner"),
        emptyState: document.getElementById("empty-state"),
        scrollBottom: document.getElementById("scroll-bottom"),
        toast: document.getElementById("toast"),
        form: document.getElementById("composer"),
        input: document.getElementById("message-input"),
        send: document.getElementById("send"),
        stop: document.getElementById("stop"),
        attach: document.getElementById("attach"),
        fileInput: document.getElementById("file-input"),
        srStatus: document.getElementById("sr-status"),
    };

    const state = {
        conversations: [],
        currentId: null,
        title: "New chat",
        messages: [],
        draftTitle: null,
        ragEnabled: true,
        streaming: false,
        uploading: false,
        uploadStatus: "",
        controller: null,
        search: "",
    };

    const emptyStateTemplate = els.emptyState.cloneNode(true);
    let toastTimer = null;
    let stickToBottom = true;
    let scrollFrame = null;

    marked.setOptions({ gfm: true, breaks: true });

    DOMPurify.addHook("afterSanitizeAttributes", (node) => {
        if (node.tagName === "A") {
            node.setAttribute("target", "_blank");
            node.setAttribute("rel", "noopener noreferrer");
        }
    });

    init();

    async function init() {
        applyTheme(getStored(STORAGE.theme) || preferredTheme());
        state.ragEnabled = getStored(STORAGE.rag) !== "false";
        els.ragToggle.checked = state.ragEnabled;
        updateSubtitle();
        bindEvents();
        autoResize();
        updateComposer();

        try {
            await refreshConversations();
            const initialId = hashConversationId() || getStored(STORAGE.current);
            const target = state.conversations.find((conversation) => conversation.id === initialId);
            if (target) {
                await openConversation(target.id);
            } else if (state.conversations.length > 0) {
                await openConversation(state.conversations[0].id);
            } else {
                startNewChat();
            }
        } catch (error) {
            startNewChat();
            showToast(`Could not load conversations: ${error.message}`, true);
        }
    }

    function bindEvents() {
        els.form.addEventListener("submit", (event) => {
            event.preventDefault();
            sendMessage(els.input.value);
        });

        els.input.addEventListener("input", () => {
            autoResize();
            updateComposer();
        });

        els.input.addEventListener("keydown", (event) => {
            if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
                event.preventDefault();
                els.form.requestSubmit();
            }
        });

        els.stop.addEventListener("click", () => {
            state.controller?.abort();
        });

        els.newChat.addEventListener("click", startNewChat);

        els.clearChat.addEventListener("click", () => {
            if (state.currentId) {
                deleteConversation(state.currentId);
            }
        });

        els.attach.addEventListener("click", () => {
            if (!state.uploading) {
                els.fileInput.click();
            }
        });

        els.fileInput.addEventListener("change", () => {
            const files = Array.from(els.fileInput.files || []);
            if (files.length > 0) {
                uploadFiles(files);
            }
        });

        els.ragToggle.addEventListener("change", () => {
            state.ragEnabled = els.ragToggle.checked;
            setStored(STORAGE.rag, String(state.ragEnabled));
            updateSubtitle();
        });

        els.themeToggle.addEventListener("click", () => {
            applyTheme(currentTheme() === "dark" ? "light" : "dark", true);
        });

        els.openSidebar.addEventListener("click", openSidebar);
        els.closeSidebar.addEventListener("click", closeSidebar);
        els.sidebarOverlay.addEventListener("click", closeSidebar);

        els.conversationSearch.addEventListener("input", () => {
            state.search = els.conversationSearch.value;
            renderConversationList();
        });

        els.conversationList.addEventListener("click", (event) => {
            const deleteButton = event.target.closest(".conversation-delete");
            if (deleteButton) {
                event.stopPropagation();
                deleteConversation(deleteButton.dataset.id);
                return;
            }
            const openButton = event.target.closest(".conversation-open");
            if (openButton) {
                const item = openButton.closest(".conversation-item");
                if (item?.dataset.id) {
                    openConversation(item.dataset.id);
                }
            }
        });

        els.messagesInner.addEventListener("click", (event) => {
            const suggestion = event.target.closest(".suggestion");
            if (suggestion) {
                const prompt = suggestion.dataset.prompt || "";
                els.input.value = prompt;
                autoResize();
                updateComposer();
                els.form.requestSubmit();
            }
        });

        els.messages.addEventListener("scroll", () => {
            stickToBottom = isNearBottom();
            els.scrollBottom.classList.toggle("show", !stickToBottom);
        }, { passive: true });

        els.scrollBottom.addEventListener("click", () => {
            stickToBottom = true;
            scrollToBottom(true);
            els.scrollBottom.classList.remove("show");
        });

        window.addEventListener("hashchange", () => {
            const id = hashConversationId();
            if (id && id !== state.currentId && state.conversations.some((conversation) => conversation.id === id)) {
                openConversation(id);
            }
        });
    }

    async function refreshConversations() {
        const response = await fetch("/api/conversations", { headers: { Accept: "application/json" } });
        if (!response.ok) {
            throw new Error(await responseError(response));
        }
        state.conversations = await response.json();
        if (state.currentId) {
            const current = state.conversations.find((conversation) => conversation.id === state.currentId);
            if (current && !state.streaming) {
                state.draftTitle = null;
                setTitle(current.title);
            }
        }
        renderConversationList();
    }

    function renderConversationList() {
        els.conversationList.textContent = "";
        const term = state.search.trim().toLowerCase();
        let items = state.conversations.filter((conversation) => {
            return !term || (conversation.title || "").toLowerCase().includes(term);
        }).map((conversation) => ({ ...conversation, draft: false }));

        if (state.draftTitle && !items.some((conversation) => conversation.id === state.currentId)) {
            items = [{
                id: state.currentId,
                title: state.draftTitle,
                updatedAt: new Date().toISOString(),
                messageCount: 1,
                draft: true,
            }, ...items];
        }

        if (items.length === 0) {
            const empty = document.createElement("p");
            empty.className = "conversation-empty";
            empty.textContent = term ? "No matching chats." : "No conversations yet.";
            els.conversationList.append(empty);
            return;
        }

        for (const [label, conversations] of groupConversations(items)) {
            const group = document.createElement("div");
            group.className = "conversation-group";
            const heading = document.createElement("div");
            heading.className = "conversation-group-title";
            heading.textContent = label;
            group.append(heading);

            for (const conversation of conversations) {
                const item = document.createElement("div");
                item.className = "conversation-item" + (conversation.id === state.currentId ? " active" : "");
                item.dataset.id = conversation.id;

                const openButton = document.createElement("button");
                openButton.type = "button";
                openButton.className = "conversation-open";
                const title = document.createElement("span");
                title.className = "conversation-title";
                title.textContent = conversation.title || "New chat";
                const meta = document.createElement("span");
                meta.className = "conversation-meta";
                meta.textContent = timeLabel(conversation.updatedAt);
                openButton.append(title, meta);

                const deleteButton = document.createElement("button");
                deleteButton.type = "button";
                deleteButton.className = "conversation-delete";
                deleteButton.dataset.id = conversation.id;
                deleteButton.setAttribute("aria-label", `Delete ${conversation.title || "conversation"}`);
                deleteButton.title = "Delete conversation";
                deleteButton.innerHTML = TRASH_ICON;

                item.append(openButton, deleteButton);
                group.append(item);
            }
            els.conversationList.append(group);
        }
    }

    function groupConversations(items) {
        const groups = new Map([
            ["Today", []],
            ["Yesterday", []],
            ["Previous 7 days", []],
            ["Older", []],
        ]);
        const now = new Date();
        const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
        const day = 86400000;

        for (const item of items) {
            const timestamp = item.updatedAt ? new Date(item.updatedAt).getTime() : Date.now();
            if (timestamp >= startOfToday) {
                groups.get("Today").push(item);
            } else if (timestamp >= startOfToday - day) {
                groups.get("Yesterday").push(item);
            } else if (timestamp >= startOfToday - 7 * day) {
                groups.get("Previous 7 days").push(item);
            } else {
                groups.get("Older").push(item);
            }
        }
        return [...groups.entries()].filter(([, conversations]) => conversations.length > 0);
    }

    async function openConversation(id) {
        abortStream();
        const response = await fetch(`/api/conversations/${encodeURIComponent(id)}`, {
            headers: { Accept: "application/json" },
        });
        if (!response.ok) {
            if (response.status === 404) {
                showToast("Conversation not found.", true);
                startNewChat();
                return;
            }
            throw new Error(await responseError(response));
        }

        const detail = await response.json();
        const cachedSources = readSourceCache(detail.id);
        let assistantIndex = 0;
        state.currentId = detail.id;
        state.title = detail.title || "New chat";
        state.draftTitle = null;
        state.messages = detail.messages
            .filter((message) => message.role === "user" || message.role === "assistant")
            .map((message) => {
                const normalized = {
                    role: message.role,
                    content: message.content || "",
                    createdAt: message.createdAt || null,
                };
                if (message.role === "assistant") {
                    normalized.sources = cachedSources[assistantIndex] || null;
                    assistantIndex++;
                }
                return normalized;
            });

        setStored(STORAGE.current, detail.id);
        updateHash(detail.id);
        setTitle(state.title);
        renderMessages();
        stickToBottom = true;
        scrollToBottom(false);
        closeSidebar();
        updateSubtitle();
        await refreshConversations();
    }

    function startNewChat() {
        abortStream();
        state.currentId = createId();
        state.title = "New chat";
        state.messages = [];
        state.draftTitle = null;
        setStored(STORAGE.current, state.currentId);
        updateHash(state.currentId);
        setTitle("New chat");
        renderMessages();
        renderConversationList();
        updateSubtitle();
        els.input.value = "";
        autoResize();
        updateComposer();
        els.input.focus();
        closeSidebar();
    }

    async function deleteConversation(id) {
        if (!id) {
            return;
        }
        if (!window.confirm("Delete this conversation?")) {
            return;
        }

        try {
            const response = await fetch(`/api/conversations/${encodeURIComponent(id)}`, { method: "DELETE" });
            if (!response.ok && response.status !== 404) {
                throw new Error(await responseError(response));
            }
            removeStored(STORAGE.sources(id));
            if (state.currentId === id) {
                startNewChat();
            }
            await refreshConversations();
            showToast("Conversation deleted.");
            announce("Conversation deleted.");
        } catch (error) {
            showToast(`Could not delete conversation: ${error.message}`, true);
        }
    }

    async function sendMessage(rawText) {
        const text = (rawText || "").trim();
        if (state.uploading) {
            showToast("Wait for the document to finish indexing.", true);
            return;
        }
        if (!text || state.streaming) {
            return;
        }

        if (!state.currentId) {
            state.currentId = createId();
        }

        if (state.messages.length === 0) {
            state.draftTitle = titleFrom(text);
            setTitle(state.draftTitle);
            renderConversationList();
        }

        const conversationId = state.currentId;
        const userMessage = { role: "user", content: text, createdAt: new Date().toISOString() };
        state.messages.push(userMessage);
        appendMessage(userMessage, state.messages.length - 1);

        const assistantMessage = { role: "assistant", content: "", createdAt: null, sources: null };
        state.messages.push(assistantMessage);
        const assistantIndex = state.messages.length - 1;
        const assistantCacheIndex = state.messages.filter((message) => message.role === "assistant").length - 1;
        const assistantView = appendMessage(assistantMessage, assistantIndex, true);

        els.input.value = "";
        autoResize();
        state.streaming = true;
        updateComposer();
        updateSubtitle();
        stickToBottom = true;
        scrollToBottom(false);
        announce("Message sent. Generating response.");

        const controller = new AbortController();
        state.controller = controller;
        const sourcesPromise = state.ragEnabled
            ? fetchSources(text, conversationId, controller.signal).catch(() => [])
            : Promise.resolve([]);

        try {
            const finalText = await streamResponse(text, conversationId, assistantView, controller.signal);
            const responseText = finalText.trim() ? finalText : "*The model returned an empty response.*";
            assistantMessage.content = responseText;
            assistantMessage.createdAt = new Date().toISOString();
            cancelScheduledMarkdown(assistantView.content);
            paintMarkdown(assistantView.content, responseText, true);
            addMessageActions(assistantView.article, assistantMessage);

            const sources = await sourcesPromise;
            if (sources.length > 0) {
                assistantMessage.sources = sources;
                attachSources(assistantView.article, sources);
                writeSourceCache(conversationId, assistantCacheIndex, sources);
            }

            if (state.controller === controller) {
                try {
                    await refreshConversations();
                    const current = state.conversations.find((conversation) => conversation.id === conversationId);
                    if (current) {
                        state.draftTitle = null;
                        setTitle(current.title);
                    }
                    renderConversationList();
                } catch {
                }
            }
            if (stickToBottom) {
                scrollToBottom(false);
            }
            announce("Response complete.");
        } catch (error) {
            if (error.name === "AbortError") {
                const partial = assistantMessage.content.trim();
                assistantMessage.content = partial ? `${assistantMessage.content}\n\n*Stopped.*` : "*Stopped.*";
                cancelScheduledMarkdown(assistantView.content);
                paintMarkdown(assistantView.content, assistantMessage.content, true);
                addMessageActions(assistantView.article, assistantMessage);
                announce("Response stopped.");
            } else {
                assistantMessage.content = `**Request failed.** ${error.message}`;
                assistantMessage.createdAt = new Date().toISOString();
                cancelScheduledMarkdown(assistantView.content);
                paintMarkdown(assistantView.content, assistantMessage.content, true);
                addMessageActions(assistantView.article, assistantMessage);
                showToast(error.message, true);
                announce("Response failed.");
            }
        } finally {
            if (state.controller === controller) {
                state.streaming = false;
                state.controller = null;
                updateComposer();
                updateSubtitle();
                if (stickToBottom) {
                    scrollToBottom(false);
                }
            }
        }
    }

    async function streamResponse(text, conversationId, assistantView, signal) {
        const response = await fetch("/api/chat/stream", {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                Accept: "text/plain",
            },
            body: JSON.stringify({
                message: text,
                conversationId,
                rag: state.ragEnabled,
            }),
            signal,
        });

        if (!response.ok) {
            throw new Error(await responseError(response));
        }
        if (!response.body) {
            throw new Error("This browser does not support streaming responses.");
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let fullText = "";
        let receivedFirstChunk = false;

        while (true) {
            const { value, done } = await reader.read();
            if (done) {
                break;
            }
            fullText += decoder.decode(value, { stream: true });
            if (!fullText) {
                continue;
            }
            if (!receivedFirstChunk) {
                receivedFirstChunk = true;
            }
            scheduleMarkdown(assistantView.content, fullText);
            followBottom();
        }

        fullText += decoder.decode();
        return fullText;
    }

    async function fetchSources(query, conversationId, signal) {
        const params = new URLSearchParams({ query, topK: "4" });
        if (conversationId) {
            params.set("conversationId", conversationId);
        }
        const response = await fetch(`/api/rag/search?${params}`, { signal });
        if (!response.ok) {
            return [];
        }
        const results = await response.json();
        return Array.isArray(results) ? results : [];
    }

    async function uploadFiles(files) {
        state.uploading = true;
        state.uploadStatus = "";
        updateComposer();
        updateSubtitle();

        let indexedFiles = 0;
        let indexedChunks = 0;
        const failures = [];

        try {
            for (const [index, file] of files.entries()) {
                state.uploadStatus = `Indexing ${index + 1} of ${files.length}: ${file.name}…`;
                updateSubtitle();
                showToast(`${state.uploadStatus} This can take a few seconds.`);

                try {
                    const body = new FormData();
                    body.append("file", file);
                    const response = await fetch("/api/rag/documents", { method: "POST", body });
                    if (!response.ok) {
                        throw new Error(await responseError(response));
                    }
                    const result = await response.json();
                    indexedFiles++;
                    indexedChunks += result.chunks;
                } catch (error) {
                    failures.push(`${file.name}: ${error.message}`);
                }
            }

            if (indexedFiles > 0) {
                if (!state.ragEnabled) {
                    state.ragEnabled = true;
                    els.ragToggle.checked = true;
                    setStored(STORAGE.rag, "true");
                }
                const summary = `Indexed ${indexedFiles} file${indexedFiles === 1 ? "" : "s"} `
                    + `(${indexedChunks} chunk${indexedChunks === 1 ? "" : "s"}).`;
                if (failures.length > 0) {
                    showToast(`${summary} Failed: ${failures.join("; ")}`, true);
                } else {
                    showToast(summary);
                }
                announce(summary);
            } else {
                showToast(`Upload failed: ${failures.join("; ")}`, true);
            }
        } finally {
            state.uploading = false;
            state.uploadStatus = "";
            els.fileInput.value = "";
            updateComposer();
            updateSubtitle();
        }
    }

    function renderMessages() {
        els.messagesInner.textContent = "";
        if (state.messages.length === 0) {
            els.messagesInner.append(emptyStateTemplate.cloneNode(true));
            return;
        }
        state.messages.forEach((message, index) => {
            appendMessage(message, index);
        });
    }

    function appendMessage(message, index, streaming = false) {
        const emptyState = els.messagesInner.querySelector(".empty-state");
        if (emptyState) {
            emptyState.remove();
        }

        const article = document.createElement("article");
        article.className = `message ${message.role}`;
        article.dataset.index = String(index);

        const avatar = document.createElement("div");
        avatar.className = "avatar";
        avatar.textContent = message.role === "user" ? "You" : "AI";

        const body = document.createElement("div");
        body.className = "message-body";

        const meta = document.createElement("div");
        meta.className = "message-meta";
        const name = document.createElement("span");
        name.className = "message-name";
        name.textContent = message.role === "user" ? "You" : "DeepRelay";
        const time = document.createElement("time");
        time.textContent = formatTime(message.createdAt);
        meta.append(name, time);
        body.append(meta);

        let content = null;
        if (message.role === "user") {
            const userText = document.createElement("div");
            userText.className = "user-text";
            userText.textContent = message.content;
            body.append(userText);
        } else {
            content = document.createElement("div");
            content.className = "markdown";
            if (streaming) {
                content.innerHTML = '<div class="typing"><span></span><span></span><span></span></div>';
            } else {
                paintMarkdown(content, message.content, true);
            }
            body.append(content);

            }

        article.append(avatar, body);
        els.messagesInner.append(article);

        if (message.sources?.length) {
            body.append(createSources(message.sources));
        }
        if (!streaming) {
            addMessageActions(article, message);
        }

        return { article, body, content, meta, time };
    }

    function addMessageActions(article, message) {
        if (article.querySelector(".message-actions")) {
            return;
        }
        const body = article.querySelector(".message-body");
        const actions = document.createElement("div");
        actions.className = "message-actions";
        const copyButton = document.createElement("button");
        copyButton.type = "button";
        copyButton.className = "copy-button";
        copyButton.textContent = "Copy";
        copyButton.title = "Copy response";
        copyButton.addEventListener("click", async () => {
            await copyText(message.content || "");
            copyButton.textContent = "Copied";
            window.setTimeout(() => {
                copyButton.textContent = "Copy";
            }, 1200);
        });
        actions.append(copyButton);
        body.append(actions);
    }

    function createSources(sources) {
        const details = document.createElement("details");
        details.className = "sources";
        const summary = document.createElement("summary");
        summary.textContent = `${sources.length} source${sources.length === 1 ? "" : "s"}`;
        const list = document.createElement("div");
        list.className = "source-list";

        for (const source of sources) {
            const item = document.createElement("details");
            item.className = "source-item";
            const itemSummary = document.createElement("summary");
            const name = document.createElement("span");
            name.className = "source-name";
            name.textContent = source.source || "Document";
            const score = document.createElement("span");
            score.className = "source-score";
            score.textContent = source.score == null ? "n/a" : Number(source.score).toFixed(3);
            itemSummary.append(name, score);
            const content = document.createElement("pre");
            content.className = "source-text";
            content.textContent = source.content || "";
            item.append(itemSummary, content);
            list.append(item);
        }

        details.append(summary, list);
        return details;
    }

    function attachSources(article, sources) {
        const body = article.querySelector(".message-body");
        if (!body) {
            return;
        }
        body.querySelector(".sources")?.remove();
        const sourced = createSources(sources);
        const actions = body.querySelector(".message-actions");
        if (actions) {
            body.insertBefore(sourced, actions);
        } else {
            body.append(sourced);
        }
    }

    function cancelScheduledMarkdown(element) {
        if (element._frame) {
            cancelAnimationFrame(element._frame);
            element._frame = null;
        }
    }

    function scheduleMarkdown(element, text) {
        element._pending = text;
        if (element._frame) {
            return;
        }
        element._frame = requestAnimationFrame(() => {
            element._frame = null;
            paintMarkdown(element, element._pending, false);
        });
    }

    function paintMarkdown(element, text, final) {
        try {
            element.innerHTML = renderMarkdown(text || "");
        } catch {
            element.textContent = text || "";
        }
        if (final) {
            enhanceMarkdown(element);
        }
    }

    function renderMarkdown(text) {
        const html = marked.parse(text, { gfm: true, breaks: true });
        return DOMPurify.sanitize(html, { ADD_ATTR: ["target", "rel"] });
    }

    function enhanceMarkdown(element) {
        element.querySelectorAll("a").forEach((link) => {
            link.target = "_blank";
            link.rel = "noopener noreferrer";
        });

        element.querySelectorAll("pre").forEach((pre) => {
            const code = pre.querySelector("code");
            if (code && window.hljs) {
                try {
                    delete code.dataset.highlighted;
                    window.hljs.highlightElement(code);
                } catch {
                }
            }
            if (!pre.querySelector(".code-copy")) {
                const button = document.createElement("button");
                button.type = "button";
                button.className = "code-copy";
                button.textContent = "Copy";
                button.setAttribute("aria-label", "Copy code");
                button.addEventListener("click", async () => {
                    await copyText(code?.innerText || "");
                    button.textContent = "Copied";
                    window.setTimeout(() => {
                        button.textContent = "Copy";
                    }, 1200);
                });
                pre.append(button);
            }
        });
    }

    function followBottom() {
        if (!stickToBottom || scrollFrame) {
            return;
        }
        scrollFrame = requestAnimationFrame(() => {
            scrollFrame = null;
            if (stickToBottom) {
                scrollToBottom(false);
            }
        });
    }

    function scrollToBottom(smooth) {
        els.messages.scrollTo({
            top: els.messages.scrollHeight,
            behavior: smooth ? "smooth" : "auto",
        });
    }

    function isNearBottom() {
        return els.messages.scrollHeight - els.messages.scrollTop - els.messages.clientHeight < 80;
    }

    function autoResize() {
        els.input.style.height = "auto";
        const height = Math.min(els.input.scrollHeight, 200);
        els.input.style.height = `${height}px`;
        els.input.style.overflowY = els.input.scrollHeight > 200 ? "auto" : "hidden";
    }

    function updateComposer() {
        const hasText = els.input.value.trim().length > 0;
        els.send.hidden = state.streaming;
        els.stop.hidden = !state.streaming;
        els.send.disabled = state.streaming || state.uploading || !hasText;
        els.attach.disabled = state.uploading || state.streaming;
    }

    function updateSubtitle() {
        if (state.uploading) {
            els.subtitle.textContent = state.uploadStatus || "Indexing documents…";
        } else if (state.streaming) {
            els.subtitle.textContent = "Generating response…";
        } else {
            els.subtitle.textContent = state.ragEnabled
                ? "Using your knowledge base · local embeddings"
                : "Regular chat · local embeddings";
        }
    }

    function setTitle(title) {
        state.title = title || "New chat";
        els.title.textContent = state.title;
        document.title = state.title === "New chat" ? "DeepRelay Chat" : `${state.title} · DeepRelay Chat`;
        updateHash(state.currentId);
    }

    function abortStream() {
        if (state.controller) {
            state.controller.abort();
            state.controller = null;
        }
        state.streaming = false;
        updateComposer();
        updateSubtitle();
    }

    function announce(message) {
        els.srStatus.textContent = "";
        window.setTimeout(() => {
            els.srStatus.textContent = message;
        }, 30);
    }

    function showToast(message, isError = false) {
        window.clearTimeout(toastTimer);
        els.toast.textContent = message;
        els.toast.classList.toggle("error", isError);
        els.toast.classList.add("show");
        toastTimer = window.setTimeout(() => {
            els.toast.classList.remove("show");
        }, isError ? 6500 : 3800);
    }

    function openSidebar() {
        els.sidebar.classList.add("open");
        els.sidebarOverlay.classList.add("show");
    }

    function closeSidebar() {
        els.sidebar.classList.remove("open");
        els.sidebarOverlay.classList.remove("show");
    }

    function formatTime(value) {
        if (!value) {
            return "";
        }
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) {
            return "";
        }
        return new Intl.DateTimeFormat(undefined, {
            hour: "numeric",
            minute: "2-digit",
        }).format(date);
    }

    function timeLabel(value) {
        if (!value) {
            return "";
        }
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) {
            return "";
        }
        const now = new Date();
        const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
        const time = date.getTime();
        if (time >= startOfToday) {
            return formatTime(value);
        }
        if (time >= startOfToday - 86400000) {
            return "Yesterday";
        }
        if (time >= startOfToday - 7 * 86400000) {
            return new Intl.DateTimeFormat(undefined, { weekday: "short" }).format(date);
        }
        return new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric" }).format(date);
    }

    function titleFrom(text) {
        const singleLine = text.replace(/\s+/g, " ").trim();
        return singleLine.length > 70 ? `${singleLine.slice(0, 70).trim()}…` : singleLine || "New chat";
    }

    function createId() {
        if (window.crypto?.randomUUID) {
            return window.crypto.randomUUID();
        }
        return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (char) => {
            const random = Math.random() * 16 | 0;
            const value = char === "x" ? random : (random & 0x3 | 0x8);
            return value.toString(16);
        });
    }

    function hashConversationId() {
        if (!window.location.hash.startsWith("#c=")) {
            return null;
        }
        const id = decodeURIComponent(window.location.hash.slice(3));
        return id || null;
    }

    function updateHash(id) {
        if (!id) {
            return;
        }
        const next = `#c=${encodeURIComponent(id)}`;
        if (window.location.hash !== next) {
            window.history.replaceState(null, "", next);
        }
    }

    async function responseError(response) {
        const contentType = response.headers.get("content-type") || "";
        try {
            if (contentType.includes("application/json")) {
                const data = await response.json();
                return data.message || data.error || `HTTP ${response.status}`;
            }
            const text = (await response.text()).trim();
            return text || `HTTP ${response.status}`;
        } catch {
            return `HTTP ${response.status}`;
        }
    }

    async function copyText(text) {
        if (!text) {
            return;
        }
        try {
            await navigator.clipboard.writeText(text);
        } catch {
            const textarea = document.createElement("textarea");
            textarea.value = text;
            textarea.setAttribute("readonly", "");
            textarea.style.position = "fixed";
            textarea.style.opacity = "0";
            document.body.append(textarea);
            textarea.select();
            document.execCommand("copy");
            textarea.remove();
        }
    }

    function readSourceCache(conversationId) {
        try {
            const raw = getStored(STORAGE.sources(conversationId));
            return raw ? JSON.parse(raw) : {};
        } catch {
            return {};
        }
    }

    function writeSourceCache(conversationId, assistantIndex, sources) {
        try {
            const cache = readSourceCache(conversationId);
            cache[assistantIndex] = sources.map((source) => ({
                source: source.source,
                score: source.score,
                content: source.content,
            }));
            const indexes = Object.keys(cache).map(Number).sort((a, b) => a - b);
            while (indexes.length > 30) {
                delete cache[indexes.shift()];
            }
            setStored(STORAGE.sources(conversationId), JSON.stringify(cache));
        } catch {
        }
    }

    function preferredTheme() {
        return window.matchMedia?.("(prefers-color-scheme: dark)").matches ? "dark" : "light";
    }

    function currentTheme() {
        return document.documentElement.dataset.theme === "dark" ? "dark" : "light";
    }

    function applyTheme(theme, persist = false) {
        document.documentElement.dataset.theme = theme === "dark" ? "dark" : "light";
        if (persist) {
            setStored(STORAGE.theme, theme);
        }
        els.themeToggle.title = theme === "dark" ? "Switch to light theme" : "Switch to dark theme";
    }

    function getStored(key) {
        try {
            return window.localStorage.getItem(key);
        } catch {
            return null;
        }
    }

    function setStored(key, value) {
        try {
            window.localStorage.setItem(key, value);
        } catch {
        }
    }

    function removeStored(key) {
        try {
            window.localStorage.removeItem(key);
        } catch {
        }
    }
})();
