<template>
  <PaperCard title="Agent 状态" icon="assistant" class="agent-flow">
    <p class="flow-status">
      <span class="pencil-dot" :class="{ 'is-working': loading }"></span
      >{{
        loading
          ? "正在沿着线索探索…"
          : hasResult
            ? "这次分析已返回"
            : "准备好，一起发现"
      }}
    </p>
    <div class="flow-progress" aria-hidden="true">
      <span :style="{ width: `${(completed / 3) * 100}%` }"></span>
    </div>
    <div class="flow-notes">
      <div
        v-for="(step, index) in steps"
        :key="step.key"
        class="flow-note"
        :class="[`flow-note--${step.tone}`, { 'is-done': done(step.key) }]"
      >
        <div class="flow-note__title">
          <SketchIcon :name="step.icon" :size="24" />
          <h3>
            {{ step.name }} <small>{{ step.title }}</small>
          </h3>
          <span
            v-if="done(step.key)"
            class="step-mark"
            :aria-label="
              step.key === 'CRITIC' && evaluation?.criticPassed === false
                ? '待核验'
                : '已返回'
            "
            >{{
              step.key === "CRITIC" && evaluation?.criticPassed === false
                ? "!"
                : "✓"
            }}</span
          >
        </div>
        <ul>
          <li
            v-for="text in step.key === 'PLANNER' && plan?.tasks?.length
              ? plan.tasks.slice(0, 3)
              : step.items"
            :key="text"
          >
            <span aria-hidden="true">{{ done(step.key) ? "☑" : "□" }}</span
            >{{ text }}
          </li>
        </ul>
        <span class="flow-note__number">0{{ index + 1 }}</span>
      </div>
    </div>
    <div class="memory-gate-note">
      <SketchIcon name="memory" :size="23" />
      <div>
        <strong>Memory Gate</strong>
        <p>需要历史时，再唤起记忆。<br />当前视频证据始终优先。</p>
      </div>
    </div>
    <p class="handwritten flow-footnote">
      每个结论，都有迹可循 <span aria-hidden="true">✧</span>
    </p>
  </PaperCard>
</template>
<script setup>
import { computed } from "vue";
import PaperCard from "./PaperCard.vue";
import SketchIcon from "./SketchIcon.vue";
const props = defineProps({
  plan: Object,
  trace: Object,
  evaluation: Object,
  loading: Boolean,
  hasResult: Boolean,
});
const steps = [
  {
    key: "PLANNER",
    name: "Planner",
    title: "任务规划",
    icon: "bulb",
    tone: "blue",
    items: ["理解你的分析目标", "拆解可验证的任务", "安排证据与步骤"],
  },
  {
    key: "EXECUTOR",
    name: "Executor",
    title: "工具执行",
    icon: "settings",
    tone: "yellow",
    items: ["阅读画面与语音", "关联 OCR 与时间线", "整理结构化产物"],
  },
  {
    key: "CRITIC",
    name: "Critic",
    title: "结果评估",
    icon: "search",
    tone: "pink",
    items: ["检查结论与证据", "核验引用的时间戳", "保留不确定的信息"],
  },
];
const done = (key) =>
  (props.loading || props.hasResult) &&
  Number(props.trace?.stageDurationMs?.[key]) > 0;
const completed = computed(() => steps.filter((step) => done(step.key)).length);
</script>
