<script setup>
import { nextTick, ref, watch } from 'vue'
import Button from 'primevue/button'
import { useChatStore } from '../stores/chat'
import MessageBubble from './MessageBubble.vue'
import AgentTraceTimeline from './AgentTraceTimeline.vue'
import ReportView from './ReportView.vue'
import DocumentUploader from './DocumentUploader.vue'

const store = useChatStore()
const draft = ref('')
const scroller = ref(null)

function send() {
  const text = draft.value.trim()
  if (!text || store.streaming) return
  draft.value = ''
  store.send(text)
}

watch(
  () => [store.messages.length, store.messages.at(-1)?.content?.length],
  async () => {
    await nextTick()
    if (scroller.value) scroller.value.scrollTop = scroller.value.scrollHeight
  }
)
</script>

<template>
  <div class="main">
    <div class="main-header">
      <div>
        <div class="main-title">Stock analysis workspace</div>
        <div style="font-size:11px;color:var(--muted)">
          Ask for a ticker (e.g. “analyse AAPL”) or chat about your uploaded documents
        </div>
      </div>
      <div style="min-width:220px">
        <DocumentUploader scope="CHAT" :conversation-id="store.currentId" />
      </div>
    </div>

    <div ref="scroller" class="messages">
      <div v-if="!store.messages.length" class="empty">
        <i class="pi pi-chart-line" style="font-size:36px;color:var(--accent)"></i>
        <h2>Your multi-agent analyst is ready</h2>
        <p>
          Type a stock ticker to launch parallel research agents: live price, news,
          earnings/dividends and technical levels. They combine their findings into a
          BUY / SELL / HOLD / WAIT verdict. Answers stream token by token and Markdown
          is rendered as it arrives.
        </p>
      </div>

      <div v-for="(message, index) in store.messages" :key="index" class="message" :class="message.role">
        <ReportView v-if="message.report" :report="message.report" />
        <MessageBubble :content="message.content" :streaming="message.streaming" />
        <AgentTraceTimeline v-if="message.events.length" :events="message.events" />
      </div>
    </div>

    <div class="composer">
      <textarea
        v-model="draft"
        placeholder="Ask about a ticker, e.g. 'Should I buy TSLA now?' — Enter to send"
        :disabled="store.streaming"
        @keydown.enter.exact.prevent="send"
      ></textarea>
      <Button
        v-if="store.streaming"
        label="Stop"
        icon="pi pi-stop"
        severity="danger"
        @click="store.stop()"
      />
      <Button v-else label="Send" icon="pi pi-send" @click="send" />
    </div>
  </div>
</template>
