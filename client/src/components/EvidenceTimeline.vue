<template>
  <PaperCard title="视频证据时间线" icon="video" class="timeline-card">
    <template #action
      ><span class="quiet-label">画面 · 语音 · 文字</span></template
    >
    <p class="card-description">把关键发现，放回它发生的那一刻。</p>
    <div v-if="items.length" class="evidence-timeline">
      <button
        v-for="(item, index) in items"
        :key="`${item.startMs}-${index}`"
        type="button"
        class="timeline-note"
        :class="`timeline-note--${index % 4}`"
        @click="$emit('seek', item.startMs)"
      >
        <span class="timeline-note__time">{{ formatTime(item.startMs) }}</span
        ><strong>{{ item.label }}</strong
        ><span class="timeline-note__pin" aria-hidden="true"></span>
      </button>
    </div>
    <div v-else class="timeline-empty">
      <div class="timeline-empty__line">
        <i></i><i></i><i></i><i></i><i></i>
      </div>
      <span>完成分析或检索后，关键证据会出现在这里。</span>
    </div>
  </PaperCard>
</template>
<script setup>
import { computed } from "vue";
import PaperCard from "./PaperCard.vue";
const props = defineProps({
  results: { type: Array, default: () => [] },
  content: { type: String, default: "" },
});
defineEmits(["seek"]);
const items = computed(() => {
  if (props.results.length)
    return props.results
      .slice(0, 6)
      .map((hit) => ({
        startMs: hit.startMs,
        label: hit.snippet || hit.source || "视频证据",
      }));
  return [
    ...props.content.matchAll(
      /^\s*-?\s*\[(\d{1,2}:\d{2}(?::\d{2})?)\]\s*(.+)$/gm,
    ),
  ]
    .slice(0, 6)
    .map((match) => ({
      startMs:
        match[1]
          .split(":")
          .reduce((seconds, value) => seconds * 60 + Number(value), 0) * 1000,
      label: match[2].replace(/\*\*/g, ""),
    }));
});
const formatTime = (value) => {
  const seconds = Math.floor(Number(value) / 1000);
  return `${Math.floor(seconds / 60)
    .toString()
    .padStart(2, "0")}:${(seconds % 60).toString().padStart(2, "0")}`;
};
</script>
