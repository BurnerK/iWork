<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError, errorMessage, request } from './api'
const props = defineProps<{ modelValue: string[]; disabled: boolean; projectId?: string }>()
const emit = defineEmits<{ 'update:modelValue': [ids: string[]]; failure: [cause: unknown]; uploading: [value: boolean] }>()
type Material = { id: string; name: string; byteSize: number }
const items = ref<Material[]>([]), file = ref<File>(), input = ref<HTMLInputElement>()
const page = ref(0), more = ref(false), busy = ref(false), error = ref(''), notice = ref('')
async function fail(cause: unknown) {
  error.value = errorMessage(cause)
  if (cause instanceof ApiError && cause.status === 401) emit('failure', cause)
}
async function load(delta = 0) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try {
    const result = await request<{ items: Material[]; hasMore: boolean }>(props.projectId ? `/api/projects/${props.projectId}/materials?page=${page.value + delta}` : `/api/materials?page=${page.value + delta}`)
    items.value = result.items; more.value = result.hasMore; page.value += delta
  } catch (cause) { await fail(cause) } finally { busy.value = false }
}
function select(id: string, checked: boolean) {
  emit('update:modelValue', checked ? [...props.modelValue, id] : props.modelValue.filter(value => value !== id))
}
async function upload() {
  if (busy.value || props.disabled) return
  busy.value = true; emit('uploading', true); error.value = ''; notice.value = ''
  try {
    if (!file.value || !file.value.size) throw new ApiError(400, 'incomplete_material')
    if (file.value.size > 2 * 1024 * 1024) throw new ApiError(413, 'material_too_large')
    if (!/^.+\.(txt|md)$/i.test(file.value.name)) throw new ApiError(415, 'unsupported_material')
    const saved = await request<Material>('/api/materials', 'POST', file.value)
    notice.value = `已保存：${saved.name}`
    if (props.modelValue.length < 5) emit('update:modelValue', [...props.modelValue, saved.id])
    file.value = undefined; input.value?.form?.reset()
    page.value = 0
  } catch (cause) { await fail(cause); return } finally { busy.value = false; emit('uploading', false) }
  await load()
}
onMounted(() => load())
</script>
<template>
  <section :aria-label="projectId ? '项目引用资料' : '个人资料'">
    <h2>{{ projectId ? '项目引用资料（勾选后用于本次任务）' : '本次资料' }}</h2>
    <p :id="projectId ? 'project-material-help' : 'material-help'" class="hint">支持 UTF-8 的 TXT、Markdown 文件，每份最多 2 MiB，每次任务最多选 5 份。原文保留在你的资料中，可在其他会话复用。</p>
    <p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <form v-if="!projectId" aria-label="上传资料" @submit.prevent="upload">
      <label for="material-file">选择文件（必填）</label>
      <input id="material-file" ref="input" type="file" accept=".txt,.md" required aria-describedby="material-help" :disabled="busy || disabled" @change="file = ($event.target as HTMLInputElement).files?.[0]">
      <button type="submit" :disabled="busy || disabled">{{ busy ? '处理中…' : '上传并保存原文' }}</button>
    </form>
    <p>已选 {{ modelValue.length }} / 5 份</p>
    <ul class="rows"><li v-for="item in items" :key="item.id"><div class="check-row">
      <input :id="`${projectId ? 'project-' : ''}material-${item.id}`" type="checkbox" :checked="modelValue.includes(item.id)" :disabled="disabled || busy || modelValue.length >= 5 && !modelValue.includes(item.id)" @change="select(item.id, ($event.target as HTMLInputElement).checked)">
      <label :for="`${projectId ? 'project-' : ''}material-${item.id}`">{{ item.name }}<small>{{ item.byteSize }} 字节</small></label>
      </div><a :href="`/api/materials/${item.id}/content`">下载原文</a></li></ul>
    <nav class="pagination" :aria-label="projectId ? '项目引用资料分页' : '资料分页'"><button type="button" class="secondary" :disabled="busy || page === 0" @click="load(-1)">上一页资料</button><span>第 {{ page + 1 }} 页</span><button type="button" class="secondary" :disabled="busy || !more" @click="load(1)">下一页资料</button><button type="button" class="secondary" :disabled="busy" @click="load()">刷新资料</button></nav>
  </section>
</template>
