import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import DocumentPanel from './DocumentPanel.vue'
afterEach(() => vi.unstubAllGlobals())
it('previews the saved version as text and links the same version for download', async () => {
  const version = { id: 'document', title: '文档', versionId: 'version', versionNumber: 1, taskId: 'task', byteSize: 31 }
  vi.stubGlobal('fetch', vi.fn((url: string) => Promise.resolve(new Response(JSON.stringify(url.endsWith('/documents') ? [version] : { ...version, markdown: '<script>unsafe()</script>\n正文' })))))
  const panel = mount(DocumentPanel, { props: { conversationId: 'conversation', revision: '' } }); await flushPromises()
  await panel.findAll('button')[0]!.trigger('click'); await flushPromises()
  expect(panel.text()).toContain('<script>unsafe()</script>')
  expect(panel.find('script').exists()).toBe(false)
  expect(panel.get('a').attributes('href')).toBe('/api/document-versions/version/content')
  expect(panel.findAll('button')[0]!.attributes('aria-expanded')).toBe('true')
  panel.unmount()
})
