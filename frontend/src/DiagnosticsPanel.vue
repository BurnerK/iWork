<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from 'vue'
import { ApiError, errorMessage, request } from './api'
const props = defineProps<{ taskId?: string; admin?: boolean }>()
type Task = {
  id: string
  ownerId: string
  conversationId: string
  modelId: string
  status: string
  executionState: string
  errorCode: string | null
  createdAt: string
  updatedAt: string
  contextState: string
  contextAt: string | null
  summaryState: string
  summaryAt: string | null
}
type Call = {
  id: string
  purpose: string
  status: string
  source: string
  errorCode: string | null
  providerState: string
  inputTokens: number | null
  outputTokens: number | null
  charged: string
  reservation: string
  currency: string
  startedAt: string
  sentAt: string | null
  usageAt: string | null
  endedAt: string | null
}
type Parse = {
  id: string
  sandboxJobId: string
  materialId: string
  status: string
  cleanup: string
  errorCode: string | null
  createdAt: string
  updatedAt: string
  cleanupAttempts: number
  cleanupAttemptedAt: string | null
  cleanupConfirmedAt: string | null
}
type Diagnostic = {
  task: Task
  calls: Call[]
  parses: Parse[]
  versions: { versionId: string; documentId: string; versionNumber: number; createdAt: string }[]
}
const items = ref<Task[]>([]),
  selected = ref<Diagnostic>(),
  target = ref(props.taskId || ''),
  page = ref(0),
  more = ref(false),
  loading = ref(false),
  error = ref('')
