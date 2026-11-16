<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import DocumentPanel from './DocumentPanel.vue'
import ParsingPanel from './ParsingPanel.vue'
import UsagePanel from './UsagePanel.vue'
import MaterialPanel from './MaterialPanel.vue'
import { ApiError, errorMessage, request } from './api'
const props = defineProps<{ conversationId: string; ownerId: string; projectId?: string | null; initialVersionId?: string }>()
const emit = defineEmits<{ failure: [cause: unknown] }>()
type Task = { agentName?: string; id: string; requestId: string; status: string; executionState: string; errorCode: string | null }
type Message = { id: number; taskId: string; role: string; content: string }
type Page<T> = { items: T[]; hasMore: boolean }
const models = ref<{ id: string; name: string }[]>([]), modelId = ref(''), message = ref(''), outputMode = ref('TEXT')
type Agent = { id: string; name: string; modelId: string }
const agents = ref<Agent[]>([]), agentId = ref(''), agentPage = ref(0), moreAgents = ref(false), loadingAgents = ref(false)
async function loadAgents(next = 0) {
  loadingAgents.value = true
  try {
    const result = await request<Page<Agent>>(`/api/agents?page=${next}`)
    if (disposed) return
    agents.value = next ? [...agents.value, ...result.items.filter(item => !agents.value.some(old => old.id === item.id))] : result.items
    agentPage.value = next; moreAgents.value = result.hasMore
    if (agentId.value && !agents.value.some(item => item.id === agentId.value)) agents.value.push(await request<Agent>(`/api/agents/${agentId.value}`))
  } catch (cause) { await failure(cause) } finally { loadingAgents.value = false }
}
const tasks = ref<Task[]>([]), messages = ref<Message[]>([]), busy = ref(false), loading = ref(false), error = ref('')
const materialIds = ref<string[]>([]), materialBusy = ref(false)
const baseVersion = ref<{ versionId: string; title: string; versionNumber: number }>()
function selectVersion(version: { versionId: string; title: string; versionNumber: number }) {
  if (busy.value || pending.value || active.value) return
  baseVersion.value = version; outputMode.value = 'DOCUMENT'; document.getElementById('task-message')?.focus()
}
const pending = ref<{ baseVersionId?: string; requestId: string; modelId?: string; agentId?: string; message: string; materialIds?: string[]; outputMode?: string } | null>(null)
const storageKey = `iwork-pending:${props.ownerId}:${props.conversationId}`
function clearPending() { localStorage.removeItem(storageKey); pending.value = null }
const usageTaskId = ref(''), parsingTaskId = ref('')
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
    if (pending.value && recent.items.some(task => task.requestId === pending.value?.requestId)) { clearPending(); message.value = ''; baseVersion.value = undefined }
  } catch (cause) { await failure(cause) }
  finally { loading.value = false }
}
async function poll() {
  if (disposed) return
  if (!document.hidden) await load()
  if (!disposed) timer = setTimeout(poll, 2000)
}
async function submit() {
  if (busy.value || materialBusy.value || active.value && !pending.value) return
  busy.value = true; error.value = ''
  try {
    pending.value ||= { requestId: crypto.randomUUID(), ...(agentId.value ? { agentId: agentId.value } : { modelId: modelId.value }), message: message.value, outputMode: outputMode.value, baseVersionId: baseVersion.value?.versionId, materialIds: [...materialIds.value] }
    localStorage.setItem(storageKey, JSON.stringify(pending.value))
    await request(`/api/conversations/${props.conversationId}/tasks`, 'POST', pending.value)
    clearPending(); message.value = ''; baseVersion.value = undefined; await load()
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
      if (typeof restored.requestId === 'string' && (typeof restored.modelId === 'string' || typeof restored.agentId === 'string') && typeof restored.message === 'string') {
        pending.value = restored; outputMode.value = restored.outputMode || 'TEXT'; modelId.value = restored.modelId || ''; agentId.value = restored.agentId || ''; message.value = restored.message; materialIds.value = Array.isArray(restored.materialIds) ? restored.materialIds : []
        if (typeof restored.baseVersionId === 'string') baseVersion.value = await request(`/api/document-versions/${restored.baseVersionId}`)
      } else localStorage.removeItem(storageKey)
    }
  } catch (cause) { await failure(cause) }
  if (!pending.value && props.initialVersionId) {
    try { baseVersion.value = await request(`/api/document-versions/${encodeURIComponent(props.initialVersionId)}`); outputMode.value = 'DOCUMENT' } catch (cause) { await failure(cause) }
  }
  try { models.value = await request('/api/models') } catch (cause) { await failure(cause) }
  await loadAgents()
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
    <ul class="rows" aria-label="任务状态"><li v-for="task in tasks" :key="task.id"><div><span role="status">{{ labels[task.status] || task.status }}</span><small>任务 {{ task.id }}</small><small v-if="task.agentName">智能体：{{ task.agentName }}（提交时配置）</small><small v-if="task.executionState === 'STOP_REQUESTED'">已请求停止，执行结束尚待确认。</small><small v-if="task.status === 'CANCELLED' && task.executionState === 'STOPPED'">本地执行已结束，不代表供应商已停止。处理容器状态见资料处理。</small><small v-if="task.executionState === 'UNKNOWN'">执行状态未知。</small><small v-if="task.errorCode">{{ errorMessage(new ApiError(400, task.errorCode)) }}</small></div><button v-if="task.status === 'RUNNING'" type="button" class="secondary" :disabled="busy" @click="cancel(task)">取消任务</button><button type="button" class="secondary" :aria-expanded="usageTaskId === task.id" @click="usageTaskId = usageTaskId === task.id ? '' : task.id">查看用量</button><button type="button" class="secondary" :aria-expanded="parsingTaskId === task.id" @click="parsingTaskId = parsingTaskId === task.id ? '' : task.id">资料处理</button></li></ul>
    <ParsingPanel v-if="parsingTaskId" :key="parsingTaskId" :task-id="parsingTaskId" />
    <UsagePanel v-if="usageTaskId" :key="usageTaskId" :task-id="usageTaskId" :running="tasks.some(task => task.id === usageTaskId && task.status === 'RUNNING')" />
    <button v-if="moreTasks" type="button" class="secondary" :disabled="busy" @click="earlierTasks">较早的任务</button>
    <DocumentPanel :conversation-id="conversationId" :disabled="busy || !!pending || active" @select-version="selectVersion" :revision="tasks.map(task => task.id + task.status).join(',')" />
    <MaterialPanel v-if="projectId" :key="projectId" :project-id="projectId" v-model="materialIds" :disabled="busy || !!pending || active" @failure="failure" @uploading="materialBusy = $event" />
    <MaterialPanel v-model="materialIds" :disabled="busy || !!pending || active" @failure="failure" @uploading="materialBusy = $event" />
    <form method="post" :action="`/api/conversations/${conversationId}/tasks`" aria-label="提交任务" @submit.prevent="submit">
      <label for="task-agent">使用方式</label><select id="task-agent" v-model="agentId" name="agentId" :disabled="busy || !!pending || active"><option value="">直接选择模型</option><option v-for="agent in agents" :key="agent.id" :value="agent.id">{{ agent.name }}</option></select>
      <button v-if="moreAgents" type="button" class="secondary" :disabled="loadingAgents || busy || !!pending || active" @click="loadAgents(agentPage + 1)">加载更多智能体</button>
      <p v-if="agentId" class="hint">使用所选智能体提交时的配置。模型：{{ models.find(model => model.id === agents.find(agent => agent.id === agentId)?.modelId)?.name || '当前不可用，请检查授权' }}。</p>
      <template v-else><label for="task-model">模型（必选）</label><select id="task-model" v-model="modelId" name="modelId" required :disabled="busy || !!pending || active"><option value="">请选择模型</option><option v-for="model in models" :key="model.id" :value="model.id">{{ model.name }}</option></select></template>
      <p v-if="baseVersion" role="status">基于《{{ baseVersion.title }}》版本 {{ baseVersion.versionNumber }} 修改。<button type="button" class="secondary" :disabled="busy || !!pending || active" @click="baseVersion = undefined">清除来源版本</button></p>
      <label for="task-output">成果形式</label><select id="task-output" v-model="outputMode" name="outputMode" :disabled="busy || !!pending || active || !!baseVersion"><option value="TEXT">文字回复</option><option value="DOCUMENT">Markdown 文档（.md）</option></select>
      <label for="task-message">工作要求（必填）</label><textarea id="task-message" v-model="message" name="message" required maxlength="20000" rows="4" :disabled="busy || !!pending || active"></textarea>
      <p v-if="pending" class="hint">尚未确认受理结果。重试使用同一请求，不会重复执行。</p>
      <p v-else-if="active" class="hint">当前会话已有运行任务。关闭页面不会取消任务。</p>
      <button type="submit" :disabled="busy || materialBusy || active && !pending">{{ pending ? '重试确认' : '提交任务' }}</button>
    </form>
    <button type="button" class="secondary" :disabled="loading" @click="load">刷新任务</button>
  </section>
</template>
