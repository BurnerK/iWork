<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TaskPanel from './TaskPanel.vue'
import ModelPanel from './ModelPanel.vue'
import ProjectPanel from './ProjectPanel.vue'
import ProjectMembership from './ProjectMembership.vue'
import AgentPanel from './AgentPanel.vue'
import { ApiError, errorMessage, request } from './api'

type User = { id: string; username: string; displayName: string; role: string; enabled: boolean; mustChangePassword: boolean }
type Conversation = { id: string; title: string; updatedAt: string; projectId: string | null }
type Page<T> = { items: T[]; hasMore: boolean }
const route = useRoute(), router = useRouter()
const user = ref<User | null>(null), ready = ref(false), busy = ref(false), loading = ref(false), error = ref('')
const notice = ref('')
const username = ref(''), password = ref(''), newPassword = ref(''), displayName = ref(''), showPassword = ref(false)
const conversations = ref<Conversation[]>([]), selected = ref<Conversation | null>(null), users = ref<User[]>([])
const page = ref(0), hasMore = ref(false), temporary = ref<{ username: string; password: string } | null>(null)
let loadSequence = 0
function clearIdentity() {
  ++loadSequence
  user.value = null; conversations.value = []; users.value = []; selected.value = null
  temporary.value = null; password.value = ''; newPassword.value = ''; username.value = ''; displayName.value = ''
  page.value = 0; hasMore.value = false; loading.value = false
}

async function failure(cause: unknown) {
  error.value = errorMessage(cause)
  if (cause instanceof ApiError && cause.status === 401 && route.name !== 'login') {
    clearIdentity()
    await router.replace('/login')
  }
  await nextTick()
  document.querySelector<HTMLElement>('[role="alert"]')?.focus()
}
async function loadIdentity() {
  loading.value = true; error.value = ''
  try { user.value = await request<User>('/api/auth/me') }
  catch (cause) {
    if (!(cause instanceof ApiError && cause.status === 401)) { await failure(cause); loading.value = false; return }
  }
  ready.value = true; loading.value = false
  await navigate()
}
async function navigate() {
  if (!ready.value) return
  temporary.value = null; password.value = ''; newPassword.value = ''; showPassword.value = false
  if (!user.value) { if (route.name !== 'login') await router.replace('/login'); return }
  if (user.value.mustChangePassword && route.name !== 'password') { await router.replace('/password'); return }
  if (route.name === 'login' || ['admin', 'admin-models'].includes(String(route.name)) && user.value.role !== 'ADMIN') { await router.replace('/conversations'); return }
  page.value = 0; await loadPanel()
  await nextTick(); document.querySelector<HTMLElement>('h1')?.focus()
}
async function loadPanel() {
  const sequence = ++loadSequence
  loading.value = true; error.value = ''; selected.value = null
  try {
    if (route.name === 'conversations' || route.name === 'conversation') {
      const result = await request<Page<Conversation>>(`/api/conversations?page=${page.value}`)
      if (sequence !== loadSequence) return
      conversations.value = result.items; hasMore.value = result.hasMore
      if (route.params.id) {
        const result = await request<Conversation>(`/api/conversations/${encodeURIComponent(String(route.params.id))}`)
        if (sequence === loadSequence) selected.value = result
      }
    } else if (route.name === 'admin') {
      const result = await request<Page<User>>(`/api/admin/users?page=${page.value}`)
      if (sequence !== loadSequence) return
      users.value = result.items; hasMore.value = result.hasMore
    }
  } catch (cause) { if (sequence === loadSequence) await failure(cause) }
  finally { if (sequence === loadSequence) loading.value = false }
}
async function act(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { await action() } catch (cause) { await failure(cause) } finally { busy.value = false }
}
function login() { return act(async () => {
  const authenticated = await request<User>('/api/auth/login', 'POST', new URLSearchParams({ username: username.value, password: password.value }))
  clearIdentity(); user.value = authenticated; notice.value = ''
  await router.replace(authenticated.mustChangePassword ? '/password' : '/conversations')
}) }
function changePassword() { return act(async () => {
  if ([...newPassword.value].length < 12 || new TextEncoder().encode(newPassword.value).length > 72) throw new ApiError(400, 'invalid_password')
  await request('/api/auth/password', 'POST', { currentPassword: password.value, newPassword: newPassword.value })
  clearIdentity(); notice.value = '密码已更新，请使用新密码重新登录。'
  await router.replace('/login')
}) }
function logout() { return act(async () => {
  await request('/api/auth/logout', 'POST'); clearIdentity(); notice.value = ''
  await router.replace('/login')
}) }
function createConversation() { return act(async () => {
  const created = await request<Conversation>('/api/conversations', 'POST', {})
  await router.push(`/conversations/${created.id}`)
}) }
function createUser() { return act(async () => {
  const result = await request<{ user: User; temporaryPassword: string }>('/api/admin/users', 'POST', { username: username.value, displayName: displayName.value })
  temporary.value = { username: result.user.username, password: result.temporaryPassword }; username.value = ''; displayName.value = ''
  page.value = 0; await loadPanel()
}) }
function resetPassword(target: User) { return act(async () => {
  const result = await request<{ temporaryPassword: string }>(`/api/admin/users/${target.id}/reset-password`, 'POST', {})
  temporary.value = { username: target.username, password: result.temporaryPassword }; await loadPanel()
}) }
function toggleUser(target: User) { return act(async () => {
  await request(`/api/admin/users/${target.id}/status`, 'PATCH', { enabled: !target.enabled }); await loadPanel()
}) }
async function movedConversation() {
  if (!selected.value) return
  const id = selected.value.id
  try {
    const refreshed = await request<Conversation>(`/api/conversations/${id}`)
    if (selected.value?.id === id) selected.value = refreshed
  } catch (cause) { await failure(cause) }
}
async function paginate(delta: number) { page.value += delta; await loadPanel() }
watch(() => route.fullPath, navigate)
watch(() => route.name, name => { document.title = `${({ projects: '项目空间', project: '项目空间', agents: '我的智能体', login: '登录', password: '修改密码', admin: '用户管理', models: '模型与额度', 'admin-models': '模型管理', conversation: '会话', conversations: '个人会话' } as Record<string, string>)[String(name)] || 'iWork'} · iWork` })
onMounted(loadIdentity)
</script>

