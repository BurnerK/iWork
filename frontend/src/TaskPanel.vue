<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import MarkdownContent from './MarkdownContent.vue'
import DiagnosticsPanel from './DiagnosticsPanel.vue'
import DocumentPanel from './DocumentPanel.vue'
import ParsingPanel from './ParsingPanel.vue'
import UsagePanel from './UsagePanel.vue'
import MaterialPanel from './MaterialPanel.vue'
import { ApiError, errorMessage, request } from './api'
const props = defineProps<{
  conversationId?: string
  ownerId: string
  projectId?: string | null
  initialVersionId?: string
}>()
const emit = defineEmits<{
  failure: [cause: unknown]
  sent: [id: string]
  refreshConversations: []
}>()
type Task = {
  agentName?: string
  id: string
  publishedVersion?: PublishedVersion | null
  requestId: string
  status: string
  executionState: string
  errorCode: string | null
}
type PublishedVersion = { versionId: string; title: string; versionNumber: number }
type Message = { id: number; taskId: string; role: string; content: string }
type Page<T> = { items: T[]; hasMore: boolean }
const models = ref<{ id: string; name: string }[]>([]),
  modelId = ref(''),
  message = ref(''),
  outputMode = ref('TEXT')
type Agent = { id: string; name: string; modelId: string }
const agents = ref<Agent[]>([]),
  agentId = ref(''),
  agentPage = ref(0),
  moreAgents = ref(false),
  loadingAgents = ref(false)
async function loadAgents(next = 0) {
  loadingAgents.value = true
  try {
    const result = await request<Page<Agent>>(`/api/agents?page=${next}`)
    if (disposed) return
    agents.value = next
      ? [
          ...agents.value,
          ...result.items.filter((item) => !agents.value.some((old) => old.id === item.id)),
        ]
      : result.items
    agentPage.value = next
    moreAgents.value = result.hasMore
    if (agentId.value && !agents.value.some((item) => item.id === agentId.value))
      agents.value.push(await request<Agent>(`/api/agents/${agentId.value}`))
  } catch (cause) {
    await failure(cause)
  } finally {
    loadingAgents.value = false
  }
}
const tasks = ref<Task[]>([]),
  messages = ref<Message[]>([]),
  busy = ref(false),
  loading = ref(false),
  error = ref('')
const materialIds = ref<string[]>([]),
  materialBusy = ref(false)
const baseVersion = ref<{ versionId: string; title: string; versionNumber: number }>()
function selectVersion(version: { versionId: string; title: string; versionNumber: number }) {
  if (busy.value || pending.value || active.value) return
  baseVersion.value = version
  outputMode.value = 'DOCUMENT'
  document.getElementById('task-message')?.focus()
}
const pending = ref<{
  baseVersionId?: string
  requestId: string
  modelId?: string
  agentId?: string
  message: string
  materialIds?: string[]
  outputMode?: string
} | null>(null)
const conversationId = ref(props.conversationId)
const creationUnconfirmed = ref(false)
const thread = ref<HTMLElement>()
const showLatest = ref(false)
const materialsOpen = ref(false)
function trackScroll() {
  const el = thread.value
  if (el) showLatest.value = el.scrollHeight - el.scrollTop - el.clientHeight > 32
}
function scrollLatest() {
  if (thread.value) thread.value.scrollTop = thread.value.scrollHeight
  trackScroll()
}
const storageKey = computed(() => `iwork-pending:${props.ownerId}:${conversationId.value}`)
const draftKey = computed(() => `iwork-first-send:${props.ownerId}`)
function acceptPending() {
  clearPending()
  message.value = ''
  baseVersion.value = undefined
  materialIds.value = []
  if (!disposed && !props.conversationId && conversationId.value) emit('sent', conversationId.value)
}
function clearPending() {
  localStorage.removeItem(storageKey.value)
  pending.value = null
}
const diagnosticTaskId = ref('')
const usageTaskId = ref(''),
  parsingTaskId = ref('')
const taskPage = ref(0),
  moreTasks = ref(false),
  moreMessages = ref(false)
const active = computed(() => tasks.value.some((task) => task.status === 'RUNNING'))
const labels: Record<string, string> = {
  RUNNING: '运行中',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
  INTERRUPTED: '重启中断',
}
const turns = computed(() => {
  const groups = new Map<string, { messages: Message[]; task?: Task }>()
  for (const message of messages.value) {
    const group = groups.get(message.taskId) ?? { messages: [] }
    group.messages.push(message)
    groups.set(message.taskId, group)
  }
  for (const task of [...tasks.value].reverse()) {
    const group = groups.get(task.id) ?? { messages: [] }
    group.task = task
    groups.set(task.id, group)
  }
  return groups
})
let timer: ReturnType<typeof setTimeout> | undefined,
  disposed = false
