<template>
  <section
    class="paper-card"
    :class="[`paper-card--${tone}`, { 'paper-card--taped': taped }]"
  >
    <header v-if="title || $slots.heading" class="paper-card__heading">
      <SketchIcon v-if="icon" :name="icon" :size="23" />
      <h2>{{ title }}<slot name="heading" /></h2>
      <slot name="action" />
    </header>
    <div class="paper-card__body"><slot /></div>
    <footer v-if="$slots.footer" class="paper-card__footer">
      <slot name="footer" />
    </footer>
  </section>
</template>

<script setup>
import SketchIcon from "./SketchIcon.vue";
defineProps({
  title: String,
  icon: String,
  tone: { type: String, default: "paper" },
  taped: Boolean,
});
</script>
