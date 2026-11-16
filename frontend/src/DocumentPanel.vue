<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { errorMessage, request } from './api'
const props = defineProps<{ conversationId: string; revision: string; disabled?: boolean }>()
type Version = { id: string; title: string; versionId: string; versionNumber: number; taskId: string; byteSize: number; baseVersionId?: string | null }
const emit = defineEmits<{ selectVersion: [version: Version] }>()
const all = ref(false), page = ref(0), hasMore = ref(false)
const items = ref<Version[]>([]), selected = ref<Version>(), markdown = ref(''), loading = ref(false), previewing = ref(false), error = ref('')
let disposed = false, reload = false
async function load(nextPage = page.value) {
  if (disposed) return
  if (loading.value) { reload = true; return }
  loading.value = true; error.value = ''
  try {
    if (all.value) {
      const result = await request<{ items: Version[]; hasMore: boolean }>(`/api/document-versions?page=${nextPage}`)
      if (!disposed) { items.value = result.items; hasMore.value = result.hasMore; page.value = nextPage }
    } else {
      const result = await request<Version[]>(`/api/conversations/${props.conversationId}/documents`)
      if (!disposed) { items.value = result; hasMore.value = false }
    }
  }
  catch (cause) { if (!disposed) error.value = errorMessage(cause) } finally { loading.value = false; if (reload) { reload = false; void load() } }
}
function changeScope() { page.value = 0; items.value = []; selected.value = undefined; void load() }
function paginate(delta: number) { void load(page.value + delta) }
async function preview(version: Version) {
  if (previewing.value) return
  if (selected.value?.versionId === version.versionId) { selected.value = undefined; return }
  previewing.value = true; error.value = ''
  try {
    const result = await request<Version & { markdown: string }>(`/api/document-versions/${version.versionId}`)
    if (!disposed) { selected.value = result; markdown.value = result.markdown }
  } catch (cause) { if (!disposed) error.value = errorMessage(cause) } finally { previewing.value = false }
}
watch(() => props.revision, () => load())
onMounted(() => load())
onUnmounted(() => { disposed = true })
</script>
<template>
  <section aria-label="正式文档">
    <h2>正式文档</h2><p class="hint">只有已保存的版本显示在这里。Markdown 原样预览，不执行其中的 HTML 或代码。</p>
    <div class="check-row"><input id="all-document-versions" v-model="all" type="checkbox" :disabled="loading" @change="changeScope"><label for="all-document-versions">查看我的全部版本</label></div>
    <p v-if="error" role="alert">{{ error }}</p><p v-if="!items.length && !loading">{{ all ? '还没有已保存文档。' : '当前会话还没有已保存文档。' }}</p>
    <ul class="rows"><li v-for="version in items" :key="version.versionId"><div><p>{{ version.title }} · 版本 {{ version.versionNumber }}</p><small>Markdown · {{ version.byteSize }} 字节</small><small>成果 {{ version.id }}</small><small v-if="version.baseVersionId">来源版本 {{ version.baseVersionId }}</small><small>任务 {{ version.taskId }}</small></div><button type="button" class="secondary" :disabled="previewing" :aria-expanded="selected?.versionId === version.versionId" aria-controls="document-preview" @click="preview(version)">预览文档</button><a :href="`/api/document-versions/${version.versionId}/content`">下载 .md</a><button type="button" class="secondary" :disabled="disabled" @click="emit('selectVersion', version)">基于此版本修改</button></li></ul>
    <div v-if="selected" id="document-preview" class="messages" aria-label="文档只读预览"><p>{{ markdown }}</p></div>
    <nav v-if="all" aria-label="个人版本分页"><button type="button" class="secondary" :disabled="loading || page === 0" @click="paginate(-1)">上一页版本</button><span>第 {{ page + 1 }} 页</span><button type="button" class="secondary" :disabled="loading || !hasMore" @click="paginate(1)">下一页版本</button></nav>
    <button type="button" class="secondary" :disabled="loading" @click="load()">刷新文档</button>
  </section>
</template>