const labels: Record<string, string> = {
  RUNNING: '运行中',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
  PAUSED: '已暂停',
  INTERRUPTED: '重启中断',
  STOP_REQUESTED: '已请求停止，尚待确认',
  STOPPED: '本地执行已结束',
  UNKNOWN: '未知',
  MAIN: '主推理',
  SUMMARY: '上下文摘要',
  OVERFLOW: '溢出恢复',
  NOT_OBSERVED: '未记录（可能为旧任务）',
  BUSINESS_HISTORY: '从业务消息构建',
  RESTORED: '已验证并恢复快照',
  SNAPSHOT_REJECTED: '快照不一致，已改用业务消息',
  NOT_NEEDED: '未达到压缩条件',
  COMPACTED: '摘要已采用',
  FALLBACK: '摘要未采用，回退业务消息',
  PROVIDER: '供应商回报',
  PARTIAL_ESTIMATE: '累计回报与内容估算',
  OBSERVED_ESTIMATE: '已观察内容估算',
  LIMIT_ESTIMATE: '限额保守估算',
  NOT_SENT: '未发送',
  PENDING: '核算中',
}
const label = (value: string) => labels[value] || value
const time = (value: string | null) => (value ? new Date(value).toLocaleString() : '未记录')
let disposed = false
async function load(next = page.value, id = target.value) {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    if (props.admin) {
      const result = await request<{ items: Task[]; hasMore: boolean }>(
        `/api/admin/tasks?page=${next}`
      )
      if (disposed) return
      items.value = result.items
      more.value = result.hasMore
      page.value = next
    }
    if (id) {
      const result = await request<Diagnostic>(
        `/api/${props.admin ? 'admin/' : ''}tasks/${encodeURIComponent(id)}/diagnostics`
      )
      if (!disposed) {
        selected.value = result
        target.value = id
        if (props.admin) {
          await nextTick()
          document.getElementById('diagnostic-result')?.focus()
        }
      }
    }
  } catch (cause) {
    if (!disposed) {
      selected.value = undefined
      error.value = errorMessage(cause)
    }
  } finally {
    loading.value = false
  }
}
onMounted(() => load())
onUnmounted(() => {
  disposed = true
})
</script>
<template>
  <section aria-label="任务运行诊断" :aria-busy="loading">
    <h1 v-if="admin" tabindex="-1">运行诊断</h1>
    <h2 v-else>任务运行诊断</h2>
    <p class="hint">
      仅展示运行标识、时间、状态与内部用量，不展示正文、摘要或指令。下方是查询时的记录，可刷新查看清理进展。
    </p>
    <p v-if="error" role="alert">{{ error }}</p>
    <template v-if="admin">
      <form aria-label="查询任务诊断" @submit.prevent="load(0)">
        <label for="diagnostic-task">任务 ID（必填）</label
        ><input
          id="diagnostic-task"
          v-model="target"
          required
          maxlength="36"
          :disabled="loading"
        /><button type="submit" :disabled="loading">查询诊断</button>
      </form>
      <ul class="rows" aria-label="近期任务">
        <li v-for="task in items" :key="task.id">
          <div>
            {{ label(task.status) }}<small>任务 {{ task.id }}</small
            ><small>用户 {{ task.ownerId }} · {{ time(task.createdAt) }}</small>
          </div>
          <button class="secondary" :disabled="loading" @click="load(page, task.id)">
            查看诊断
          </button>
        </li>
      </ul>
      <nav aria-label="诊断分页">
        <button class="secondary" :disabled="loading || page === 0" @click="load(page - 1)">
          上一页任务</button
        ><button class="secondary" :disabled="loading || !more" @click="load(page + 1)">
          下一页任务
        </button>
      </nav>
    </template>
    <article v-if="selected" class="messages">
      <h2 id="diagnostic-result" tabindex="-1">
        {{ label(selected.task.status) }} · {{ label(selected.task.executionState) }}
      </h2>
      <p>
        任务 {{ selected.task.id
        }}<small>用户 {{ selected.task.ownerId }} · 会话 {{ selected.task.conversationId }}</small
        ><small>模型 {{ selected.task.modelId }}</small
        ><small
          >创建 {{ time(selected.task.createdAt) }} · 状态更新
          {{ time(selected.task.updatedAt) }}</small
        >
      </p>
      <p v-if="selected.task.errorCode">
        {{ errorMessage(new ApiError(400, selected.task.errorCode)) }}
        <small>{{ selected.task.errorCode }}</small>
      </p>
      <p v-if="selected.task.status === 'CANCELLED'">
        业务已取消。供应商执行和处理容器清理分别以下方记录为准。
      </p>
      <h2>上下文阶段</h2>
      <p>
        {{ label(selected.task.contextState) }} · {{ time(selected.task.contextAt)
        }}<small
          >摘要：{{ label(selected.task.summaryState) }} ·
          {{ time(selected.task.summaryAt) }}</small
        >
      </p>
      <p
        v-if="
          ['CANCELLED', 'INTERRUPTED'].includes(selected.task.status) &&
          selected.task.summaryState === 'RUNNING'
        "
      >
        任务在摘要阶段结束，摘要执行结果未确认。
      </p>
      <h2>模型调用</h2>
      <p v-if="!selected.calls.length">没有已受理调用。提交前校验失败时不会创建调用记录。</p>
      <ul class="rows">
        <li v-for="call in selected.calls" :key="call.id">
          <div>
            <p>
              {{ label(call.purpose) }} · {{ label(call.source) }}<small>调用 {{ call.id }}</small>
            </p>
            <p>
              供应商执行：{{
                call.providerState === 'UNKNOWN'
                  ? '未知，不能确认已停止'
                  : call.providerState === 'COMPLETED'
                    ? '已观察到响应完成'
                    : '未发送'
              }}<small v-if="!['PROVIDER', 'NOT_SENT'].includes(call.source)"
                >供应商用量待核实；内部估算不等于供应商账单。</small
              >
            </p>
            <p>
              内部已计 {{ call.charged }} {{ call.currency }} ·
              {{ call.status === 'OPEN' ? `预占 ${call.reservation}` : '预占已释放'
              }}<small
                >供应商输入 {{ call.inputTokens ?? '未回报' }} · 输出
                {{ call.outputTokens ?? '未回报' }}</small
              >
            </p>
            <p v-if="call.errorCode">
              {{ errorMessage(new ApiError(400, call.errorCode))
              }}<small>{{ call.errorCode }}</small>
            </p>
            <small>开始 {{ time(call.startedAt) }} · 发送 {{ time(call.sentAt) }}</small
            ><small>用量更新 {{ time(call.usageAt) }} · 本地收尾 {{ time(call.endedAt) }}</small>
          </div>
        </li>
      </ul>
      <h2>资料处理与清理</h2>
      <p v-if="!selected.parses.length">没有资料处理记录。</p>
      <ul class="rows">
        <li v-for="item in selected.parses" :key="item.id">
          <div>
            <p>
              {{ label(item.status) }} ·
              {{
                item.cleanup === 'CONFIRMED'
                  ? '处理容器已确认清理'
                  : item.status === 'RUNNING' && selected.task.status === 'RUNNING'
                    ? '正在处理，尚未确认清理'
                    : '处理停止未知，清理待重试'
              }}
            </p>
            <small>处理作业 {{ item.sandboxJobId }} · 资料 {{ item.materialId }}</small
            ><small v-if="admin">容器关联标签 iwork.job={{ item.sandboxJobId }}</small>
            <p v-if="item.errorCode">
              {{ errorMessage(new ApiError(400, item.errorCode))
              }}<small>{{ item.errorCode }}</small>
            </p>
            <small>创建 {{ time(item.createdAt) }} · 最近观察 {{ time(item.updatedAt) }}</small
            ><small
              >清理尝试 {{ item.cleanupAttempts }} 次 · 最近尝试
              {{ time(item.cleanupAttemptedAt) }} · 首次确认
              {{ time(item.cleanupConfirmedAt) }}</small
            >
          </div>
        </li>
      </ul>
      <h2>成果发布</h2>
      <p v-if="!selected.versions.length">本次没有发布正式版本，已有成果不受影响。</p>
      <ul class="rows">
        <li v-for="version in selected.versions" :key="version.versionId">
          <div>
            版本 {{ version.versionNumber
            }}<small>成果 {{ version.documentId }} · 版本 {{ version.versionId }}</small
            ><small>{{ time(version.createdAt) }}</small>
          </div>
        </li>
      </ul>
    </article>
    <button class="secondary" :disabled="loading" @click="load()">刷新诊断</button>
  </section>
</template>