async function failure(cause: unknown) {
  if (disposed) return
  error.value = errorMessage(cause)
  if (cause instanceof ApiError && cause.status === 401) emit('failure', cause)
}
async function load() {
  if (!conversationId.value || loading.value || disposed) return
  loading.value = true
  try {
    const [recent, incoming] = await Promise.all([
      request<Page<Task>>(`/api/conversations/${conversationId.value}/tasks`),
      request<Page<Message>>(
        `/api/conversations/${conversationId.value}/messages?after=${messages.value.at(-1)?.id || 0}`
      ),
    ])
    if (disposed) return
    tasks.value = [
      ...recent.items,
      ...tasks.value.filter((task) => !recent.items.some((item) => item.id === task.id)),
    ]
    if (taskPage.value === 0) moreTasks.value = recent.hasMore
    const follow = !showLatest.value
    messages.value.push(...incoming.items)
    if (follow && incoming.items.length) void nextTick(scrollLatest)
    moreMessages.value = incoming.hasMore
    if (
      !busy.value &&
      pending.value &&
      recent.items.some((task) => task.requestId === pending.value?.requestId)
    ) {
      acceptPending()
    }
  } catch (cause) {
    await failure(cause)
  } finally {
    loading.value = false
  }
}
async function poll() {
  if (disposed) return
  if (!document.hidden) await load()
  if (!disposed) timer = setTimeout(poll, 2000)
}
async function submit() {
  if (
    busy.value ||
    creationUnconfirmed.value ||
    materialBusy.value ||
    (active.value && !pending.value)
  )
    return
  if (!pending.value && (!message.value.trim() || (!agentId.value && !modelId.value))) return
  busy.value = true
  error.value = ''
  try {
    if (!conversationId.value) {
      try {
        const title = message.value
          .trim()
          .slice(0, 200)
          .replace(/[\uD800-\uDBFF]$/, '')
        const created = await request<{ id: string }>('/api/conversations', 'POST', { title })
        if (disposed) return
        conversationId.value = created.id
      } catch (cause) {
        if (!(cause instanceof ApiError && cause.status >= 400 && cause.status < 500)) {
          creationUnconfirmed.value = true
          error.value = '未确认会话是否创建成功。请先核对会话列表。'
          emit('refreshConversations')
        } else await failure(cause)
        return
      }
    }
    pending.value ||= {
      requestId: crypto.randomUUID(),
      ...(agentId.value ? { agentId: agentId.value } : { modelId: modelId.value }),
      message: message.value,
      outputMode: outputMode.value,
      baseVersionId: baseVersion.value?.versionId,
      materialIds: [...materialIds.value],
    }
    localStorage.setItem(storageKey.value, JSON.stringify(pending.value))
    if (!props.conversationId) sessionStorage.setItem(draftKey.value, conversationId.value)
    await request(`/api/conversations/${conversationId.value}/tasks`, 'POST', pending.value)
    acceptPending()
    await load()
  } catch (cause) {
    if (
      cause instanceof ApiError &&
      ((cause.status >= 400 && cause.status < 500) || cause.code === 'execution_unavailable')
    )
      clearPending()
    await failure(cause)
  } finally {
    busy.value = false
  }
}
async function cancel(task: Task) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await request(`/api/tasks/${task.id}/cancel`, 'POST', {})
    await load()
  } catch (cause) {
    await failure(cause)
  } finally {
    busy.value = false
  }
}
async function earlierTasks() {
  if (busy.value) return
  busy.value = true
  try {
    const result = await request<Page<Task>>(
      `/api/conversations/${conversationId.value}/tasks?page=${taskPage.value + 1}`
    )
    taskPage.value++
    moreTasks.value = result.hasMore
    tasks.value.push(
      ...result.items.filter((item) => !tasks.value.some((task) => task.id === item.id))
    )
  } catch (cause) {
    await failure(cause)
  } finally {
    busy.value = false
  }
}
onMounted(async () => {
  const firstConversation = sessionStorage.getItem(draftKey.value)
  if (!conversationId.value && firstConversation) {
    emit('sent', firstConversation)
    return
  }
  if (firstConversation && conversationId.value === firstConversation)
    sessionStorage.removeItem(draftKey.value)
  try {
    const stored = conversationId.value && localStorage.getItem(storageKey.value)
    if (stored) {
      const restored = JSON.parse(stored)
      if (
        typeof restored.requestId === 'string' &&
        (typeof restored.modelId === 'string' || typeof restored.agentId === 'string') &&
        typeof restored.message === 'string'
      ) {
        pending.value = restored
        outputMode.value = restored.outputMode || 'TEXT'
        modelId.value = restored.modelId || ''
        agentId.value = restored.agentId || ''
        message.value = restored.message
        materialIds.value = Array.isArray(restored.materialIds) ? restored.materialIds : []
        if (typeof restored.baseVersionId === 'string')
          baseVersion.value = await request(`/api/document-versions/${restored.baseVersionId}`)
      } else localStorage.removeItem(storageKey.value)
    }
  } catch (cause) {
    await failure(cause)
  }
  if (!pending.value && props.initialVersionId) {
    try {
      baseVersion.value = await request(
        `/api/document-versions/${encodeURIComponent(props.initialVersionId)}`
      )
      outputMode.value = 'DOCUMENT'
    } catch (cause) {
      await failure(cause)
    }
  }
  try {
    models.value = await request('/api/models')
  } catch (cause) {
    await failure(cause)
  }
  await loadAgents()
  await poll()
})
onUnmounted(() => {
  disposed = true
  clearTimeout(timer)
})
</script>
<template>
  <section class="task-workspace" aria-label="会话消息与后台任务">
    <div ref="thread" class="task-thread" @scroll.passive="trackScroll">
      <p v-if="error" role="alert">{{ error }}</p>
      <div v-if="!conversationId" class="conversation-welcome">
        <h2>今天想推进哪件事？</h2>
      </div>
      <p v-else-if="!messages.length && !loading">暂时没有消息。</p>
      <button v-if="moreMessages" type="button" class="secondary" :disabled="loading" @click="load">
        继续加载消息
      </button>
      <DocumentPanel
        v-if="conversationId"
        :conversation-id="conversationId"
        conversation-preview
        :disabled="busy || !!pending || active"
        @select-version="selectVersion"
        :revision="tasks.map((task) => task.id + task.status).join(',')"
        v-slot="{ preview, previewing }"
      >
        <div v-for="[taskId, turn] in turns" :key="taskId" class="task-turn">
          <ol class="messages">
            <li
              v-for="item in turn.messages"
              :key="item.id"
              :class="{ 'user-message': item.role === 'user' }"
            >
              <small>{{ item.role === 'user' ? '你' : '助手' }}</small>
              <p v-if="item.role === 'user'">{{ item.content }}</p>
              <MarkdownContent v-else :content="item.content" />
            </li>
          </ol>
          <div v-if="turn.task" class="task-status" aria-label="任务状态">
            <div>
              <span role="status">{{ labels[turn.task.status] || turn.task.status }}</span><small
                v-if="turn.task.agentName"
                >智能体：{{ turn.task.agentName }}（提交时配置）</small
              ><small v-if="turn.task.executionState === 'STOP_REQUESTED'"
                >已请求停止，执行结束尚待确认。</small
              ><small
                v-if="turn.task.status === 'CANCELLED' && turn.task.executionState === 'STOPPED'"
                >本地执行已结束，不代表供应商已停止。处理容器状态见资料处理。</small
              ><small v-if="turn.task.executionState === 'UNKNOWN'">执行状态未知。</small
              ><small v-if="turn.task.errorCode">{{
                errorMessage(new ApiError(400, turn.task.errorCode))
              }}</small>
            </div>
            <button
              v-if="turn.task.status === 'RUNNING'"
              type="button"
              class="secondary"
              :disabled="busy"
              @click="cancel(turn.task)"
            >
              取消任务
            </button>
            <details class="task-details">
              <summary>任务详情</summary>
              <small>任务 {{ taskId }}</small>
              <button
                type="button"
                class="secondary"
                :aria-expanded="usageTaskId === taskId"
                @click="usageTaskId = usageTaskId === taskId ? '' : taskId"
              >
                查看用量</button
              ><button
                type="button"
                class="secondary"
                :aria-expanded="parsingTaskId === taskId"
                @click="parsingTaskId = parsingTaskId === taskId ? '' : taskId"
              >
                资料处理</button
              ><button
                type="button"
                class="secondary"
                :aria-expanded="diagnosticTaskId === taskId"
                @click="diagnosticTaskId = diagnosticTaskId === taskId ? '' : taskId"
              >
                运行诊断
              </button>
            </details>
            <button
              v-if="turn.task?.publishedVersion"
              type="button"
              class="artifact-card"
              :disabled="previewing"
              @click="preview(turn.task.publishedVersion)"
            >
              <img :src="'/assets/icons/file-text.svg'" alt="" />
              {{ turn.task.publishedVersion.title }} · 版本
              {{ turn.task.publishedVersion.versionNumber }}
            </button>
          </div>
        </div>
      </DocumentPanel>
      <DiagnosticsPanel
        v-if="diagnosticTaskId"
        :key="diagnosticTaskId"
        :task-id="diagnosticTaskId"
      />
      <ParsingPanel v-if="parsingTaskId" :key="parsingTaskId" :task-id="parsingTaskId" />
      <UsagePanel
        v-if="usageTaskId"
        :key="usageTaskId"
        :task-id="usageTaskId"
        :running="tasks.some((task) => task.id === usageTaskId && task.status === 'RUNNING')"
      />
      <button
        v-if="moreTasks"
        type="button"
        class="secondary"
        :disabled="busy"
        @click="earlierTasks"
      >
        较早的任务
      </button>
      <button
        v-if="conversationId"
        type="button"
        class="secondary"
        :disabled="loading"
        @click="load"
      >
        刷新任务
      </button>
    </div>
    <button v-if="showLatest" class="secondary latest-message" type="button" @click="scrollLatest">
      回到最新消息
    </button>
    <div class="task-composer">
      <div v-show="materialsOpen" id="composer-materials" class="composer-materials">
        <MaterialPanel
          v-if="projectId"
          :key="projectId"
          :project-id="projectId"
          v-model="materialIds"
          :disabled="busy || !!pending || active"
          @failure="failure"
          @uploading="materialBusy = $event"
        />
        <MaterialPanel
          v-model="materialIds"
          :disabled="busy || !!pending || active"
          @failure="failure"
          @uploading="materialBusy = $event"
        />
      </div>
      <form
        method="post"
        :action="conversationId ? `/api/conversations/${conversationId}/tasks` : undefined"
        aria-label="提交任务"
        @submit.prevent="submit"
      >
        <label for="task-message">工作要求（必填）</label
        ><textarea
          id="task-message"
          v-model="message"
          name="message"
          placeholder="描述你的任务，或继续追问…"
          required
          maxlength="20000"
          rows="2"
          :disabled="busy || !!pending || active"
        ></textarea>
        <div class="composer-toolbar">
          <button
            type="button"
            class="composer-material-toggle"
            :aria-expanded="materialsOpen"
            aria-controls="composer-materials"
            @click="materialsOpen = !materialsOpen"
          >
            <span aria-hidden="true">＋</span> 资料<span v-if="materialIds.length"
              >({{ materialIds.length }})</span
            >
          </button>
          <div class="composer-options">
            <label class="composer-label" for="task-agent">使用方式</label
            ><select
              id="task-agent"
              v-model="agentId"
              name="agentId"
              :disabled="busy || !!pending || active"
            >
              <option value="">直接选择模型</option>
              <option v-for="agent in agents" :key="agent.id" :value="agent.id">
                {{ agent.name }}
              </option>
            </select>
            <button
              v-if="moreAgents"
              type="button"
              class="secondary"
              :disabled="loadingAgents || busy || !!pending || active"
              @click="loadAgents(agentPage + 1)"
            >
              加载更多智能体
            </button>
            <p v-if="agentId" class="hint">
              使用所选智能体提交时的配置。模型：{{
                models.find(
                  (model) => model.id === agents.find((agent) => agent.id === agentId)?.modelId
                )?.name || '当前不可用，请检查授权'
              }}。
            </p>
            <template v-else
              ><label class="composer-label" for="task-model">模型（必选）</label
              ><select
                id="task-model"
                v-model="modelId"
                name="modelId"
                required
                :disabled="busy || !!pending || active"
              >
                <option value="">请选择模型</option>
                <option v-for="model in models" :key="model.id" :value="model.id">
                  {{ model.name }}
                </option>
              </select></template
            >
            <p v-if="baseVersion" role="status">
              基于《{{ baseVersion.title }}》版本 {{ baseVersion.versionNumber }} 修改。<button
                type="button"
                class="secondary"
                :disabled="busy || !!pending || active"
                @click="baseVersion = undefined"
              >
                清除来源版本
              </button>
            </p>
            <label class="composer-label" for="task-output">成果形式</label
            ><select
              id="task-output"
              v-model="outputMode"
              name="outputMode"
              :disabled="busy || !!pending || active || !!baseVersion"
            >
              <option value="TEXT">文字回复</option>
              <option value="DOCUMENT">Markdown 文档</option>
            </select>
          </div>
          <button
            type="submit"
            :disabled="busy || materialBusy || creationUnconfirmed || (active && !pending)"
          >
            {{ pending ? '重试确认' : '提交任务' }}
          </button>
        </div>
        <button
          v-if="creationUnconfirmed"
          type="button"
          class="secondary"
          @click="creationUnconfirmed = false"
        >
          已核对，允许重新提交
        </button>
        <p v-if="pending" class="hint">尚未确认受理结果。重试使用同一请求，不会重复执行。</p>
        <p v-else-if="active" class="hint">当前会话已有运行任务。关闭页面不会取消任务。</p>
      </form>
    </div>
  </section>
</template>
