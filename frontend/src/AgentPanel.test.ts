import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import AgentPanel from './AgentPanel.vue'
afterEach(() => {
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})
it('keeps an edited instruction after authorization failure and saves the same owned configuration on retry', async () => {
  const agent = { id: 'owned', name: '方案助手', instructions: '旧指令', modelId: 'model' }
  const writes: { url: string; body: any }[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      if (url.endsWith('/csrf')) return Promise.resolve(new Response('{"token":"proof"}'))
      if (init?.method === 'PUT') {
        writes.push({ url, body: JSON.parse(String(init.body)) })
        return Promise.resolve(
          writes.length === 1
            ? new Response('{"error":"model_not_authorized"}', { status: 403 })
            : new Response(JSON.stringify({ ...agent, ...writes[1]!.body }))
        )
      }
      return Promise.resolve(
        new Response(
          JSON.stringify(
            url === '/api/models'
              ? [{ id: 'model', name: '写作模型' }]
              : { items: [agent], hasMore: false }
          )
        )
      )
    })
  )
  const panel = mount(AgentPanel, { attachTo: document.body })
  await flushPromises()
  await panel.get('[aria-label="个人智能体"] button').trigger('click')
  await flushPromises()
  expect((panel.get('textarea').element as HTMLTextAreaElement).value).toBe('旧指令')
  await panel.get('textarea').setValue('按角色分工组织正文')
  await panel.get('form').trigger('submit')
  await flushPromises()
  expect(panel.get('[role="alert"]').text()).toContain('没有使用权限')
  expect((panel.get('textarea').element as HTMLTextAreaElement).value).toBe('按角色分工组织正文')
  expect(document.activeElement).toBe(panel.get('[role="alert"]').element)
  await panel.get('form').trigger('submit')
  await flushPromises()
  expect(writes).toHaveLength(2)
  expect(writes[1]).toEqual(writes[0])
  expect(writes[0]).toEqual({
    url: '/api/agents/owned',
    body: { name: '方案助手', instructions: '按角色分工组织正文', modelId: 'model' },
  })
  expect(panel.get('[role="status"]').text()).toContain('正在执行的任务保持原配置')
  panel.unmount()
})
