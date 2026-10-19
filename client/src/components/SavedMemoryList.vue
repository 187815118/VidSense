<template>
  <p v-if="demo" class="empty-note">演示模式不读取真实记忆。登录并完成分析后可在这里查看保存记录。</p>
  <p v-else-if="!signedIn" class="empty-note">登录后查看你的记忆记录。</p>
  <p v-else-if="loading" class="empty-note" role="status">正在读取已保存记录…</p>
  <p v-else-if="error" class="empty-note" role="alert">{{ error }}</p>
  <p v-else-if="enabled === false" class="empty-note">当前服务未开启长期记忆。</p>
  <p v-else-if="!items.length" class="empty-note">最近 20 条有效记忆中暂无此类记录；记忆会在成功任务后异步提取。</p>
  <ul v-else class="saved-memory-list">
    <li v-for="item in items" :key="item.memoryId">
      <span class="quiet-label">{{ labels[item.type] || item.type }} · {{ item.scope === 'GLOBAL_USER' ? '跨视频' : '当前视频' }}</span>
      <p>{{ item.content }}</p>
      <details><summary>来源与更新时间</summary>
        <small>任务：{{ item.sourceTaskId || '未记录' }}<br />视频：{{ item.sourceVideoId ?? '全局' }}<br />{{ item.updatedAt || '' }}</small>
      </details>
    </li>
  </ul>
</template>
<script setup>
defineProps({ items: { type: Array, default: () => [] }, loading: Boolean, enabled: { default: null }, error: String, signedIn: Boolean, demo: Boolean })
const labels = { USER_PREFERENCE: '用户偏好', EPISODIC_TASK: '历史任务', IMPORTANT_FACT: '重要事实', PREVIOUS_RESULT: '历史结论', USER_FEEDBACK: '用户反馈' }
</script>
