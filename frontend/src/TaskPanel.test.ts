import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import TaskPanel from './TaskPanel.vue'
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })
it('retries an uncertain submission with the same request identity and never cancels on unmount', async () => {
  const bodies: string[] = []
  const fetch = vi.fn((url: string, init?: RequestInit) => {
    if (url.endsWith('/csrf')) return Promise.resolve(new Response('{"token":"proof"}'))
    if (init?.method === 'POST') {
      bodies.push(String(init.body))
      if (bodies.length === 1) return Promise.reject(new TypeError('connection lost'))
      return Promise.resolve(new Response('{"id":"task-one","status":"RUNNING","executionState":"RUNNING"}', { status: 202 }))
    }
    return Promise.resolve(new Response(JSON.stringify(url === '/api/models' ? [{ id: 'model', name: '模型' }] : { items: [], hasMore: false })))
  })
  vi.stubGlobal('fetch', fetch)
  const panel = mount(TaskPanel, { props: { conversationId: 'conversation', ownerId: 'owner' } }); await flushPromises()
  await panel.get('select').setValue('model'); await panel.get('textarea').setValue('整理资料')
  await panel.get('form').trigger('submit'); await flushPromises()
  await panel.get('form').trigger('submit'); await flushPromises()
  expect(bodies).toHaveLength(2); expect(bodies[0]).toBe(bodies[1])
  panel.unmount()
  expect(fetch.mock.calls.some(([url]) => url.includes('/cancel'))).toBe(false)
})
it('keeps an uncertain request identity after reopening the same conversation', async () => {
  localStorage.clear()
  const bodies: string[] = []
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    if (url.endsWith('/csrf')) return Promise.resolve(new Response('{"token":"proof"}'))
    if (init?.method === 'POST') { bodies.push(String(init.body)); return Promise.reject(new TypeError('lost')) }
    return Promise.resolve(new Response(JSON.stringify(url === '/api/models' ? [{ id: 'model', name: '模型' }] : { items: [], hasMore: false })))
  }))
  let panel = mount(TaskPanel, { props: { conversationId: 'reopen', ownerId: 'owner' } }); await flushPromises()
  await panel.get('select').setValue('model'); await panel.get('textarea').setValue('要求')
  await panel.get('form').trigger('submit'); await flushPromises(); panel.unmount()
  panel = mount(TaskPanel, { props: { conversationId: 'reopen', ownerId: 'owner' } }); await flushPromises()
  expect(panel.text()).toContain('重试确认')
  await panel.get('form').trigger('submit'); await flushPromises()
  expect(bodies[0]).toBe(bodies[1]); panel.unmount(); localStorage.clear()
})
