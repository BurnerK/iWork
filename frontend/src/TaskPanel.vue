<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
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
  outputMode?: string
  id: string
  publishedVersion?: PublishedVersion | null
  requestId: string
  status: string
  executionState: string
  errorCode: string | null
}
type PublishedVersion = { versionId: string; title: string; versionNumber: number }
type Message = {
  id: number
  taskId: string
  role: string
  content: string
  publishedVersion?: PublishedVersion | null
}
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
const target = computed({
  get: () => (agentId.value ? `agent:${agentId.value}` : modelId.value),
  set: (value: string) => {
    if (value.startsWith('agent:')) {
      agentId.value = value.slice('agent:'.length)
    } else {
      agentId.value = ''
      modelId.value = value
    }
  },
})
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
const materialNames = reactive(new Map<string, string>())
const selectedMaterials = computed(() =>
  materialIds.value.map((id) => ({ id, name: materialNames.get(id) }))
)
function rememberMaterialNames(items: { id: string; name?: string }[]) {
  for (const { id, name } of items) {
    if (name !== undefined) materialNames.set(id, name)
  }
}
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
const materialsToggle = ref<HTMLButtonElement>()
const composerTools = ref<HTMLElement>()
async function toggleMaterials() {
  materialsOpen.value = !materialsOpen.value
  if (materialsOpen.value) {
    await nextTick()
    composerTools.value?.focus()
  }
}
function closeMaterials(event: KeyboardEvent) {
  if (!materialsOpen.value || event.isComposing) return
  materialsOpen.value = false
  materialsToggle.value?.focus()
}
function trackScroll() {
  const el = thread.value
  if (el) showLatest.value = el.scrollHeight - el.scrollTop - el.clientHeight > 32
}
function scrollLatest() {
  if (thread.value) thread.value.scrollTop = thread.value.scrollHeight
  trackScroll()
}
function keepFollowing() {
  if (!showLatest.value) scrollLatest()
}
function returnToLatest() {
  scrollLatest()
  thread.value?.focus({ preventScroll: true })
}
const liveReply = ref<{ taskId: string; version: number; content: string }>()
const streamingTaskId = computed(
  () => tasks.value.find((task) => task.status === 'RUNNING' && task.outputMode !== 'DOCUMENT')?.id
)
watch(streamingTaskId, (taskId, _, onCleanup) => {
  if (!taskId || typeof EventSource === 'undefined') return
  const stream = new EventSource(`/api/tasks/${encodeURIComponent(taskId)}/reply-stream`)
  let closed = false
  const close = () => {
    closed = true
    stream.close()
  }
  onCleanup(close)
  stream.addEventListener('reply', (event: MessageEvent<string>) => {
    if (closed) return
    try {
      const update = JSON.parse(event.data)
      if (typeof update?.content !== 'string' || !Number.isSafeInteger(update.version)) return
      if (liveReply.value?.taskId === taskId && liveReply.value.version >= update.version) return
      liveReply.value = { taskId, version: update.version, content: update.content }
      void nextTick(keepFollowing)
    } catch {
      // The next valid snapshot or the saved reply can still be rendered.
    }
  })
  stream.addEventListener('done', () => {
    if (closed) return
    close()
    void load()
  })
})
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
  const groups = new Map<
    string,
    { messages: Message[]; task?: Task; publishedVersion?: PublishedVersion | null }
  >()
  for (const message of messages.value) {
    const group = groups.get(message.taskId) ?? { messages: [] }
    group.messages.push(message)
    group.publishedVersion ??= message.publishedVersion
    groups.set(message.taskId, group)
  }
  for (const task of [...tasks.value].reverse()) {
    const group = groups.get(task.id) ?? { messages: [] }
    group.task = task
    group.publishedVersion ??= task.publishedVersion
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
    if (follow) void nextTick(keepFollowing)
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
function sendOnEnter(event: KeyboardEvent) {
  if (event.shiftKey || event.ctrlKey || event.metaKey || event.altKey) return
  if (window.matchMedia('(pointer: coarse)').matches) return
  // Safari can report the IME confirmation after compositionend.
  if (event.isComposing || event.keyCode === 229) return
  event.preventDefault()
  if (!event.repeat) (event.currentTarget as HTMLTextAreaElement).form?.requestSubmit()
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
    localStorage.setItem(
      storageKey.value,
      JSON.stringify({ ...pending.value, selectedMaterials: selectedMaterials.value })
    )
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
      const { selectedMaterials: storedMaterials, ...restored } = JSON.parse(stored)
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
        if (Array.isArray(storedMaterials)) rememberMaterialNames(storedMaterials)
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
    <div
      ref="thread"
      class="task-thread"
      tabindex="0"
      aria-label="会话消息"
      @scroll.passive="trackScroll"
    >
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
        v-slot="{ preview, previewing, previewError }"
      >
        <div v-for="[taskId, turn] in turns" :key="taskId" class="task-turn">
          <ol class="messages">
            <li
              v-for="item in turn.messages.filter((item) => item.role === 'user')"
              :key="item.id"
              class="user-message"
            >
              <small>你</small>
              <p>{{ item.content }}</p>
            </li>
            <li
              v-if="
                turn.task?.status === 'RUNNING' ||
                turn.publishedVersion ||
                turn.messages.some((item) => item.role === 'assistant') ||
                (turn.task?.status === 'COMPLETED' && liveReply?.taskId === taskId)
              "
              aria-label="助手"
            >
              <p v-if="turn.task?.status === 'RUNNING'" class="hint" role="status">
                {{ labels.RUNNING }}
              </p>
              <MarkdownContent
                v-for="item in turn.messages.filter((item) => item.role === 'assistant')"
                :key="item.id"
                :content="item.content"
              />
              <MarkdownContent
                v-if="
                  liveReply?.taskId === taskId &&
                  liveReply.content &&
                  !turn.messages.some((item) => item.role === 'assistant')
                "
                :content="liveReply.content"
              />
              <button
                v-if="turn.publishedVersion"
                type="button"
                class="artifact-card"
                aria-haspopup="dialog"
                :aria-disabled="previewing"
                @click="preview(turn.publishedVersion, true)"
              >
                <img src="/assets/icons/file-text.svg" alt="" />
                {{ turn.publishedVersion.title }} · 版本
                {{ turn.publishedVersion.versionNumber }}
              </button>
              <p
                v-if="previewError && previewError.versionId === turn.publishedVersion?.versionId"
                role="alert"
              >
                {{ previewError.message }}
              </p>
            </li>
          </ol>
          <div v-if="turn.task" class="task-status" aria-label="任务状态">
            <div>
              <span v-if="turn.task.status !== 'RUNNING'" role="status">{{
                labels[turn.task.status] || turn.task.status
              }}</span><small v-if="turn.task.agentName"
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
              <small v-if="turn.task.status === 'RUNNING'">关闭页面不会取消任务。</small>
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
    <button
      v-if="showLatest"
      class="secondary latest-message"
      type="button"
      @click="returnToLatest"
    >
      回到最新消息
    </button>
    <div class="task-composer" @keydown.esc="closeMaterials">
      <div
        v-show="materialsOpen"
        id="composer-materials"
        ref="composerTools"
        class="composer-materials"
        role="group"
        aria-label="资料与成果形式"
        tabindex="-1"
      >
        <label for="task-output">成果形式</label>
        <select
          id="task-output"
          v-model="outputMode"
          :disabled="busy || !!pending || active || !!baseVersion"
        >
          <option value="TEXT">文字回复</option>
          <option value="DOCUMENT">Markdown 文档</option>
        </select>
        <MaterialPanel
          v-if="projectId"
          :key="projectId"
          :project-id="projectId"
          v-model="materialIds"
          :disabled="busy || !!pending || active"
          @material-names="rememberMaterialNames"
          @failure="failure"
          @uploading="materialBusy = $event"
        />
        <MaterialPanel
          v-model="materialIds"
          :disabled="busy || !!pending || active"
          @material-names="rememberMaterialNames"
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
          enterkeyhint="enter"
          :disabled="busy || !!pending || active"
          @keydown.enter="sendOnEnter"
        ></textarea>
        <ul
          v-if="selectedMaterials.length"
          class="composer-selection"
          role="list"
          aria-label="已选资料"
        >
          <li v-for="item in selectedMaterials" :key="item.id">
            {{ item.name ?? '文件名暂不可用' }}
          </li>
        </ul>
        <p v-if="baseVersion" class="hint" role="status">
          基于《{{ baseVersion.title }}》版本 {{ baseVersion.versionNumber }} 修改。<button
            type="button"
            class="secondary"
            :disabled="busy || !!pending || active"
            @click="baseVersion = undefined"
          >
            清除来源版本
          </button>
        </p>
        <p v-else-if="outputMode === 'DOCUMENT'" class="hint">成果形式为 Markdown 文档</p>
        <div class="composer-toolbar">
          <button
            ref="materialsToggle"
            type="button"
            class="composer-material-toggle"
            :aria-expanded="materialsOpen"
            aria-controls="composer-materials"
            title="资料与成果形式"
            @click="toggleMaterials"
          >
            <span aria-hidden="true">＋</span> 资料<span v-if="materialIds.length"
              >({{ materialIds.length }})</span
            >
          </button>
          <label class="composer-label" for="task-model">模型或智能体（必选）</label>
          <select
            id="task-model"
            v-model="target"
            name="target"
            required
            :aria-describedby="agentId ? 'composer-agent-model' : undefined"
            :disabled="busy || !!pending || active"
          >
            <option value="" disabled>选择模型或智能体</option>
            <optgroup v-if="models.length" label="模型">
              <option v-for="model in models" :key="model.id" :value="model.id">
                {{ model.name }}
              </option>
            </optgroup>
            <optgroup v-if="agents.length" label="智能体">
              <option v-for="agent in agents" :key="agent.id" :value="`agent:${agent.id}`">
                {{ agent.name }}
              </option>
            </optgroup>
          </select>
          <button
            type="submit"
            :class="{ 'composer-send': !pending }"
            :aria-label="pending ? undefined : '提交任务'"
            :title="pending ? undefined : '提交任务'"
            :disabled="
              busy ||
              materialBusy ||
              creationUnconfirmed ||
              (!pending && (active || !message.trim()))
            "
          >
            <template v-if="pending">重试确认</template>
            <img v-else src="/assets/icons/arrow-up.svg" width="24" height="24" alt="" />
          </button>
        </div>
        <p v-if="agentId" id="composer-agent-model" class="hint">
          智能体模型：{{
            models.find(
              (model) => model.id === agents.find((agent) => agent.id === agentId)?.modelId
            )?.name || '当前不可用，请检查授权'
          }}。
        </p>
        <button
          v-if="moreAgents"
          type="button"
          class="secondary"
          :disabled="loadingAgents || busy || !!pending || active"
          @click="loadAgents(agentPage + 1)"
        >
          加载更多智能体
        </button>
        <button
          v-if="creationUnconfirmed"
          type="button"
          class="secondary"
          @click="creationUnconfirmed = false"
        >
          已核对，允许重新提交
        </button>
        <p v-if="pending" class="hint">尚未确认受理结果。重试使用同一请求，不会重复执行。</p>
      </form>
    </div>
  </section>
</template>
