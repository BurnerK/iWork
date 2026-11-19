<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TaskPanel from './TaskPanel.vue'
import ModelPanel from './ModelPanel.vue'
import DiagnosticsPanel from './DiagnosticsPanel.vue'
import ProjectPanel from './ProjectPanel.vue'
import ProjectMembership from './ProjectMembership.vue'
import AgentPanel from './AgentPanel.vue'
import { ApiError, errorMessage, request } from './api'

type User = {
  id: string
  username: string
  displayName: string
  role: string
  enabled: boolean
  mustChangePassword: boolean
}
type Conversation = { id: string; title: string; updatedAt: string; projectId: string | null }
type Page<T> = { items: T[]; hasMore: boolean }
const route = useRoute(),
  router = useRouter()
const user = ref<User | null>(null),
  ready = ref(false),
  busy = ref(false),
  loading = ref(false),
  error = ref('')
const notice = ref('')
const username = ref(''),
  password = ref(''),
  newPassword = ref(''),
  displayName = ref(''),
  showPassword = ref(false)
const projects = ref<{ id: string; name: string }[]>([])
const conversations = ref<Conversation[]>([]),
  selected = ref<Conversation | null>(null),
  users = ref<User[]>([])
const page = ref(0),
  hasMore = ref(false),
  temporary = ref<{ username: string; password: string } | null>(null)
