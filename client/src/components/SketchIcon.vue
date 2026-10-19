<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.7"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path v-for="(path, index) in paths" :key="index" :d="path" />
  </svg>
</template>

<script setup>
import { computed } from "vue";
const props = defineProps({
  name: { type: String, default: "star" },
  size: { type: [Number, String], default: 22 },
});
const icons = {
  home: ["M3 10 12 2.8 21 10v10H14v-6h-4v6H3Z"],
  video: [
    "M3 6.5 15.8 6 16 19 3 19.4Z",
    "M16 10 22 7.5v11L16 16",
    "M4 3.5 14 2",
  ],
  folder: ["M2.5 6h7l2 2h10l-.7 12H3Z", "m10 11 5 3-5 3Z"],
  tasks: [
    "M6 4h14v17H5V7",
    "M8 2h7v4H8Z",
    "m8 11 1 1 2-2",
    "M14 11h3M8 16h3m3 0h3",
  ],
  memory: [
    "M12 3C0 1 0 21 11 21V4c9-5 15 10 7 15",
    "M6 7c-4 1-3 6 0 6m12-7c3 3 1 6-2 6",
    "M7 16c-3 3 0 5 4 4m5-5c5-1 7 2 3 5",
  ],
  history: ["M3 10a9 9 0 1 1 1 8", "M3 4v6h6", "M12 7v6l4 2"],
  preference: [
    "M5 3v18M12 3v18M19 3v18",
    "M2 8h6m1 8h6m1-10h6",
    "M3 6h4v4H3Zm7 8h4v4h-4Zm7-10h4v4h-4Z",
  ],
  assistant: [
    "M5 7h14l1 12H4Z",
    "M12 7V3m-2-1h4",
    "M8 11v2m8-2v2m-7 3h6",
    "M1 11v5m22-5v5",
  ],
  settings: [
    "m9 3 1-2h4l1 3 3 1 2-1 2 3-1 3v4l1 2-2 3-3-1-3 1-1 2h-4l-1-3-3-1-2 1-2-3 1-3v-3L2 7l2-3 3 1Z",
    "M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z",
  ],
  search: ["M16.5 10a6.5 6.5 0 1 1-13 0 6.5 6.5 0 0 1 13 0Z", "m15 15 6 6"],
  upload: ["M12 16V3m-5 5 5-5 5 5", "M3 15v6h18v-6"],
  arrow: ["M3 12c5-2 11 2 17-1", "m15 6 6 5-5 6"],
  star: ["m12 2 3 6 7 2-5 5 1 7-6-4-6 4 1-7-5-5 7-2Z"],
  cloud: ["M7 19C-2 19 0 8 7 9c1-9 14-7 13 1 7 2 3 10-1 9Z"],
  check: ["m4 12 5 6L21 5"],
  book: ["M12 5C8 2 4 3 2 4v16c4-2 7-1 10 1 3-2 6-3 10-1V4c-4-2-7-1-10 1v16"],
  quote: ["M9 5C4 6 2 9 3 17h6v-7H5m16-5c-5 1-7 4-6 12h6v-7h-4"],
  play: ["m8 4 12 8L8 20Z"],
  rocket: [
    "M6 16C8 8 15 3 22 2c0 8-5 14-14 16Z",
    "M6 13H2l4-6 6-1m-1 12v4l6-4 1-6",
    "m3 5-1 4m4-1-3 2M17 7a2 2 0 1 0 0 .1",
  ],
  bulb: [
    "M8 17c0-4-4-4-4-8a8 8 0 0 1 16 0c0 4-4 4-4 8Z",
    "M8 20h8m-7 3h6M9 10l3 3 3-3m-3 3v4",
  ],
  link: [
    "m10 14 4-4",
    "m8 16-2 2a4 4 0 0 1-5-6l5-5a4 4 0 0 1 6 1m4 0 2-2a4 4 0 0 1 5 6l-5 5a4 4 0 0 1-6-1",
  ],
  user: ["M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z", "M4 21v-3c0-7 16-7 16 0v3"],
  clock: ["M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z", "M12 6v7l4 2"],
  close: ["m5 5 14 14M19 5 5 19"],
};
const paths = computed(() => icons[props.name] || icons.star);
</script>
