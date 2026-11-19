<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from 'vue'
import { ApiError, errorMessage, request } from './api'
type Agent = { id: string; name: string; instructions: string; modelId: string }
const emit = defineEmits<{ failure: [cause: unknown] }>()
const agents = ref<Agent[]>([]),
  models = ref<{ id: string; name: string }[]>([])
const id = ref(''),
  name = ref(''),
  instructions = ref(''),
  modelId = ref('')
const page = ref(0),
  more = ref(false),
  busy = ref(false),
  loading = ref(false),
  error = ref(''),
  notice = ref('')
let disposed = false
async function failure(cause: unknown) {
  if (disposed) return
  error.value = errorMessage(cause)
  if (cause instanceof ApiError && cause.status === 401) emit('failure', cause)
  await nextTick()
  document.getElementById('agent-error')?.focus()
}
async function load(target = page.value) {
  loading.value = true
  try {
    const result = await request<{ items: Agent[]; hasMore: boolean }>(`/api/agents?page=${target}`)
    if (!disposed) {
      agents.value = result.items
      more.value = result.hasMore
      page.value = target
    }
  } catch (cause) {
    await failure(cause)
  } finally {
    loading.value = false
  }
}
async function edit(agent?: Agent) {
  id.value = agent?.id || ''
  name.value = agent?.name || ''
  instructions.value = agent?.instructions || ''
  modelId.value = agent?.modelId || ''
  notice.value = ''
  error.value = ''
  await nextTick()
  document.getElementById('agent-name')?.focus()
}
async function save() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const saved = await request<Agent>(
      `/api/agents${id.value ? `/${id.value}` : ''}`,
      id.value ? 'PUT' : 'POST',
      { name: name.value, instructions: instructions.value, modelId: modelId.value }
    )
    if (disposed) return
    id.value = saved.id
    notice.value = '已保存。后续新任务使用此配置，正在执行的任务保持原配置。'
    await load(0)
  } catch (cause) {
    await failure(cause)
  } finally {
    busy.value = false
  }
}
onMounted(async () => {
  try {
    const result = await request<{ id: string; name: string }[]>('/api/models')
    if (!disposed) models.value = result
  } catch (cause) {
    await failure(cause)
  }
  await load()
})
onUnmounted(() => {
  disposed = true
})
</script>
<template>
  <section :aria-busy="loading">
    <h1 tabindex="-1">我的智能体</h1>
    <p>
      保存常用指令和模型，在会话提交任务时选择使用。资料仍由每次任务明确选择，用量计入你的个人额度。
    </p>
    <p v-if="error" id="agent-error" role="alert" tabindex="-1">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <ul class="rows" aria-label="个人智能体">
      <li v-for="agent in agents" :key="agent.id">
        <div>
          {{ agent.name
          }}<small>{{
            models.find((model) => model.id === agent.modelId)?.name || '当前模型不可用'
          }}</small>
        </div>
        <button type="button" class="secondary" :disabled="busy" @click="edit(agent)">编辑</button>
      </li>
    </ul>
    <nav aria-label="智能体分页">
      <button
        type="button"
        class="secondary"
        :disabled="loading || busy || page === 0"
        @click="load(page - 1)"
      >
        上一页</button
      ><span>第 {{ page + 1 }} 页</span
      ><button
        type="button"
        class="secondary"
        :disabled="loading || busy || !more"
        @click="load(page + 1)"
      >
        下一页
      </button>
    </nav>
    <button type="button" class="secondary" :disabled="busy" @click="edit()">新建智能体</button>
    <form
      class="surface-card"
      :action="`/api/agents${id ? `/${id}` : ''}`"
      method="post"
      aria-labelledby="agent-form-heading"
      @submit.prevent="save"
    >
      <h2 id="agent-form-heading">{{ id ? '编辑智能体' : '新建智能体' }}</h2>
      <label for="agent-name">名称（必填，最多120字符）</label
      ><input
        id="agent-name"
        v-model="name"
        name="name"
        required
        maxlength="120"
        :disabled="busy"
      />
      <label for="agent-instructions">指令（必填，最多4000字符）</label
      ><textarea
        id="agent-instructions"
        v-model="instructions"
        name="instructions"
        required
        maxlength="4000"
        rows="6"
        :disabled="busy"
      ></textarea>
      <label for="agent-model">模型（必选）</label
      ><select id="agent-model" v-model="modelId" name="modelId" required :disabled="busy">
        <option value="">请选择模型</option>
        <option
          v-if="modelId && !models.some((model) => model.id === modelId)"
          :value="modelId"
          disabled
        >
          当前模型不可用，请重新选择
        </option>
        <option v-for="model in models" :key="model.id" :value="model.id">{{ model.name }}</option>
      </select>
      <button type="submit" :disabled="busy">保存智能体</button>
    </form>
  </section>
</template>
