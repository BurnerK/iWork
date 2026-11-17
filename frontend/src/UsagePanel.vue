<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { errorMessage, request } from './api'
const props = defineProps<{ taskId: string; running: boolean }>()
type Call = {
  id: string
  purpose: string
  status: string
  source: string
  inputTokens: number | null
  outputTokens: number | null
  inputEstimate: number
  observedOutputBytes: number
  charged: string
  reservation: string
  currency: string
  startedAt: string
  providerResponseId: string | null
}
const calls = ref<Call[]>([]),
  loading = ref(false),
  error = ref('')
const sources: Record<string, string> = {
  PENDING: '核算中',
  PROVIDER: '供应商回报',
  PARTIAL_ESTIMATE: '累计回报与内容估算',
  NOT_SENT: '未发送',
  OBSERVED_ESTIMATE: '按已观察内容估算',
  LIMIT_ESTIMATE: '按限额保守估算',
}
const purposes: Record<string, string> = {
  MAIN: '主推理',
  SUMMARY: '上下文摘要',
  OVERFLOW: '溢出恢复',
}
let timer: ReturnType<typeof setTimeout> | undefined,
  disposed = false
async function load() {
  if (loading.value || disposed) return
  loading.value = true
  error.value = ''
  try {
    const result = await request<Call[]>(`/api/tasks/${props.taskId}/calls`)
    if (!disposed) calls.value = result
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
watch(() => props.running, load)
onMounted(poll)
onUnmounted(() => {
  disposed = true
  clearTimeout(timer)
})
</script>
<template>
  <section aria-label="任务用量">
    <h2>逐调用用量</h2>
    <p class="hint">内部额度核算不等于供应商账单。取消本地任务不代表供应商已经停止或结清。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="!calls.length && !loading">暂时没有已受理的模型调用。</p>
    <ul class="rows">
      <li v-for="call in calls" :key="call.id">
        <div>
          <p>
            {{ purposes[call.purpose] || call.purpose }} · {{ sources[call.source] || call.source }}
          </p>
          <p>
            内部已计：{{ call.charged }} {{ call.currency
            }}<small>{{
              call.status === 'OPEN'
                ? `本次预占：${call.reservation} ${call.currency}`
                : '本次预占已释放'
            }}</small>
          </p>
          <p>
            供应商输入：{{ call.inputTokens ?? '未回报' }} · 输出：{{
              call.outputTokens ?? '未回报'
            }}
          </p>
          <p v-if="!['PROVIDER', 'NOT_SENT'].includes(call.source)" class="hint">
            供应商用量待核实。输入估算 {{ call.inputEstimate }} tokens，已观察输出
            {{ call.observedOutputBytes }} 字节。
          </p>
          <small>{{ new Date(call.startedAt).toLocaleString() }} · 调用 {{ call.id }}</small
          ><small v-if="call.providerResponseId">供应商标识：{{ call.providerResponseId }}</small>
        </div>
      </li>
    </ul>
    <button type="button" class="secondary" :disabled="loading" @click="load">刷新用量</button>
  </section>
</template>
