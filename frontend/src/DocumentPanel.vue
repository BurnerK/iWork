<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { errorMessage, request } from './api'
const props = defineProps<{ conversationId: string; revision: string }>()
type Version = { id: string; title: string; versionId: string; versionNumber: number; taskId: string; byteSize: number }
const items = ref<Version[]>([]), selected = ref<Version>(), markdown = ref(''), loading = ref(false), previewing = ref(false), error = ref('')
let disposed = false, reload = false
async function load() {
  if (disposed) return
  if (loading.value) { reload = true; return }
  loading.value = true; error.value = ''
  try { const result = await request<Version[]>(`/api/conversations/${props.conversationId}/documents`); if (!disposed) items.value = result }
  catch (cause) { if (!disposed) error.value = errorMessage(cause) } finally { loading.value = false; if (reload) { reload = false; void load() } }
}
async function preview(version: Version) {
  if (previewing.value) return
  if (selected.value?.versionId === version.versionId) { selected.value = undefined; return }
  previewing.value = true; error.value = ''
  try {
    const result = await request<Version & { markdown: string }>(`/api/document-versions/${version.versionId}`)
    if (!disposed) { selected.value = result; markdown.value = result.markdown }
  } catch (cause) { if (!disposed) error.value = errorMessage(cause) } finally { previewing.value = false }
}
watch(() => props.revision, load)
onMounted(load)
onUnmounted(() => { disposed = true })
</script>
<template>
  <section aria-label="正式文档">
    <h2>正式文档</h2><p class="hint">只有已保存的版本显示在这里。Markdown 原样预览，不执行其中的 HTML 或代码。</p>
    <p v-if="error" role="alert">{{ error }}</p><p v-if="!items.length && !loading">当前会话还没有已保存文档。</p>
    <ul class="rows"><li v-for="version in items" :key="version.versionId"><div><p>{{ version.title }} · 版本 {{ version.versionNumber }}</p><small>Markdown · {{ version.byteSize }} 字节</small><small>任务 {{ version.taskId }}</small></div><button type="button" class="secondary" :disabled="previewing" :aria-expanded="selected?.versionId === version.versionId" aria-controls="document-preview" @click="preview(version)">预览文档</button><a :href="`/api/document-versions/${version.versionId}/content`">下载 .md</a></li></ul>
    <div v-if="selected" id="document-preview" class="messages" aria-label="文档只读预览"><p>{{ markdown }}</p></div>
    <button type="button" class="secondary" :disabled="loading" @click="load">刷新文档</button>
  </section>
</template>
