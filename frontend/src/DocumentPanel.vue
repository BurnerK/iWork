<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import MarkdownContent from './MarkdownContent.vue'
import { errorMessage, request } from './api'
const props = defineProps<{
  conversationId: string
  revision: string
  projectId?: string
  disabled?: boolean
  conversationPreview?: boolean
}>()
type Version = {
  id: string
  title: string
  versionId: string
  versionNumber: number
  taskId: string
  byteSize: number
  baseVersionId?: string | null
}
const emit = defineEmits<{ selectVersion: [version: Version]; removeReference: [id: string] }>()
const all = ref(false),
  page = ref(0),
  hasMore = ref(false)
const items = ref<Version[]>([]),
  selected = ref<Version>(),
  markdown = ref(''),
  loading = ref(false),
  previewing = ref(false),
  error = ref('')
const previewError = ref<{ versionId: string; message: string }>()
const dialog = ref<HTMLDialogElement>()
let disposed = false,
  reload = false
async function load(nextPage = page.value) {
  if (disposed) return
  if (loading.value) {
    reload = true
    return
  }
  loading.value = true
  error.value = ''
  try {
    const path = all.value
      ? '/api/document-versions'
      : props.projectId
        ? `/api/projects/${props.projectId}/documents`
        : `/api/conversations/${props.conversationId}/documents`
    const result = await request<{ items: Version[]; hasMore: boolean }>(`${path}?page=${nextPage}`)
    if (!disposed) {
      items.value = result.items
      hasMore.value = result.hasMore
      page.value = nextPage
    }
  } catch (cause) {
    if (!disposed) error.value = errorMessage(cause)
  } finally {
    loading.value = false
    if (reload) {
      reload = false
      void load()
    }
  }
}
function changeScope() {
  page.value = 0
  items.value = []
  selected.value = undefined
  void load()
}
function paginate(delta: number) {
  void load(page.value + delta)
}
function revisePreview() {
  if (!selected.value) return
  dialog.value?.close()
  emit('selectVersion', selected.value)
}
async function preview(version: Pick<Version, 'versionId'>, fromReply = false) {
  if (previewing.value) return
  if (!props.conversationPreview && selected.value?.versionId === version.versionId) {
    selected.value = undefined
    return
  }
  previewing.value = true
  error.value = ''
  previewError.value = undefined
  try {
    const result = await request<Version & { markdown: string }>(
      `/api/document-versions/${version.versionId}`
    )
    if (!disposed) {
      selected.value = result
      markdown.value = result.markdown
      if (props.conversationPreview) {
        await nextTick()
        if (!disposed && dialog.value && !dialog.value.open) dialog.value.showModal()
      }
    }
  } catch (cause) {
    if (!disposed) {
      if (fromReply)
        previewError.value = { versionId: version.versionId, message: errorMessage(cause) }
      else error.value = errorMessage(cause)
    }
  } finally {
    previewing.value = false
  }
}
watch(
  () => props.revision,
  () => load()
)
onMounted(() => load())
onUnmounted(() => {
  disposed = true
})
</script>
<template>
  <section aria-label="正式文档">
    <slot
      :items="items"
      :preview="preview"
      :previewing="previewing"
      :preview-error="previewError"
    />
    <details :open="!conversationPreview">
      <summary v-if="conversationPreview">浏览文档版本</summary>
      <h2>正式文档</h2>
      <p class="hint">只有已保存的版本显示在这里。Markdown 原样预览，不执行其中的 HTML 或代码。</p>
      <div class="check-row">
        <input
          id="all-document-versions"
          v-model="all"
          type="checkbox"
          :disabled="loading"
          @change="changeScope"
        /><label for="all-document-versions">查看我的全部版本</label>
      </div>
      <p v-if="error" role="alert">{{ error }}</p>
      <p v-if="!items.length && !loading">
        {{ all ? '还没有已保存文档。' : '当前范围还没有已保存文档。' }}
      </p>
      <ul class="rows">
        <li v-for="version in items" :key="version.versionId">
          <div>
            <p>{{ version.title }} · 版本 {{ version.versionNumber }}</p>
            <small>Markdown · {{ version.byteSize }} 字节</small><small>成果 {{ version.id }}</small
            ><small v-if="version.baseVersionId">来源版本 {{ version.baseVersionId }}</small
            ><small>任务 {{ version.taskId }}</small>
          </div>
          <button
            type="button"
            class="secondary"
            :disabled="!conversationPreview && previewing"
            :aria-disabled="conversationPreview ? previewing : undefined"
            :aria-haspopup="conversationPreview ? 'dialog' : undefined"
            :aria-expanded="
              conversationPreview ? undefined : selected?.versionId === version.versionId
            "
            :aria-controls="conversationPreview ? undefined : 'document-preview'"
            @click="preview(version)"
          >
            预览文档</button
          ><a :href="`/api/document-versions/${version.versionId}/content`">下载 .md</a
          ><button
            type="button"
            class="secondary"
            :disabled="disabled"
            @click="emit('selectVersion', version)"
          >
            基于此版本修改</button
          ><button
            v-if="projectId && !all"
            type="button"
            class="secondary"
            :disabled="disabled"
            @click="emit('removeReference', version.id)"
          >
            移除成果引用
          </button>
        </li>
      </ul>
      <nav aria-label="文档版本分页">
        <button
          type="button"
          class="secondary"
          :disabled="loading || page === 0"
          @click="paginate(-1)"
        >
          上一页版本</button
        ><span>第 {{ page + 1 }} 页</span
        ><button
          type="button"
          class="secondary"
          :disabled="loading || !hasMore"
          @click="paginate(1)"
        >
          下一页版本
        </button>
      </nav>
      <button type="button" class="secondary" :disabled="loading" @click="load()">刷新文档</button>
    </details>
    <dialog
      v-if="conversationPreview"
      ref="dialog"
      class="document-drawer"
      aria-label="文档预览"
      @close="selected = undefined"
    >
      <template v-if="selected">
        <form method="dialog"><button class="secondary" autofocus>关闭预览</button></form>
        <h2>{{ selected.title }} · 版本 {{ selected.versionNumber }}</h2>
        <MarkdownContent :content="markdown" />
        <a :href="`/api/document-versions/${selected.versionId}/content`">下载 .md</a>
        <button type="button" class="secondary" :disabled="disabled" @click="revisePreview">
          基于此版本修改
        </button>
      </template>
    </dialog>
    <div
      v-if="!conversationPreview && selected"
      id="document-preview"
      class="messages"
      aria-label="文档只读预览"
    >
      <p>{{ markdown }}</p>
    </div>
  </section>
</template>