const workspace = computed(
  () => !!user.value && !user.value.mustChangePassword && route.name !== 'password'
)
const conversationRoute = computed(
  () => route.name === 'conversations' || route.name === 'conversation'
)
const sidebarCollapsed = ref(false)
const mobileNavigation = ref<HTMLDialogElement>()
const navigationToggle = ref<HTMLButtonElement>()
function closeNavigation() {
  if (mobileNavigation.value?.open) mobileNavigation.value.close()
}
const navigation = computed(() => [
  { to: '/conversations', name: '个人会话', icon: 'house' },
  { to: '/projects', name: '项目空间', icon: 'folder' },
  { to: '/agents', name: '我的智能体', icon: 'bot' },
  { to: '/models', name: '模型与额度', icon: 'chart-no-axes-combined' },
  ...(user.value?.role === 'ADMIN'
    ? [
        { to: '/admin', name: '用户管理', icon: 'ellipsis' },
        { to: '/admin-models', name: '模型管理', icon: 'chart-no-axes-combined' },
        { to: '/admin-diagnostics', name: '运行诊断', icon: 'file-text' },
      ]
    : []),
])
let loadSequence = 0
function clearIdentity() {
  ++loadSequence
  user.value = null
  conversations.value = []
  projects.value = []
  users.value = []
  selected.value = null
  temporary.value = null
  password.value = ''
  newPassword.value = ''
  username.value = ''
  displayName.value = ''
  page.value = 0
  hasMore.value = false
  loading.value = false
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
  loading.value = true
  error.value = ''
  try {
    user.value = await request<User>('/api/auth/me')
  } catch (cause) {
    if (!(cause instanceof ApiError && cause.status === 401)) {
      await failure(cause)
      loading.value = false
      return
    }
  }
  ready.value = true
  loading.value = false
  await navigate()
}
async function navigate() {
  if (!ready.value) return
  closeNavigation()
  temporary.value = null
  password.value = ''
  newPassword.value = ''
  showPassword.value = false
  if (!user.value) {
    if (route.name !== 'login') await router.replace('/login')
    return
  }
  if (user.value.mustChangePassword && route.name !== 'password') {
    await router.replace('/password')
    return
  }
  if (
    route.name === 'login' ||
    (['admin', 'admin-models', 'admin-diagnostics'].includes(String(route.name)) &&
      user.value.role !== 'ADMIN')
  ) {
    await router.replace('/conversations')
    return
  }
  page.value = 0
  if (conversationRoute.value) void loadNavigationProjects()
  await loadPanel()
  await nextTick()
  document.querySelector<HTMLElement>('h1')?.focus()
}
async function loadNavigationProjects() {
  const ownerId = user.value?.id
  try {
    const result = await request<Page<{ id: string; name: string }>>('/api/projects?page=0')
    if (ownerId && user.value?.id === ownerId) projects.value = result.items ?? []
  } catch {
    // The project page provides its own error and retry; the sidebar link stays available.
  }
}
async function loadPanel() {
  const sequence = ++loadSequence
  loading.value = true
  error.value = ''
  if (selected.value?.id !== route.params.id) selected.value = null
  try {
    if (route.name === 'conversations' || route.name === 'conversation') {
      const result = await request<Page<Conversation>>(`/api/conversations?page=${page.value}`)
      if (sequence !== loadSequence) return
      conversations.value = result.items
      hasMore.value = result.hasMore
      if (route.params.id) {
        const result = await request<Conversation>(
          `/api/conversations/${encodeURIComponent(String(route.params.id))}`
        )
        if (sequence === loadSequence) selected.value = result
      }
    } else if (route.name === 'admin') {
      const result = await request<Page<User>>(`/api/admin/users?page=${page.value}`)
      if (sequence !== loadSequence) return
      users.value = result.items
      hasMore.value = result.hasMore
    }
  } catch (cause) {
    if (sequence === loadSequence) await failure(cause)
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}
async function act(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await action()
  } catch (cause) {
    await failure(cause)
  } finally {
    busy.value = false
  }
}
function login() {
  return act(async () => {
    const authenticated = await request<User>(
      '/api/auth/login',
      'POST',
      new URLSearchParams({ username: username.value, password: password.value })
    )
    clearIdentity()
    user.value = authenticated
    notice.value = ''
    await router.replace(authenticated.mustChangePassword ? '/password' : '/conversations')
  })
}
function changePassword() {
  return act(async () => {
    if (
      [...newPassword.value].length < 12 ||
      new TextEncoder().encode(newPassword.value).length > 72
    )
      throw new ApiError(400, 'invalid_password')
    await request('/api/auth/password', 'POST', {
      currentPassword: password.value,
      newPassword: newPassword.value,
    })
    clearIdentity()
    notice.value = '密码已更新，请使用新密码重新登录。'
    await router.replace('/login')
  })
}
function logout() {
  return act(async () => {
    await request('/api/auth/logout', 'POST')
    clearIdentity()
    notice.value = ''
    await router.replace('/login')
  })
}
function openSentConversation(id: string) {
  if (user.value && route.name === 'conversations') void router.replace(`/conversations/${id}`)
}
function createUser() {
  return act(async () => {
    const result = await request<{ user: User; temporaryPassword: string }>(
      '/api/admin/users',
      'POST',
      { username: username.value, displayName: displayName.value }
    )
    temporary.value = { username: result.user.username, password: result.temporaryPassword }
    username.value = ''
    displayName.value = ''
    page.value = 0
    await loadPanel()
  })
}
function resetPassword(target: User) {
  return act(async () => {
    const result = await request<{ temporaryPassword: string }>(
      `/api/admin/users/${target.id}/reset-password`,
      'POST',
      {}
    )
    temporary.value = { username: target.username, password: result.temporaryPassword }
    await loadPanel()
  })
}
function toggleUser(target: User) {
  return act(async () => {
    await request(`/api/admin/users/${target.id}/status`, 'PATCH', { enabled: !target.enabled })
    await loadPanel()
  })
}
async function movedConversation() {
  if (!selected.value) return
  const id = selected.value.id
  try {
    const refreshed = await request<Conversation>(`/api/conversations/${id}`)
    if (selected.value?.id === id) selected.value = refreshed
  } catch (cause) {
    await failure(cause)
  }
}
async function paginate(delta: number) {
  page.value += delta
  await loadPanel()
}
watch(() => route.fullPath, navigate)
watch(
  () => route.name,
  (name) => {
    document.title = `${({ 'admin-diagnostics': '运行诊断', projects: '项目空间', project: '项目空间', agents: '我的智能体', login: '登录', password: '修改密码', admin: '用户管理', models: '模型与额度', 'admin-models': '模型管理', conversation: '会话', conversations: '个人会话' } as Record<string, string>)[String(name)] || 'iWork'} · iWork`
  }
)
onMounted(loadIdentity)
</script>

