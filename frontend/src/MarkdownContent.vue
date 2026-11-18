<script lang="ts">
import MarkdownIt from 'markdown-it'

const markdown = new MarkdownIt({ html: false }).disable('image')
</script>

<script setup lang="ts">
defineProps<{ content: string }>()
</script>

<template>
  <!-- markdown-it escapes raw HTML and validates link schemes. -->
  <div class="markdown-content" v-html="markdown.render(content)" />
</template>