<template>
  <header>
    <span class="brand">iWork</span>
    <nav v-if="user" aria-label="主导航">
      <template v-if="!user.mustChangePassword">
        <RouterLink to="/conversations">个人会话</RouterLink>
        <RouterLink v-if="user.role === 'ADMIN'" to="/admin">用户管理</RouterLink>
        <RouterLink to="/projects">项目空间</RouterLink>
        <RouterLink to="/agents">我的智能体</RouterLink>
        <RouterLink to="/models">模型与额度</RouterLink>
        <RouterLink v-if="user.role === 'ADMIN'" to="/admin-models">模型管理</RouterLink>
        <RouterLink to="/password">修改密码</RouterLink>
      </template>
      <span>{{ user.displayName }}</span>
      <button type="button" class="secondary" :disabled="busy" @click="logout">退出登录</button>
    </nav>
  </header>
  <main :aria-busy="busy || loading">
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="error" class="error" role="alert" tabindex="-1">{{ error }}</p>
    <template v-if="!ready">
      <h1 tabindex="-1">连接 iWork</h1><p>{{ loading ? '正在连接…' : '请检查服务后重试。' }}</p>
      <button type="button" :disabled="loading" @click="loadIdentity">重试连接</button>
    </template>
    <section v-else-if="route.name === 'login'" class="auth-panel">
      <p class="eyebrow">你的工作，从这里开始</p><h1 tabindex="-1">登录 iWork</h1>
      <form method="post" action="/api/auth/login" @submit.prevent="login">
        <label for="username">用户名（必填）</label>
        <input id="username" v-model="username" name="username" autocomplete="username" autocapitalize="none" autocorrect="off" :spellcheck="false" required :disabled="busy">
        <label for="password">密码（必填）</label>
        <input id="password" v-model="password" name="password" :type="showPassword ? 'text' : 'password'" autocomplete="current-password" required :disabled="busy">
        <button type="button" class="secondary" :aria-pressed="showPassword" :disabled="busy" @click="showPassword = !showPassword">{{ showPassword ? '隐藏密码' : '显示密码' }}</button>
        <button type="submit" :disabled="busy">{{ busy ? '正在登录…' : '登录' }}</button>
      </form>
      <p class="hint">账号由管理员创建。忘记密码时，请联系管理员重置。</p>
    </section>
    <section v-else-if="route.name === 'password'" class="auth-panel">
      <h1 tabindex="-1">修改密码</h1><p>{{ user?.mustChangePassword ? '首次登录，请设置新密码' : '修改后，其他设备需要重新登录。' }}</p>
      <form method="post" action="/api/auth/password" @submit.prevent="changePassword">
        <label for="current-password">当前密码（必填）</label>
        <input id="current-password" v-model="password" name="currentPassword" :type="showPassword ? 'text' : 'password'" autocomplete="current-password" required :disabled="busy">
        <label for="new-password">新密码（必填）</label><p id="password-help" class="hint">至少 12 个字符，最多 72 个 UTF-8 字节（中文通常每字占 3 字节）。</p>
        <input id="new-password" v-model="newPassword" name="newPassword" :type="showPassword ? 'text' : 'password'" autocomplete="new-password" aria-describedby="password-help" required :disabled="busy">
        <button type="button" class="secondary" :aria-pressed="showPassword" :disabled="busy" @click="showPassword = !showPassword">{{ showPassword ? '隐藏密码' : '显示密码' }}</button>
        <button type="submit" :disabled="busy">{{ busy ? '正在保存…' : '保存新密码' }}</button>
      </form>
    </section>
    <section v-else-if="route.name === 'admin'">
      <h1 tabindex="-1">用户管理</h1><p>创建普通用户，管理账号访问权限。</p>
      <form class="create-user" method="post" action="/api/admin/users" aria-label="创建用户" @submit.prevent="createUser">
        <div><label for="new-username">用户名（必填）</label><input id="new-username" v-model="username" name="username" autocomplete="off" autocorrect="off" autocapitalize="none" :spellcheck="false" required minlength="3" maxlength="64" :disabled="busy"></div>
        <div><label for="display-name">显示名称（必填）</label><input id="display-name" v-model="displayName" name="displayName" autocomplete="off" required maxlength="100" :disabled="busy"></div>
        <button type="submit" :disabled="busy">创建用户</button>
      </form>
      <div v-if="temporary" class="notice" role="status">
        <p>{{ temporary.username }} 的临时密码仅在这里显示一次，请安全交付给本人。</p><code>{{ temporary.password }}</code>
        <button type="button" class="secondary" :disabled="busy" @click="temporary = null">已记录，隐藏密码</button>
      </div>
      <p v-if="loading">正在加载用户…</p>
      <ul v-else class="rows" role="list">
        <li v-for="target in users" :key="target.id">
          <div><span>{{ target.displayName }}</span><small>{{ target.username }} · {{ target.role === 'ADMIN' ? '管理员' : '普通用户' }} · {{ target.enabled ? '已启用' : '已禁用' }}</small></div>
          <div v-if="target.role !== 'ADMIN'" class="actions">
            <button type="button" class="secondary" :disabled="busy" :aria-label="`${target.enabled ? '禁用' : '启用'} ${target.username}`" @click="toggleUser(target)">{{ target.enabled ? '禁用' : '启用' }}</button>
            <button type="button" class="secondary" :disabled="busy" :aria-label="`重置密码 ${target.username}`" @click="resetPassword(target)">重置密码</button>
          </div>
        </li>
      </ul>
    </section>
    <ProjectPanel v-else-if="user && ['projects', 'project'].includes(String(route.name))" :key="route.fullPath" :project-id="route.params.id ? String(route.params.id) : undefined" @failure="failure" />
    <AgentPanel v-else-if="user && route.name === 'agents'" :key="user.id" @failure="failure" />
    <ModelPanel v-else-if="user && ['models', 'admin-models'].includes(String(route.name))" :key="`${user.id}-${String(route.name)}`" :admin="route.name === 'admin-models'" @failure="failure" />
    <section v-else class="workspace">
      <aside>
        <div class="section-title"><h1 tabindex="-1">个人会话</h1><button type="button" :disabled="busy" @click="createConversation">新建会话</button></div>
        <p v-if="loading">正在加载会话…</p>
        <p v-else-if="!conversations.length">还没有会话。新建一段会话，开始整理你的工作。</p>
        <ul v-else class="rows" role="list"><li v-for="conversation in conversations" :key="conversation.id"><RouterLink :to="`/conversations/${conversation.id}`">{{ conversation.title }}<small>{{ new Date(conversation.updatedAt).toLocaleString() }}</small></RouterLink></li></ul>
      </aside>
      <article class="detail"><template v-if="selected"><h2>{{ selected.title }}</h2><ProjectMembership :key="`membership-${selected.id}-${selected.projectId}`" :conversation-id="selected.id" :project-id="selected.projectId" @moved="movedConversation" @failure="failure" /><TaskPanel :key="selected.id" :project-id="selected.projectId" :initial-version-id="typeof route.query.version === 'string' ? route.query.version : undefined" :conversation-id="selected.id" :owner-id="user?.id || ''" @failure="failure" /></template><template v-else><h2>准备好新的开始</h2><p>从左侧打开会话，或新建一段会话。</p></template></article>
    </section>
    <nav v-if="ready && ['admin', 'conversations', 'conversation'].includes(String(route.name))" class="pagination" aria-label="分页">
      <button type="button" class="secondary" :disabled="page === 0 || loading || busy" @click="paginate(-1)">上一页</button><span>第 {{ page + 1 }} 页</span><button type="button" class="secondary" :disabled="!hasMore || loading || busy" @click="paginate(1)">下一页</button>
      <button v-if="error" type="button" :disabled="loading || busy" @click="loadPanel">重试加载</button>
    </nav>
  </main>
</template>
