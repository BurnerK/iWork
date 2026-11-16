<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { errorMessage, request } from './api'
const props = defineProps<{ conversationId: string; projectId: string | null }>()
const emit = defineEmits<{ moved: []; failure: [cause: unknown] }>()
const projects = ref<{ id: string; name: string }[]>([]), target = ref(props.projectId || ''), page = ref(0), more = ref(false), busy = ref(false), error = ref('')
async function load(next = 0) {
  busy.value = true
  try {
    const result = await request<{ items: { id: string; name: string }[]; hasMore: boolean }>(`/api/projects?page=${next}`)
    projects.value.push(...result.items.filter(item => !projects.value.some(old => old.id === item.id))); page.value = next; more.value = result.hasMore
    if (props.projectId && !projects.value.some(item => item.id === props.projectId)) projects.value.push(await request(`/api/projects/${props.projectId}`))
  } catch (cause) { error.value = errorMessage(cause) } finally { busy.value = false }
}
async function move() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { await request(`/api/conversations/${props.conversationId}/project`, 'PUT', { projectId: target.value || null }); emit('moved') }
  catch (cause) { error.value = errorMessage(cause) } finally { busy.value = false }
}
onMounted(() => load())
</script>
<template>
  <form aria-label="会话所属项目" @submit.prevent="move">
    <p v-if="projectId">所属项目：<RouterLink :to="`/projects/${projectId}`">{{ projects.find(item => item.id === projectId)?.name || '查看项目' }}</RouterLink></p>
    <p class="hint">移动后，新任务使用目标项目的指令。运行中的任务保留提交时的配置。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <label for="conversation-project">会话所属项目</label><select id="conversation-project" v-model="target" :disabled="busy"><option value="">独立会话</option><option v-for="item in projects" :key="item.id" :value="item.id">{{ item.name }}</option></select>
    <button v-if="more" type="button" class="secondary" :disabled="busy" @click="load(page + 1)">加载更多项目</button><button type="submit" class="secondary" :disabled="busy || target === (projectId || '')">移动会话</button>
  </form>
</template>
