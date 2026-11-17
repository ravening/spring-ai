<script setup>
defineProps({ events: { type: Array, default: () => [] } })
</script>

<template>
  <div v-if="events.length" class="trace">
    <span
      v-for="(event, index) in events"
      :key="index"
      class="trace-chip"
      :class="{ tool: event.type === 'tool_call' }"
    >
      <i :class="event.type === 'tool_call' ? 'pi pi-wrench' : 'pi pi-sparkles'"></i>
      {{ event.stage }} · {{ event.text }}
    </span>
  </div>
</template>
