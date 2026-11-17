import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { makeRouter } from './router'
import ProjectPanel from './ProjectPanel.vue'
afterEach(() => {
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})
it('only deletes project references after explicit confirmation and clears dialog return value', async () => {
  const writes: string[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      if (url.endsWith('/csrf')) return Promise.resolve(new Response('{"token":"proof"}'))
      if (init?.method === 'DELETE') {
        writes.push(url)
        return Promise.resolve(new Response(null, { status: 204 }))
      }
      return Promise.resolve(
        new Response(
          JSON.stringify(
            url === '/api/projects/owned'
              ? { id: 'owned', name: '蓝鲸', instructions: '' }
              : { items: [], hasMore: false }
          )
        )
      )
    })
  )
  const router = makeRouter(createMemoryHistory())
  await router.push('/projects/owned')
  const panel = mount(ProjectPanel, {
    props: { projectId: 'owned' },
    global: { plugins: [router] },
    attachTo: document.body,
  })
  await flushPromises()
  const dialog = panel.get('dialog').element as HTMLDialogElement
  expect(panel.get('dialog').text()).toContain('会话、原始资料和正式成果保留')
  dialog.returnValue = 'cancel'
  await panel.get('dialog').trigger('close')
  await flushPromises()
  expect(writes).toEqual([])
  expect(dialog.returnValue).toBe('')
  dialog.returnValue = 'delete'
  await panel.get('dialog').trigger('close')
  await flushPromises()
  expect(writes).toEqual(['/api/projects/owned'])
  expect(dialog.returnValue).toBe('')
  expect(router.currentRoute.value.path).toBe('/projects')
  await panel.get('dialog').trigger('close')
  await flushPromises()
  expect(writes).toHaveLength(1)
  panel.unmount()
})
