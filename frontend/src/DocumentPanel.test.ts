import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import DocumentPanel from './DocumentPanel.vue'
afterEach(() => vi.unstubAllGlobals())
it('previews the saved version as text and links the same version for download', async () => {
  const version = {
    id: 'document',
    title: '文档',
    versionId: 'version',
    versionNumber: 1,
    taskId: 'task',
    byteSize: 31,
  }
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) =>
      Promise.resolve(
        new Response(
          JSON.stringify(
            url.endsWith('/documents')
              ? [version]
              : { ...version, markdown: '<script>unsafe()</script>\n正文' }
          )
        )
      )
    )
  )
  const panel = mount(DocumentPanel, { props: { conversationId: 'conversation', revision: '' } })
  await flushPromises()
  await panel.findAll('button')[0]!.trigger('click')
  await flushPromises()
  expect(panel.text()).toContain('<script>unsafe()</script>')
  expect(panel.find('script').exists()).toBe(false)
  expect(panel.get('a').attributes('href')).toBe('/api/document-versions/version/content')
  expect(panel.findAll('button')[0]!.attributes('aria-expanded')).toBe('true')
  panel.unmount()
})
it('keeps the last successful page when loading the next page fails', async () => {
  let attempts = 0
  const fetch = vi.fn((url: string) => {
    if (url === '/api/document-versions?page=1' && attempts++ === 0)
      return Promise.reject(new TypeError('lost'))
    return Promise.resolve(
      new Response(JSON.stringify(url.endsWith('/documents') ? [] : { items: [], hasMore: true }))
    )
  })
  vi.stubGlobal('fetch', fetch)
  const panel = mount(DocumentPanel, { props: { conversationId: 'conversation', revision: '' } })
  await flushPromises()
  await panel.get('#all-document-versions').setValue(true)
  await flushPromises()
  const next = () => panel.findAll('button').find((button) => button.text() === '下一页版本')!
  await next().trigger('click')
  await flushPromises()
  expect(panel.text()).toContain('第 1 页')
  await next().trigger('click')
  await flushPromises()
  expect(panel.text()).toContain('第 2 页')
  expect(fetch.mock.calls.filter(([url]) => url === '/api/document-versions?page=1')).toHaveLength(
    2
  )
  panel.unmount()
})
