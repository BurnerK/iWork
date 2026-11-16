<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { request } from './api'
type Model = { id: string; name: string; inputPrice: string; outputPrice: string; baseUrl: string; modelName: string; credentialRef: string; enabled: boolean; contextWindow: number | null }
type Budget = { currency: string; limit: string; spent: string; reserved: string; available: string }
const props = defineProps<{ admin: boolean }>()
const emit = defineEmits<{ failure: [cause: unknown] }>()
const models = ref<Model[]>([]), budget = ref<Budget | null>(null), loading = ref(false)
const users = ref<{ id: string; username: string }[]>([]), userPage = ref(0), hasMore = ref(false)
const selectedUser = ref(''), grants = ref<string[]>([]), limit = ref('0.000000'), userBudget = ref<Budget | null>(null)
const currency = ref('CNY'), currencyLocked = ref(false), busy = ref(false), notice = ref(''), editing = ref('')
const emptyModel = () => ({ name: '', baseUrl: '', modelName: '', credentialRef: '', inputPrice: '0.000000', outputPrice: '0.000000', enabled: true, contextWindow: null as number | null })
const draft = ref(emptyModel())
async function loadUsers(nextPage = userPage.value) {
  const result = await request<{ items: typeof users.value; hasMore: boolean }>(`/api/admin/users?page=${nextPage}`)
  users.value = result.items; hasMore.value = result.hasMore; userPage.value = nextPage
}
async function act(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; notice.value = ''
  try { await action() } catch (cause) { emit('failure', cause) } finally { busy.value = false }
}
function saveModel() { return act(async () => {
  await request(editing.value ? `/api/admin/models/${editing.value}` : '/api/admin/models', editing.value ? 'PUT' : 'POST', draft.value)
  draft.value = emptyModel(); editing.value = ''; await load(); notice.value = '模型已保存。'
}) }
function edit(model: Model) {
  editing.value = model.id
  const { id: _id, ...configuration } = model; draft.value = configuration
  document.getElementById('model-name')?.focus()
}
function loadUser() { return act(async () => {
  userBudget.value = null; grants.value = []
  const [access, current] = await Promise.all([request<string[]>(`/api/admin/users/${selectedUser.value}/model-access`), request<Budget>(`/api/admin/users/${selectedUser.value}/budget`)])
  grants.value = access; userBudget.value = current; limit.value = current.limit
}) }
function saveAccess() { return act(async () => {
  await request(`/api/admin/users/${selectedUser.value}/model-access`, 'PUT', { modelIds: grants.value })
  notice.value = '模型授权已保存。'
}) }
function saveBudget() { return act(async () => {
  userBudget.value = await request<Budget>(`/api/admin/users/${selectedUser.value}/budget`, 'PUT', { limit: limit.value })
  currencyLocked.value = true; notice.value = '个人额度已保存。'
}) }
function saveCurrency() { return act(async () => {
  await request('/api/admin/model-settings', 'PUT', { currency: currency.value }); notice.value = '币种已保存。'
}) }
function paginate(delta: number) { return act(async () => { await loadUsers(userPage.value + delta); selectedUser.value = ''; changeUser() }) }
function changeUser() { userBudget.value = null; grants.value = []; notice.value = '' }
async function load() {
  loading.value = true
  try {
    if (props.admin) {
      const [items, settings] = await Promise.all([request<Model[]>('/api/admin/models'), request<{ currency: string; locked: boolean }>('/api/admin/model-settings'), loadUsers()])
      models.value = items; currency.value = settings.currency; currencyLocked.value = settings.locked
    } else [models.value, budget.value] = await Promise.all([request<Model[]>('/api/models'), request<Budget>('/api/budget')])
  }
  catch (cause) { emit('failure', cause) }
  finally { loading.value = false }
}
onMounted(load)
</script>
<template>
  <section :aria-busy="loading">
    <h1 tabindex="-1">{{ admin ? '模型管理' : '模型与额度' }}</h1>
    <p v-if="notice" role="status">{{ notice }}</p>
    <template v-if="admin">
      <form method="post" action="/api/admin/model-settings" aria-label="实例币种" @submit.prevent="saveCurrency">
        <label for="currency">实例币种（必填，ISO 代码）</label><input id="currency" v-model="currency" name="currency" required pattern="[A-Z]{3}" maxlength="3" :disabled="busy || currencyLocked">
        <p class="hint">建立模型或预算后，币种锁定。所有价格为每百万 Token 的金额，最多 6 位小数。</p>
        <button type="submit" :disabled="busy || currencyLocked">保存币种</button>
      </form>
      <div class="workspace">
        <form method="post" action="/api/admin/models" aria-label="模型配置" @submit.prevent="saveModel">
          <h2>{{ editing ? '编辑模型' : '添加模型' }}</h2>
          <label for="model-name">显示名称（必填）</label><input id="model-name" v-model="draft.name" name="name" required maxlength="100" :disabled="busy">
          <label for="model-url">服务地址（必填，HTTPS）</label><input id="model-url" v-model="draft.baseUrl" name="baseUrl" type="url" required maxlength="1000" autocorrect="off" autocapitalize="none" :spellcheck="false" :disabled="busy">
          <label for="model-upstream">上游模型标识（必填）</label><input id="model-upstream" v-model="draft.modelName" name="modelName" required maxlength="200" autocorrect="off" autocapitalize="none" :spellcheck="false" :disabled="busy">
          <label for="model-window">获准上下文窗口（必填，Token）</label><input id="model-window" v-model.number="draft.contextWindow" name="contextWindow" type="number" min="8192" max="131072" step="1" required aria-describedby="window-help" :disabled="busy">
          <p id="window-help" class="hint">按供应商确认的模型窗口填写；首版最多使用 131072 Token。未配置时不能执行任务。</p>
          <label for="model-credential">凭据引用（必填）</label><input id="model-credential" v-model="draft.credentialRef" name="credentialRef" required pattern="IWORK_MODEL_KEY_[A-Z0-9_]{1,80}" maxlength="100" aria-describedby="credential-help" autocorrect="off" autocapitalize="none" :spellcheck="false" :disabled="busy">
          <p id="credential-help" class="hint">填写服务端配置的 IWORK_MODEL_KEY_ 开头的环境变量名。密钥由部署者配置，请勿粘贴密钥。</p>
          <label for="input-price">输入价格（必填，{{ currency }}/百万 Token）</label><input id="input-price" v-model="draft.inputPrice" name="inputPrice" inputmode="decimal" required pattern="[0-9]+(\.[0-9]{1,6})?" :disabled="busy">
          <label for="output-price">输出价格（必填，{{ currency }}/百万 Token）</label><input id="output-price" v-model="draft.outputPrice" name="outputPrice" inputmode="decimal" required pattern="[0-9]+(\.[0-9]{1,6})?" :disabled="busy">
          <div class="check-row"><input id="model-enabled" v-model="draft.enabled" name="enabled" type="checkbox" :disabled="busy"><label for="model-enabled">启用模型</label></div>
          <button type="submit" :disabled="busy">保存模型</button>
          <button v-if="editing" type="button" class="secondary" :disabled="busy" @click="editing = ''; draft = emptyModel()">取消编辑</button>
        </form>
        <section>
          <h2>用户授权与额度</h2>
          <form method="get" action="/api/admin/users" aria-label="选择用户" @submit.prevent="loadUser">
            <label for="model-user">用户（必选）</label><select id="model-user" v-model="selectedUser" name="userId" required :disabled="busy" @change="changeUser"><option value="">请选择用户</option><option v-for="target in users" :key="target.id" :value="target.id">{{ target.username }}</option></select>
            <div class="actions"><button type="button" class="secondary" :disabled="busy || userPage === 0" @click="paginate(-1)">上一页用户</button><button type="button" class="secondary" :disabled="busy || !hasMore" @click="paginate(1)">下一页用户</button></div>
            <button type="submit" :disabled="busy">查看授权与额度</button>
          </form>
          <template v-if="userBudget">
            <form method="post" :action="`/api/admin/users/${selectedUser}/model-access`" aria-label="模型授权" @submit.prevent="saveAccess">
              <fieldset :disabled="busy"><legend>允许使用的模型</legend><div v-for="model in models" :key="model.id" class="check-row"><input :id="`grant-${model.id}`" v-model="grants" name="modelIds" type="checkbox" :value="model.id"><label :for="`grant-${model.id}`">{{ model.name }}{{ model.enabled ? '' : '（已停用）' }}</label></div></fieldset>
              <button type="submit" :disabled="busy">保存模型授权</button>
            </form>
            <form method="post" :action="`/api/admin/users/${selectedUser}/budget`" aria-label="个人额度" @submit.prevent="saveBudget">
              <p>已用 {{ userBudget.spent }} · 预占 {{ userBudget.reserved }} {{ userBudget.currency }}</p>
              <label for="budget-limit">总额度（必填，{{ userBudget.currency }}）</label><input id="budget-limit" v-model="limit" name="limit" inputmode="decimal" required pattern="[0-9]+(\.[0-9]{1,6})?" :disabled="busy">
              <p class="hint">这是累计预算上限，修改不会清零已用金额。</p><button type="submit" :disabled="busy">保存个人额度</button>
            </form>
          </template>
        </section>
      </div>
    </template>
    <p v-if="budget">可用 {{ budget.available }} {{ budget.currency }} · 总额度 {{ budget.limit }} · 已用 {{ budget.spent }} · 预占 {{ budget.reserved }}</p>
    <p v-if="!admin && !loading && !models.length">暂无可用模型，请联系管理员授权。</p>
    <ul class="rows"><li v-for="model in models" :key="model.id"><div>{{ model.name }}<small>每百万 Token：输入 {{ model.inputPrice }}，输出 {{ model.outputPrice }} {{ budget?.currency || currency }}</small><small v-if="admin">{{ model.enabled ? '已启用' : '已停用' }}</small></div><button v-if="admin" type="button" class="secondary" :disabled="busy" @click="edit(model)">编辑 {{ model.name }}</button></li></ul>
    <button type="button" class="secondary" :disabled="loading" @click="load">刷新</button>
  </section>
</template>