<template>
  <div
    :class="{ 'app-shell': workspace, 'sidebar-collapsed': sidebarCollapsed || !conversationRoute }"
  >
    <header v-if="workspace" class="workspace-topbar">
      <div class="topbar-controls">
        <img
          v-if="!conversationRoute"
          class="workspace-logo desktop-brand"
          :src="'/assets/iwork-icon.svg'"
          alt="iWork"
          width="28"
          height="28"
        />
        <button
          v-if="conversationRoute"
          type="button"
          class="icon-control workspace-toggle t-icon-swap"
          aria-label="切换导航"
          aria-controls="conversation-sidebar"
          :aria-expanded="!sidebarCollapsed"
          @click="sidebarCollapsed = !sidebarCollapsed"
        >
          <img
            class="workspace-logo t-icon"
            data-icon="a"
            :src="'/assets/iwork-icon.svg'"
            alt=""
            width="28"
            height="28"
          />
          <span
            class="navigation-icon workspace-toggle-icon t-icon"
            data-icon="b"
            aria-hidden="true"
          ></span>
        </button>
        <button
          ref="navigationToggle"
          type="button"
          class="icon-control mobile-toggle"
          aria-label="打开导航"
          aria-haspopup="dialog"
          @click="mobileNavigation?.showModal()"
        >
          <span class="menu-icon" aria-hidden="true"></span>
        </button>
      </div>
      <h1 :class="{ 'conversation-title': conversationRoute }" tabindex="-1">
        {{
          conversationRoute
            ? selected?.title || '新会话'
            : navigation.find(
                (item) =>
                  item.to === '/' + (route.name === 'project' ? 'projects' : String(route.name))
              )?.name
        }}
      </h1>
    </header>
    <nav v-if="workspace" class="workspace-rail" aria-label="主导航">
      <RouterLink
        v-for="item in navigation"
        :key="item.to"
        :to="item.to"
        :aria-label="item.name"
        :class="{
          'workspace-link-current':
            (item.to === '/conversations' && conversationRoute) ||
            (item.to === '/projects' && route.name === 'project'),
        }"
      >
        <span
          class="navigation-icon"
          :style="{ '--icon': `url('/assets/icons/${item.icon}.svg')` }"
          aria-hidden="true"
        ></span>
      </RouterLink>
      <details class="workspace-account">
        <summary :aria-label="`账户设置 · ${user?.displayName}`">
          {{ user?.displayName.slice(0, 1) }}
        </summary>
        <div class="account-menu">
          <span>{{ user?.displayName }}</span>
          <RouterLink to="/password">修改密码</RouterLink>
          <button type="button" class="secondary" :disabled="busy" @click="logout">退出登录</button>
        </div>
      </details>
    </nav>
    <dialog
      v-if="workspace"
      ref="mobileNavigation"
      class="mobile-navigation"
      aria-label="工作台导航"
      @close="navigationToggle?.focus()"
    >
      <form method="dialog"><button type="submit" class="secondary">关闭导航</button></form>
      <nav aria-label="主导航">
        <RouterLink
          v-for="item in navigation"
          :key="item.to"
          :to="item.to"
          @click="closeNavigation"
          >{{ item.name }}</RouterLink
        >
      </nav>
      <nav
        v-if="conversationRoute"
        class="mobile-conversations"
        aria-labelledby="mobile-conversations-heading"
      >
        <h2 id="mobile-conversations-heading">最近会话</h2>
        <RouterLink
          v-for="item in conversations"
          :key="item.id"
          :to="`/conversations/${item.id}`"
          :title="item.title"
          @click="closeNavigation"
          >{{ item.title }}</RouterLink
        >
        <button
          type="button"
          class="secondary"
          :disabled="page === 0 || loading"
          @click="paginate(-1)"
        >
          上一页
        </button>
        <button
          type="button"
          class="secondary"
          :disabled="!hasMore || loading"
          @click="paginate(1)"
        >
          下一页
        </button>
      </nav>
      <div class="mobile-account">
        <span>{{ user?.displayName }}</span>
        <RouterLink to="/password" @click="closeNavigation">修改密码</RouterLink>
        <button type="button" class="secondary" :disabled="busy" @click="logout">退出登录</button>
      </div>
    </dialog>
    <header v-else>
      <span class="brand">iWork</span>
      <nav v-if="user" aria-label="主导航">
        <template v-if="!user.mustChangePassword">
          <RouterLink to="/conversations">个人会话</RouterLink>
          <RouterLink v-if="user.role === 'ADMIN'" to="/admin">用户管理</RouterLink>
          <RouterLink to="/projects">项目空间</RouterLink>
          <RouterLink to="/agents">我的智能体</RouterLink>
          <RouterLink to="/models">模型与额度</RouterLink>
          <RouterLink v-if="user.role === 'ADMIN'" to="/admin-models">模型管理</RouterLink>
          <RouterLink v-if="user.role === 'ADMIN'" to="/admin-diagnostics">运行诊断</RouterLink>
          <RouterLink to="/password">修改密码</RouterLink>
        </template>
        <span>{{ user.displayName }}</span>
        <button type="button" class="secondary" :disabled="busy" @click="logout">退出登录</button>
      </nav>
    </header>
    <main
      :class="{ 'conversation-main': workspace && conversationRoute }"
      :aria-busy="busy || loading"
    >
      <p v-if="notice" role="status">{{ notice }}</p>
      <p v-if="error" class="error" role="alert" tabindex="-1">{{ error }}</p>
      <template v-if="!ready">
        <h1 tabindex="-1">连接 iWork</h1>
        <p>{{ loading ? '正在连接…' : '请检查服务后重试。' }}</p>
        <button type="button" :disabled="loading" @click="loadIdentity">重试连接</button>
      </template>
      <section v-else-if="route.name === 'login'" class="auth-panel">
        <p class="eyebrow">你的工作，从这里开始</p>
        <h1 tabindex="-1">登录 iWork</h1>
        <form method="post" action="/api/auth/login" @submit.prevent="login">
          <label for="username">用户名（必填）</label>
          <input
            id="username"
            v-model="username"
            name="username"
            autocomplete="username"
            autocapitalize="none"
            autocorrect="off"
            :spellcheck="false"
            required
            :disabled="busy"
          />
          <label for="password">密码（必填）</label>
          <input
            id="password"
            v-model="password"
            name="password"
            :type="showPassword ? 'text' : 'password'"
            autocomplete="current-password"
            required
            :disabled="busy"
          />
          <button
            type="button"
            class="secondary"
            :aria-pressed="showPassword"
            :disabled="busy"
            @click="showPassword = !showPassword"
          >
            {{ showPassword ? '隐藏密码' : '显示密码' }}
          </button>
          <button type="submit" :disabled="busy">{{ busy ? '正在登录…' : '登录' }}</button>
        </form>
        <p class="hint">账号由管理员创建。忘记密码时，请联系管理员重置。</p>
      </section>
      <section v-else-if="route.name === 'password'" class="auth-panel">
        <h1 tabindex="-1">修改密码</h1>
        <p>
          {{
            user?.mustChangePassword ? '首次登录，请设置新密码' : '修改后，其他设备需要重新登录。'
          }}
        </p>
        <form method="post" action="/api/auth/password" @submit.prevent="changePassword">
          <label for="current-password">当前密码（必填）</label>
          <input
            id="current-password"
            v-model="password"
            name="currentPassword"
            :type="showPassword ? 'text' : 'password'"
            autocomplete="current-password"
            required
            :disabled="busy"
          />
          <label for="new-password">新密码（必填）</label>
          <p id="password-help" class="hint">
            至少 12 个字符，最多 72 个 UTF-8 字节（中文通常每字占 3 字节）。
          </p>
          <input
            id="new-password"
            v-model="newPassword"
            name="newPassword"
            :type="showPassword ? 'text' : 'password'"
            autocomplete="new-password"
            aria-describedby="password-help"
            required
            :disabled="busy"
          />
          <button
            type="button"
            class="secondary"
            :aria-pressed="showPassword"
            :disabled="busy"
            @click="showPassword = !showPassword"
          >
            {{ showPassword ? '隐藏密码' : '显示密码' }}
          </button>
          <button type="submit" :disabled="busy">{{ busy ? '正在保存…' : '保存新密码' }}</button>
        </form>
      </section>
      <section v-else-if="route.name === 'admin'">
        <h1 tabindex="-1">用户管理</h1>
        <p>创建普通用户，管理账号访问权限。</p>
        <form
          class="create-user"
          method="post"
          action="/api/admin/users"
          aria-label="创建用户"
          @submit.prevent="createUser"
        >
          <div>
            <label for="new-username">用户名（必填）</label
            ><input
              id="new-username"
              v-model="username"
              name="username"
              autocomplete="off"
              autocorrect="off"
              autocapitalize="none"
              :spellcheck="false"
              required
              minlength="3"
              maxlength="64"
              :disabled="busy"
            />
          </div>
          <div>
            <label for="display-name">显示名称（必填）</label
            ><input
              id="display-name"
              v-model="displayName"
              name="displayName"
              autocomplete="off"
              required
              maxlength="100"
              :disabled="busy"
            />
          </div>
          <button type="submit" :disabled="busy">创建用户</button>
        </form>
        <div v-if="temporary" class="notice" role="status">
          <p>{{ temporary.username }} 的临时密码仅在这里显示一次，请安全交付给本人。</p>
          <code>{{ temporary.password }}</code>
          <button type="button" class="secondary" :disabled="busy" @click="temporary = null">
            已记录，隐藏密码
          </button>
        </div>
        <p v-if="loading">正在加载用户…</p>
        <ul v-else class="rows" role="list">
          <li v-for="target in users" :key="target.id">
            <div>
              <span>{{ target.displayName }}</span><small
                >{{ target.username }} · {{ target.role === 'ADMIN' ? '管理员' : '普通用户' }} ·
                {{ target.enabled ? '已启用' : '已禁用' }}</small
              >
            </div>
            <div v-if="target.role !== 'ADMIN'" class="actions">
              <button
                type="button"
                class="secondary"
                :disabled="busy"
                :aria-label="`${target.enabled ? '禁用' : '启用'} ${target.username}`"
                @click="toggleUser(target)"
              >
                {{ target.enabled ? '禁用' : '启用' }}
              </button>
              <button
                type="button"
                class="secondary"
                :disabled="busy"
                :aria-label="`重置密码 ${target.username}`"
                @click="resetPassword(target)"
              >
                重置密码
              </button>
            </div>
          </li>
        </ul>
      </section>
      <DiagnosticsPanel
        v-else-if="user?.role === 'ADMIN' && route.name === 'admin-diagnostics'"
        admin
      />
      <ProjectPanel
        v-else-if="user && ['projects', 'project'].includes(String(route.name))"
        :key="route.fullPath"
        :project-id="route.params.id ? String(route.params.id) : undefined"
        @failure="failure"
      />
      <AgentPanel v-else-if="user && route.name === 'agents'" :key="user.id" @failure="failure" />
      <ModelPanel
        v-else-if="user && ['models', 'admin-models'].includes(String(route.name))"
        :key="`${user.id}-${String(route.name)}`"
        :admin="route.name === 'admin-models'"
        @failure="failure"
      />
      <section v-else class="workspace">
        <aside
          id="conversation-sidebar"
          class="conversation-sidebar"
          aria-label="会话列表"
          :inert="sidebarCollapsed"
        >
          <div class="conversation-sidebar-content">
            <RouterLink class="new-conversation" to="/conversations">
              <span class="navigation-icon" aria-hidden="true"></span>新建会话
            </RouterLink>
            <h2 class="sidebar-heading">最近会话</h2>
            <p v-if="loading && !conversations.length" class="sidebar-state" role="status">
              正在加载会话…
            </p>
            <p v-else-if="!conversations.length" class="sidebar-state">还没有会话</p>
            <ul v-else class="conversation-list">
              <li v-for="conversation in conversations" :key="conversation.id">
                <RouterLink :to="`/conversations/${conversation.id}`" :title="conversation.title">
                  {{ conversation.title }}
                </RouterLink>
              </li>
            </ul>
            <nav v-if="page || hasMore" aria-label="会话分页">
              <button
                type="button"
                class="secondary"
                :disabled="page === 0 || loading || busy"
                @click="paginate(-1)"
              >
                上一页
              </button>
              <button
                type="button"
                class="secondary"
                :disabled="!hasMore || loading || busy"
                @click="paginate(1)"
              >
                下一页
              </button>
            </nav>
            <h2 class="sidebar-heading"><RouterLink to="/projects">项目空间</RouterLink></h2>
            <ul v-if="projects.length" class="conversation-list">
              <li v-for="project in projects" :key="project.id">
                <RouterLink :to="`/projects/${project.id}`" :title="project.name">
                  {{ project.name }}
                </RouterLink>
              </li>
            </ul>
          </div>
        </aside>
        <article class="conversation-body">
          <details v-if="selected" class="conversation-membership">
            <summary>项目归属</summary>
            <ProjectMembership
              :key="`membership-${selected.id}-${selected.projectId}`"
              :conversation-id="selected.id"
              :project-id="selected.projectId"
              @moved="movedConversation"
              @failure="failure"
            />
          </details>
          <TaskPanel
            v-if="selected || route.name === 'conversations'"
            :key="selected?.id || 'draft'"
            :project-id="selected?.projectId"
            :initial-version-id="
              typeof route.query.version === 'string' ? route.query.version : undefined
            "
            :conversation-id="selected?.id"
            :owner-id="user?.id || ''"
            @failure="failure"
            @sent="openSentConversation"
            @refresh-conversations="loadPanel"
          />
          <p v-else-if="loading" role="status">正在打开会话…</p>
          <button v-else-if="error" type="button" @click="loadPanel">重试加载</button>
        </article>
      </section>
      <nav v-if="ready && route.name === 'admin'" class="pagination" aria-label="分页">
        <button
          type="button"
          class="secondary"
          :disabled="page === 0 || loading || busy"
          @click="paginate(-1)"
        >
          上一页</button
        ><span>第 {{ page + 1 }} 页</span
        ><button
          type="button"
          class="secondary"
          :disabled="!hasMore || loading || busy"
          @click="paginate(1)"
        >
          下一页
        </button>
        <button v-if="error" type="button" :disabled="loading || busy" @click="loadPanel">
          重试加载
        </button>
      </nav>
    </main>
  </div>
</template>
