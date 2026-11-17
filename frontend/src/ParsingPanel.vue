<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { ApiError, errorMessage, request } from './api'
const props = defineProps<{ taskId: string }>()
type Parse = {
  id: string
  name: string
  status: string
  cleanup: string
  characters: number | null
  errorCode: string | null
}
const items = ref<Parse[]>([]),
  loading = ref(false),
  error = ref(''),
  selected = ref(''),
  text = ref(''),
  offset = ref(0),
  more = ref(false)
const labels: Record<string, string> = {
  RUNNING: '解析中',
  COMPLETED: '已解析',
  FAILED: '解析失败',
  CANCELLED: '已取消',
  INTERRUPTED: '解析中断',
}
let timer: ReturnType<typeof setTimeout> | undefined,
  disposed = false
async function load() {
  if (loading.value || disposed) return
  loading.value = true
  try {
    const result = await request<Parse[]>(`/api/tasks/${props.taskId}/parses`)
    if (!disposed) items.value = result
  } catch (cause) {
    if (!disposed) error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}
async function read(id: string, next = false) {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const page = await request<{ text: string; nextOffset: number; hasMore: boolean }>(
      `/api/tasks/${props.taskId}/parses/${id}?offset=${next ? offset.value : 0}`
    )
    if (!disposed) {
      selected.value = id
      text.value = page.text
      offset.value = page.nextOffset
      more.value = page.hasMore
    }
  } catch (cause) {
    if (!disposed) error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}
async function poll() {
  if (disposed) return
  if (!document.hidden) await load()
  if (!disposed) timer = setTimeout(poll, 2000)
}
onMounted(poll)
onUnmounted(() => {
  disposed = true
  clearTimeout(timer)
})
</script>
<template>
  <section aria-label="任务资料处理">
    <h2>资料处理</h2>
    <p class="hint">解析文本是本次任务的材料，不是正式文档成果。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="!items.length && !loading">本次任务尚未调用资料解析。</p>
    <ul class="rows">
      <li v-for="item in items" :key="item.id">
        <div>
          <p>{{ item.name }} · {{ labels[item.status] || item.status }}</p>
          <small>{{
            item.cleanup === 'CONFIRMED' ? '处理容器已确认清理' : '处理容器清理尚未确认'
          }}</small><small v-if="item.errorCode">{{
            errorMessage(new ApiError(400, item.errorCode))
          }}</small
          ><small v-if="item.characters !== null">{{ item.characters }} 字符</small>
        </div>
        <button
          v-if="item.characters !== null"
          type="button"
          class="secondary"
          :disabled="loading"
          @click="read(item.id)"
        >
          查看解析文本
        </button>
      </li>
    </ul>
    <div v-if="selected" class="messages">
      <p>{{ text }}</p>
      <button
        v-if="more"
        type="button"
        class="secondary"
        :disabled="loading"
        @click="read(selected, true)"
      >
        下一段
      </button>
    </div>
    <button type="button" class="secondary" :disabled="loading" @click="load">刷新处理状态</button>
  </section>
</template>
