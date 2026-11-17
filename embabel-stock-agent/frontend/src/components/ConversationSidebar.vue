<script setup>
import Button from 'primevue/button'
import { useChatStore } from '../stores/chat'
import DocumentUploader from './DocumentUploader.vue'

const store = useChatStore()
</script>

<template>
  <aside class="sidebar">
    <div class="sidebar-header">
      <div class="brand"><i class="pi pi-chart-line brand-mark"></i> Stock Agent</div>
      <div class="subtitle">Embabel multi-agent analysis</div>
      <Button
        label="New analysis"
        icon="pi pi-plus"
        style="width:100%;margin-top:12px"
        @click="store.newChat()"
      />
    </div>

    <div class="section" style="flex:1">
      <div class="section-title">Conversations</div>
      <div
        v-for="conversation in store.conversations"
        :key="conversation.id"
        class="conv-item"
        :class="{ active: conversation.id === store.currentId }"
        @click="store.select(conversation.id)"
      >
        <div style="min-width:0">
          <div class="conv-title">
            <i v-if="store.isStreaming(conversation.id)" class="pi pi-spin pi-spinner"
               style="color:#7ee0b8;margin-right:6px;font-size:11px"></i>
            {{ conversation.title }}
          </div>
          <div class="conv-meta">{{ conversation.messageCount }} messages</div>
        </div>
        <Button
          icon="pi pi-trash"
          text
          rounded
          severity="danger"
          aria-label="Delete conversation"
          @click.stop="store.removeConversation(conversation.id)"
        />
      </div>
    </div>

    <div class="section" style="border-top:1px solid var(--border); max-height:280px">
      <div class="section-title">Global documents · all chats</div>
      <DocumentUploader scope="GLOBAL" />
    </div>
  </aside>
</template>
