import { defineStore } from 'pinia'
import { apiDelete, apiGet, apiPost, streamPost } from '../api/client'

const asMessage = (turn) => ({
  role: turn.role,
  content: turn.content,
  streaming: false,
  events: [],
  report: null
})

export const useChatStore = defineStore('chat', {
  state: () => ({
    conversations: [],
    currentId: null,
    messages: [],
    // Per-conversation UI state, so background streams keep running when switching chats.
    histories: {},
    streams: {},
    globalDocs: [],
    chatDocs: [],
    error: null
  }),

  getters: {
    // Used by the composer: true only for the conversation currently on screen.
    streaming: (state) => !!(state.streams[state.currentId] && state.streams[state.currentId].active),
    isStreaming: (state) => (id) => !!(state.streams[id] && state.streams[id].active)
  },

  actions: {
    async init() {
      try {
        await this.loadConversations()
        if (this.conversations.length) await this.select(this.conversations[0].id)
        else await this.newChat()
      } catch (e) {
        this.error = e.message
      }
    },

    async loadConversations() {
      this.conversations = await apiGet('/api/conversations')
    },

    async newChat() {
      const created = await apiPost('/api/conversations', { title: 'New analysis' })
      await this.loadConversations()
      await this.select(created.id)
    },

    async refreshHistory(id) {
      const turns = await apiGet(`/api/conversations/${id}/messages`)
      this.histories[id] = turns.map(asMessage)
      if (this.currentId === id) this.messages = this.histories[id]
    },

    async select(id) {
      // Do NOT stop other conversations' streams — they continue in the background.
      this.currentId = id
      if (this.histories[id]) {
        this.messages = this.histories[id]
      } else {
        try {
          await this.refreshHistory(id)
        } catch (e) {
          this.histories[id] = []
          this.messages = this.histories[id]
          this.error = e.message
        }
      }
      // A stream may have been started in another tab or before a page reload.
      try {
        const status = await apiGet(`/api/conversations/${id}/active`)
        if (status.active && !this.streams[id]) {
          this.streams[id] = { controller: null, active: true }
        }
      } catch (e) {
        // best-effort; local stream state still works
      }
      await Promise.all([this.loadDocs(), this.loadConversations()])
    },

    async rename(id, title) {
      const response = await fetch(`/api/conversations/${id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title })
      })
      if (!response.ok) throw new Error('Rename failed')
      await this.loadConversations()
    },

    async removeConversation(id) {
      this.stop(id)
      await apiDelete(`/api/conversations/${id}`)
      delete this.histories[id]
      await this.loadConversations()
      if (this.currentId === id) {
        if (this.conversations.length) await this.select(this.conversations[0].id)
        else await this.newChat()
      }
    },

    async send(text) {
      const targetId = this.currentId
      if (!text.trim() || !targetId || this.isStreaming(targetId)) return

      if (!this.histories[targetId]) this.histories[targetId] = []
      const history = this.histories[targetId]
      history.push({ role: 'user', content: text, streaming: false, events: [], report: null })
      const assistant = {
        role: 'assistant',
        content: '',
        streaming: true,
        events: [],
        report: null
      }
      history.push(assistant)
      this.messages = history

      const controller = new AbortController()
      this.streams[targetId] = { controller, active: true }

      try {
        await streamPost(
          '/api/chat/stream',
          { conversationId: targetId, message: text },
          ({ data }) => {
            // The closure keeps updating this conversation's message even after
            // the user navigates elsewhere, so returning shows live progress.
            if (data.type === 'token') assistant.content += data.text
            else if (data.type === 'report') assistant.report = data.data
            else if (data.type === 'stage' || data.type === 'tool_call') assistant.events.push(data)
            else if (data.type === 'error') assistant.content += `\n\n**Error:** ${data.text}`
          },
          controller.signal
        )
      } catch (e) {
        if (e.name !== 'AbortError') assistant.content += `\n\n**Error:** ${e.message}`
      } finally {
        assistant.streaming = false
        if (this.streams[targetId]) this.streams[targetId].active = false
        delete this.streams[targetId]
        await Promise.all([this.refreshHistory(targetId).catch(() => {}), this.loadConversations()])
      }
    },

    /** Reconcile streams discovered from server status (no local controller). */
    async refreshStreamStatus() {
      const orphaned = Object.keys(this.streams).filter((key) => !this.streams[key].controller)
      for (const key of orphaned) {
        try {
          const status = await apiGet(`/api/conversations/${key}/active`)
          if (!status.active) delete this.streams[key]
        } catch (e) {
          // keep local state if the server is temporarily unavailable
        }
      }
    },

    stop(id = this.currentId) {
      const stream = this.streams[id]
      if (!stream) return
      // The controller is null when the stream was detected from server status
      // (different tab / page reload) — there is nothing local to abort.
      if (stream.controller) stream.controller.abort()
      stream.active = false
      delete this.streams[id]
      const history = this.histories[id]
      if (history && history.length) history[history.length - 1].streaming = false
    },

    async loadDocs() {
      try {
        const [globalDocs, chatDocs] = await Promise.all([
          apiGet('/api/documents/global'),
          this.currentId ? apiGet(`/api/documents/chat/${this.currentId}`) : Promise.resolve([])
        ])
        this.globalDocs = globalDocs
        this.chatDocs = chatDocs
      } catch (e) {
        this.error = e.message
      }
    },

    async upload(file, scope) {
      if (!file) return
      const form = new FormData()
      form.append('file', file)
      const url = scope === 'GLOBAL'
        ? '/api/documents/global'
        : `/api/documents/chat/${this.currentId}`
      const response = await fetch(url, { method: 'POST', body: form })
      if (!response.ok) throw new Error((await response.text()) || 'Upload failed')
      await this.loadDocs()
    },

    async deleteDoc(id) {
      await apiDelete(`/api/documents/${id}`)
      await this.loadDocs()
    }
  }
})
