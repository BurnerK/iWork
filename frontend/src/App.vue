<script setup lang="ts">
import { onMounted, ref } from 'vue'

const checking = ref(false)
const status = ref('')

async function checkConnection() {
  if (checking.value) return
  checking.value = true
  try {
    const response = await fetch('/actuator/health/readiness', {
      cache: 'no-store', signal: AbortSignal.timeout(5000),
    })
    const body: unknown = await response.json()
    status.value = response.ok && typeof body === 'object' && body !== null
      && 'status' in body && body.status === 'UP'
      ? '服务已就绪' : '服务暂不可用，请稍后重试'
  } catch {
    status.value = '服务暂不可用，请稍后重试'
  } finally {
    checking.value = false
  }
}

onMounted(checkConnection)
</script>

<template>
  <main>
    <h1>iWork</h1>
    <p>你的工作助手，即将启程。</p>
    <p role="status">{{ status }}</p>
    <button type="button" :disabled="checking" @click="checkConnection">
      {{ checking ? '正在连接…' : '检查连接' }}
    </button>
  </main>
</template>
