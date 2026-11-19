<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import DocumentPanel from './DocumentPanel.vue'
import { ApiError, errorMessage, request } from './api'
const props = defineProps<{ projectId?: string }>()
const emit = defineEmits<{ failure: [cause: unknown] }>()
const router = useRouter()
type Project = { id: string; name: string; instructions: string }
type Item = {
  id: string
  name?: string
  title?: string
  versionId?: string
  versionNumber?: number
}
type Page = { items: Item[]; hasMore: boolean }
const projects = ref<Project[]>([]),
  project = ref<Project>(),
  name = ref(''),
  instructions = ref('')
const conversations = ref<Item[]>([]),
  materials = ref<Item[]>([]),
  choices = ref<Item[]>([])
const page = ref(0),
  more = ref(false),
  conversationPage = ref(0),
  moreConversations = ref(false),
  materialPage = ref(0),
  moreMaterials = ref(false)
const kind = ref('materials'),
  choicePage = ref(0),
  moreChoices = ref(false),
  choice = ref(''),
  revision = ref(0)
const busy = ref(false),
  error = ref(''),
  notice = ref(''),
  dialog = ref<HTMLDialogElement>()
let disposed = false
async function act(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await action()
  } catch (cause) {
    if (disposed) return
    error.value = errorMessage(cause)
    if (cause instanceof ApiError && cause.status === 401) emit('failure', cause)
    await nextTick()
    document.getElementById('project-error')?.focus()
  } finally {
    busy.value = false
  }
}
async function load(target = page.value) {
  const result = await request<{ items: Project[]; hasMore: boolean }>(
    `/api/projects?page=${target}`
  )
  projects.value = result.items
  page.value = target
  more.value = result.hasMore
}
async function loadConversations(target = conversationPage.value) {
  const result = await request<Page>(
    `/api/projects/${props.projectId}/conversations?page=${target}`
  )
  conversations.value = result.items
  conversationPage.value = target
  moreConversations.value = result.hasMore
}
async function loadMaterials(target = materialPage.value) {
  const result = await request<Page>(`/api/projects/${props.projectId}/materials?page=${target}`)
  materials.value = result.items
  materialPage.value = target
  moreMaterials.value = result.hasMore
}
async function loadChoices(target = 0) {
  const result = await request<Page>(
    `/api/${kind.value === 'materials' ? 'materials' : 'document-versions'}?page=${target}`
  )
  choices.value = result.items
  choicePage.value = target
  moreChoices.value = result.hasMore
  choice.value = ''
}
function save() {
  return act(async () => {
    const saved = await request<Project>(
      `/api/projects${props.projectId ? `/${props.projectId}` : ''}`,
      props.projectId ? 'PUT' : 'POST',
      { name: name.value, instructions: instructions.value }
    )
    if (props.projectId) {
      project.value = saved
      notice.value = '已保存，后续新任务使用更新后的项目指令。'
    } else await router.push(`/projects/${saved.id}`)
  })
}
function createConversation(version?: { versionId: string }) {
  return act(async () => {
    const created = await request<Item>('/api/conversations', 'POST', {
      title: project.value?.name,
      projectId: props.projectId,
    })
    await router.push({
      path: `/conversations/${created.id}`,
      query: version ? { version: version.versionId } : {},
    })
  })
}
function add() {
  return act(async () => {
    await request(`/api/projects/${props.projectId}/${kind.value}`, 'POST', {
      [kind.value === 'materials' ? 'materialId' : 'versionId']: choice.value,
    })
    choice.value = ''
    revision.value++
    await loadMaterials(0)
    notice.value = '已引用，原资料和成果仍归你所有。'
  })
}
function removeMaterial(id: string) {
  return act(async () => {
    await request(`/api/projects/${props.projectId}/materials/${id}`, 'DELETE')
    await loadMaterials()
    notice.value = '已移除引用，个人资料中的原文保留。'
  })
}
function removeDocument(id: string) {
  return act(async () => {
    await request(`/api/projects/${props.projectId}/documents/${id}`, 'DELETE')
    revision.value++
    notice.value = '已移除引用，个人成果和全部版本保留。'
  })
}
function closed() {
  const confirmed = dialog.value?.returnValue === 'delete'
  if (dialog.value) dialog.value.returnValue = ''
  if (confirmed)
    void act(async () => {
      await request(`/api/projects/${props.projectId}`, 'DELETE')
      await router.push('/projects')
    })
}
onMounted(() =>
  act(async () => {
    if (!props.projectId) {
      await load()
      return
    }
    project.value = await request<Project>(`/api/projects/${props.projectId}`)
    name.value = project.value.name
    instructions.value = project.value.instructions
    await Promise.all([loadConversations(), loadMaterials(), loadChoices()])
  })
)
onUnmounted(() => {
  disposed = true
})
</script>
<template>
  <section :aria-busy="busy">
    <h1 tabindex="-1">{{ project?.name || '项目空间' }}</h1>
    <p>项目用于组织你的会话、资料和成果。资料需在每次任务中明确选择。</p>
    <p v-if="error" id="project-error" role="alert" tabindex="-1">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <template v-if="!projectId">
      <ul class="rows">
        <li v-for="item in projects" :key="item.id">
          <RouterLink :to="`/projects/${item.id}`">{{ item.name }}</RouterLink>
        </li>
      </ul>
      <nav aria-label="项目分页">
        <button class="secondary" :disabled="busy || page === 0" @click="act(() => load(page - 1))">
          上一页项目</button
        ><button class="secondary" :disabled="busy || !more" @click="act(() => load(page + 1))">
          下一页项目
        </button>
      </nav>
    </template>
    <form class="surface-card" method="post" aria-label="项目设置" @submit.prevent="save">
      <h2>{{ projectId ? '项目设置' : '新建项目' }}</h2>
      <label for="project-name">名称（必填，最多120字符）</label
      ><input id="project-name" v-model="name" required maxlength="120" :disabled="busy" />
      <label for="project-instructions">项目指令（最多4000字符）</label
      ><textarea
        id="project-instructions"
        v-model="instructions"
        maxlength="4000"
        rows="4"
        :disabled="busy"
      ></textarea>
      <button type="submit" :disabled="busy">{{ projectId ? '保存项目' : '创建项目' }}</button>
    </form>
    <template v-if="project">
      <h2>项目会话</h2>
      <button :disabled="busy" @click="createConversation()">在项目中新建会话</button>
      <ul class="rows">
        <li v-for="item in conversations" :key="item.id">
          <RouterLink :to="`/conversations/${item.id}`">{{ item.title }}</RouterLink>
        </li>
      </ul>
      <nav aria-label="项目会话分页">
        <button
          class="secondary"
          :disabled="busy || conversationPage === 0"
          @click="act(() => loadConversations(conversationPage - 1))"
        >
          上一页会话</button
        ><button
          class="secondary"
          :disabled="busy || !moreConversations"
          @click="act(() => loadConversations(conversationPage + 1))"
        >
          下一页会话
        </button>
      </nav>
      <h2>项目引用资料</h2>
      <ul class="rows">
        <li v-for="item in materials" :key="item.id">
          <span>{{ item.name }}</span><a :href="`/api/materials/${item.id}/content`">下载原文</a
          ><button class="secondary" :disabled="busy" @click="removeMaterial(item.id)">
            移除资料引用
          </button>
        </li>
      </ul>
      <nav aria-label="项目资料分页">
        <button
          class="secondary"
          :disabled="busy || materialPage === 0"
          @click="act(() => loadMaterials(materialPage - 1))"
        >
          上一页引用资料</button
        ><button
          class="secondary"
          :disabled="busy || !moreMaterials"
          @click="act(() => loadMaterials(materialPage + 1))"
        >
          下一页引用资料
        </button>
      </nav>
      <form aria-label="引用个人资源" @submit.prevent="add">
        <h2>引用个人资源</h2>
        <p class="hint">上传资料可在会话中完成。引用成果会包含其所有正式版本。</p>
        <label for="reference-kind">资源类型</label
        ><select
          id="reference-kind"
          v-model="kind"
          :disabled="busy"
          @change="act(() => loadChoices())"
        >
          <option value="materials">资料</option>
          <option value="documents">成果</option>
        </select>
        <label for="reference-choice">选择资源（必选）</label
        ><select id="reference-choice" v-model="choice" required :disabled="busy">
          <option value="">请选择</option>
          <option
            v-for="item in choices"
            :key="item.versionId || item.id"
            :value="item.versionId || item.id"
          >
            {{ item.name || item.title
            }}{{ item.versionNumber ? ` · 版本 ${item.versionNumber}` : '' }}
          </option>
        </select>
        <nav aria-label="个人资源分页">
          <button
            type="button"
            class="secondary"
            :disabled="busy || choicePage === 0"
            @click="act(() => loadChoices(choicePage - 1))"
          >
            上一页资源</button
          ><button
            type="button"
            class="secondary"
            :disabled="busy || !moreChoices"
            @click="act(() => loadChoices(choicePage + 1))"
          >
            下一页资源
          </button>
        </nav>
        <button type="submit" :disabled="busy">添加引用</button>
      </form>
      <DocumentPanel
        conversation-id=""
        :project-id="projectId"
        :revision="String(revision)"
        :disabled="busy"
        @select-version="createConversation"
        @remove-reference="removeDocument"
      />
      <button class="secondary" :disabled="busy" @click="dialog?.showModal()">删除项目</button>
      <dialog ref="dialog" aria-labelledby="delete-project-title" @close="closed">
        <h2 id="delete-project-title">删除这个项目？</h2>
        <p>仅删除项目及其引用。会话、原始资料和正式成果保留在你的个人空间。</p>
        <form method="dialog">
          <button value="cancel" autofocus>保留项目</button
          ><button value="delete" class="secondary">确认删除项目</button>
        </form>
      </dialog>
    </template>
  </section>
</template>
