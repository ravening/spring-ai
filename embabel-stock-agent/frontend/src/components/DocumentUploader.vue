<script setup>
import { ref } from 'vue'
import Button from 'primevue/button'
import { useChatStore } from '../stores/chat'

const props = defineProps({
  scope: { type: String, required: true },
  conversationId: { type: String, default: null }
})

const store = useChatStore()
const fileInput = ref(null)
const busy = ref(false)
const error = ref('')

const docs = () => (props.scope === 'GLOBAL' ? store.globalDocs : store.chatDocs)

async function onFile(event) {
  const file = event.target.files?.[0]
  if (!file) return
  busy.value = true
  error.value = ''
  try {
    await store.upload(file, props.scope)
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
    if (fileInput.value) fileInput.value.value = ''
  }
}
</script>

<template>
  <div>
    <div style="display:flex; align-items:center; gap:8px">
      <input ref="fileInput" type="file" style="display:none" @change="onFile" />
      <Button
        :label="busy ? 'Uploading…' : scope === 'GLOBAL' ? 'Add global file' : 'Add file to chat'"
        icon="pi pi-upload"
        class="p-button-sm"
        :disabled="busy || (scope === 'CHAT' && !conversationId)"
        @click="fileInput && fileInput.click()"
      />
    </div>
    <div v-if="error" style="color:#fca5a5;font-size:11px;margin-top:6px">{{ error }}</div>
    <div style="margin-top:8px">
      <div v-for="doc in docs()" :key="doc.id" class="doc-row">
        <span class="doc-name" :title="doc.filename">
          <i class="pi pi-file" style="margin-right:6px"></i>{{ doc.filename }}
        </span>
        <span style="display:flex;align-items:center;gap:6px">
          <span class="scope-chip">{{ doc.scope === 'GLOBAL' ? 'global' : 'this chat' }}</span>
          <Button
            icon="pi pi-trash"
            text
            rounded
            severity="danger"
            aria-label="Delete document"
            @click="store.deleteDoc(doc.id)"
          />
        </span>
      </div>
      <div v-if="!docs().length" style="font-size:11px;color:var(--muted)">
        {{ scope === 'GLOBAL' ? 'No global documents yet.' : 'No documents in this chat yet.' }}
      </div>
    </div>
  </div>
</template>
