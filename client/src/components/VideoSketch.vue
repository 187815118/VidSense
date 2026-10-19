<template>
  <div class="video-sketch">
    <div class="video-sketch__label">
      <span class="pencil-dot"></span
      >{{
        demo ? "演示示意 · 非真实视频" : "视频预览示意 · 打开助手查看原视频"
      }}
    </div>
    <svg
      viewBox="0 0 800 410"
      role="img"
      aria-label="手绘讲解场景：从视频中发现问题、定位证据、形成洞察"
    >
      <defs>
        <pattern
          :id="gridId"
          width="18"
          height="18"
          patternUnits="userSpaceOnUse"
        >
          <path
            d="M18 0H0V18"
            fill="none"
            stroke="#9eaeb5"
            stroke-opacity=".15"
          />
        </pattern>
      </defs>
      <path
        d="M4 12 793 5 795 403 5 398Z"
        fill="#dce9ee"
        stroke="#656e70"
        stroke-width="2"
      />
      <path d="M4 12 793 5 795 403 5 398Z" :fill="gridFill" />
      <path
        d="m243 42 500-8-6 282-500 6Z"
        fill="#fffdf5"
        stroke="#657074"
        stroke-width="2.5"
      />
      <path d="m258 58 466-6-3 248-467 8Z" fill="none" stroke="#adb4ac" />
      <text
        x="316"
        y="106"
        fill="#454b46"
        font-size="29"
        font-family="KaiTi, STKaiti, serif"
      >
        让每一段视频，都有回响
      </text>
      <path
        d="M318 116q138 5 322-4"
        stroke="#aaa0cc"
        stroke-width="3"
        fill="none"
      />
      <g stroke="#62685f" stroke-width="2" stroke-linejoin="round" fill="none">
        <path d="m286 159 109-3 3 58-111 2Z" fill="#e8e0f4" />
        <path d="m446 157 110 2-2 55-110 1Z" fill="#fff0b9" />
        <path d="m601 158 109-1-1 57-110 1Z" fill="#e0eccf" />
        <path
          d="M401 186q17-7 39-1m-11-8 10 8-10 7M560 186q17-7 36-1m-10-8 10 8-10 7"
        />
        <path d="M335 248q99 41 207-1t155 0" stroke-dasharray="5 7" />
      </g>
      <g fill="#52574e" font-size="21" font-family="KaiTi, STKaiti, serif">
        <text x="299" y="192">发现问题</text>
        <text x="457" y="192">定位证据</text>
        <text x="611" y="192">形成洞察</text>
      </g>
      <g
        stroke="#474d49"
        stroke-width="2.5"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <path d="M134 301q-8-88 19-105l43 1q27 31 14 102Z" fill="#e7e0d2" />
        <path d="M164 205v56m-17-47 17 37 26-37" fill="none" />
        <path d="m198 210 44 31 57-39 10 12-65 49-52-25" fill="#e7e0d2" />
        <path d="m296 202 12-14 12-3 3 6-10 13 7-3 6 5-19 12" fill="#f1d7bb" />
        <path d="M153 192v14q16 19 30-1v-14" fill="#f1d7bb" />
        <path
          d="M144 148q-6-30 25-32 34 0 29 41l-10 31q-22 21-41-9Z"
          fill="#f1d7bb"
        />
        <path
          d="M143 161q-20-26 1-40l-6-6 23-3q0-16 20-10l-2 12q34 1 25 31l-16-2-6-15-14 9-17 2-2 22Z"
          fill="#65635b"
        />
        <path d="M181 155h1m-23 0h1m10 16q7 4 12-2" fill="none" />
        <path d="m135 299-8 49h33l10-48 11 49h34l-7-50" fill="#727d83" />
        <path d="M133 350h-16m70 0h32" fill="none" />
      </g>
      <g stroke="#59645d" stroke-width="2" fill="none">
        <path
          d="M65 329V226m0 45q-40-6-33-39 33-1 33 39m0 20q40-2 34-39-33 4-34 39"
          fill="#b8ccb3"
        />
        <path d="m43 323 45 1-7 34H48Z" fill="#dac7ac" />
      </g>
      <g stroke="#61645c" stroke-width="2.5">
        <path
          d="M278 408q-5-56 33-62 45-9 58 61m61 2q-8-57 34-64 44-5 58 62m53 0q-4-51 34-62 42-4 62 62"
          fill="#b8c5cb"
        />
        <path
          d="M332 321a23 25 0 1 1-46 0 23 25 0 0 1 46 0Zm152 0a23 25 0 1 1-46 0 23 25 0 0 1 46 0Zm150 0a23 25 0 1 1-46 0 23 25 0 0 1 46 0Z"
          fill="#e3c5a9"
        />
        <path
          d="M285 321q-4-39 31-30 20 7 16 29l-13-15-14 8-9-3Z"
          fill="#6e6d63"
        />
        <path d="M438 319q-5-35 26-31 29 0 22 33l-18-17-12 7Z" fill="#77766c" />
        <path d="M590 319q0-40 32-30 20 4 14 34l-18-17-14 9Z" fill="#61645e" />
      </g>
      <path
        d="m746 77 4-12 4 12 12 3-12 4-4 12-4-12-12-4Z"
        fill="#fff0b9"
        stroke="#75786a"
        stroke-width="1.5"
      />
    </svg>
    <div class="video-sketch__footer">
      <SketchIcon name="video" :size="18" /><span>{{
        demo
          ? "看看视频如何变成可追溯的知识"
          : "上传后，可播放原视频并跳转时间戳"
      }}</span
      ><button type="button" @click="$emit('action')">
        {{ actionLabel || (demo ? "试试自己的目标" : "选择视频") }}
        <span aria-hidden="true">↗</span>
      </button>
    </div>
  </div>
</template>
<script setup>
import { useId } from "vue";
import SketchIcon from "./SketchIcon.vue";
defineProps({ demo: Boolean, actionLabel: String });
const gridId = useId();
const gridFill = "url(#" + gridId + ")";
defineEmits(["action"]);
</script>
