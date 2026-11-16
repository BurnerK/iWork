<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ApiError, errorMessage, request } from './api'
const props = defineProps<{ conversationId: string; ownerId: string }>()
const emit = defineEmits<{ failure: [cause: unknown] }>()
type Task = { id: string; requestId: string; status: string; executionState: string; errorCode: string | null }
type Message = { id: number; taskId: string; role: string; content: string }
type Page<T> = { items: T[]; hasMore: boolean }
const models = ref<{ id: string; name: string }[]>([]), modelId = ref(''), message = ref('')
const tasks = ref<Task[]>([]), messages = ref<Message[]>([]), busy = ref(false), loading = ref(false), error = ref('')
const pending = ref<{ requestId: string; modelId: string; message: string } | null>(null)
const storageKey = `iwork-pending:${props.ownerId}:${props.conversationId}`
function clearPending() { localStorage.removeItem(storageKey); pending.value = null }
const taskPage = ref(0), moreTasks = ref(false), moreMessages = ref(false)
const active = computed(() => tasks.value.some(task => task.status === 'RUNNING'))
const labels: Record<string, string> = { RUNNING: '运行中', COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消', INTERRUPTED: '重启中断' }
let timer: ReturnType<typeof setTimeout> | undefined, disposed = false
async function failure(cause: unknown) {
  if (disposed) return
  error.value = errorMessage(cause)
  if (cause instanceof ApiError && cause.status === 401) emit('failure', cause)
}
async function load() {
  if (loading.value || disposed) return
  loading.value = true
  try {
    const [recent, incoming] = await Promise.all([
      request<Page<Task>>(`/api/conversations/${props.conversationId}/tasks`),
      request<Page<Message>>(`/api/conversations/${props.conversationId}/messages?after=${messages.value.at(-1)?.id || 0}`),
    ])
    if (disposed) return
    tasks.value = [...recent.items, ...tasks.value.filter(task => !recent.items.some(item => item.id === task.id))]
    if (taskPage.value === 0) moreTasks.value = recent.hasMore
    messages.value.push(...incoming.items); moreMessages.value = incoming.hasMore
    if (pending.value && recent.items.some(task => task.requestId === pending.value?.requestId)) { clearPending(); message.value = '' }
  } catch (cause) { await failure(cause) }
  finally { loading.value = false }
}
async function poll() {
  if (disposed) return
  if (!document.hidden) await load()
  if (!disposed) timer = setTimeout(poll, 2000)
}
async function submit() {
  if (busy.value || active.value && !pending.value) return
  busy.value = true; error.value = ''
  try {
    pending.value ||= { requestId: crypto.randomUUID(), modelId: modelId.value, message: message.value }
    localStorage.setItem(storageKey, JSON.stringify(pending.value))
    await request(`/api/conversations/${props.conversationId}/tasks`, 'POST', pending.value)
    clearPending(); message.value = ''; await load()
  } catch (cause) {
    if (cause instanceof ApiError && (cause.status >= 400 && cause.status < 500 || cause.code === 'execution_unavailable')) clearPending()
    await failure(cause)
  } finally { busy.value = false }
}
async function cancel(task: Task) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { await request(`/api/tasks/${task.id}/cancel`, 'POST', {}); await load() }
  catch (cause) { await failure(cause) } finally { busy.value = false }
}
async function earlierTasks() {
  if (busy.value) return
  busy.value = true
  try {
    const result = await request<Page<Task>>(`/api/conversations/${props.conversationId}/tasks?page=${taskPage.value + 1}`)
    taskPage.value++; moreTasks.value = result.hasMore
    tasks.value.push(...result.items.filter(item => !tasks.value.some(task => task.id === item.id)))
  } catch (cause) { await failure(cause) } finally { busy.value = false }
}
onMounted(async () => {
  try {
    const stored = localStorage.getItem(storageKey)
    if (stored) {
      const restored = JSON.parse(stored)
      if (typeof restored.requestId === 'string' && typeof restored.modelId === 'string' && typeof restored.message === 'string') {
        pending.value = restored; modelId.value = restored.modelId; message.value = restored.message
      } else localStorage.removeItem(storageKey)
    }
  } catch (cause) { await failure(cause) }
  try { models.value = await request('/api/models') } catch (cause) { await failure(cause) }
  await poll()
})
onUnmounted(() => { disposed = true; clearTimeout(timer) })
</script>
<template>
  <section aria-label="会话消息与后台任务">
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="!messages.length">暂时没有消息。</p>
    <ol class="messages"><li v-for="item in messages" :key="item.id"><small>{{ item.role === 'user' ? '你' : '助手' }}</small><p>{{ item.content }}</p></li></ol>
    <button v-if="moreMessages" type="button" class="secondary" :disabled="loading" @click="load">继续加载消息</button>
    <ul class="rows" aria-label="任务状态"><li v-for="task in tasks" :key="task.id"><div><span role="status">{{ labels[task.status] || task.status }}</span><small>任务 {{ task.id }}</small><small v-if="task.executionState === 'STOP_REQUESTED'">已请求停止，执行结束尚待确认。</small><small v-if="task.executionState === 'UNKNOWN'">执行状态未知。</small><small v-if="task.errorCode">{{ errorMessage(new ApiError(400, task.errorCode)) }}</small></div><button v-if="task.status === 'RUNNING'" type="button" class="secondary" :disabled="busy" @click="cancel(task)">取消任务</button></li></ul>
    <button v-if="moreTasks" type="button" class="secondary" :disabled="busy" @click="earlierTasks">较早的任务</button>
    <form method="post" :action="`/api/conversations/${conversationId}/tasks`" aria-label="提交任务" @submit.prevent="submit">
      <label for="task-model">模型（必选）</label><select id="task-model" v-model="modelId" name="modelId" required :disabled="busy || !!pending || active"><option value="">请选择模型</option><option v-for="model in models" :key="model.id" :value="model.id">{{ model.name }}</option></select>
      <label for="task-message">工作要求（必填）</label><textarea id="task-message" v-model="message" name="message" required maxlength="20000" rows="4" :disabled="busy || !!pending || active"></textarea>
      <p v-if="pending" class="hint">尚未确认受理结果。重试使用同一请求，不会重复执行。</p>
      <p v-else-if="active" class="hint">当前会话已有运行任务。关闭页面不会取消任务。</p>
      <button type="submit" :disabled="busy || active && !pending">{{ pending ? '重试确认' : '提交任务' }}</button>
    </form>
    <button type="button" class="secondary" :disabled="loading" @click="load">刷新任务</button>
  </section>
</template>
