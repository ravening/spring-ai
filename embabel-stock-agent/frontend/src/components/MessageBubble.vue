<script setup>
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'

const props = defineProps({
  content: { type: String, default: '' },
  streaming: { type: Boolean, default: false }
})

const md = new MarkdownIt({ html: false, linkify: true, typographer: true, breaks: true })

/**
 * While tokens arrive, keep an incomplete Markdown construct as plain text so
 * raw `**` markers and half-open code fences never flash on screen.
 */
function splitStreaming(text, streaming) {
  if (!streaming) return { stable: text, tail: '' }
  let cut = text.length

  const boldCount = (text.match(/\*\*/g) || []).length
  if (boldCount % 2 === 1) cut = Math.min(cut, text.lastIndexOf('**'))

  const backtickCount = (text.match(/`/g) || []).length
  if (backtickCount % 2 === 1) cut = Math.min(cut, text.lastIndexOf('`'))

  const lastLine = text.slice(text.lastIndexOf('\n') + 1)
  const trimmed = lastLine.trimStart()
  if (trimmed.startsWith('|') && !trimmed.trimEnd().endsWith('|')) {
    cut = Math.min(cut, text.length - lastLine.length)
  }

  if (cut < 0) cut = 0
  return { stable: text.slice(0, cut), tail: text.slice(cut) }
}

const parts = computed(() => splitStreaming(props.content, props.streaming))
const rendered = computed(() => DOMPurify.sanitize(md.render(parts.value.stable)))
</script>

<template>
  <div class="bubble">
    <span v-html="rendered"></span><span v-text="parts.tail"></span><span v-if="streaming" class="cursor"></span>
  </div>
</template>
