<script setup>
import { onMounted, onUnmounted } from 'vue'
import { useChatStore } from './stores/chat'
import ConversationSidebar from './components/ConversationSidebar.vue'
import ChatPanel from './components/ChatPanel.vue'

const store = useChatStore()
let statusTimer

onMounted(() => {
  store.init()
  // Streams started in another tab / before a reload have no local AbortController.
  statusTimer = setInterval(() => store.refreshStreamStatus(), 3000)
})

onUnmounted(() => clearInterval(statusTimer))
</script>

<template>
  <div class="app-shell">
    <ConversationSidebar />
    <ChatPanel />
  </div>
</template>
