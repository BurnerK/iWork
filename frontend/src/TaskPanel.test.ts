import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import TaskPanel from './TaskPanel.vue'
afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})
it('retries an uncertain submission with the same request identity and never cancels on unmount', async () => {
  const bodies: string[] = []
  const fetch = vi.fn((url: string, init?: RequestInit) => {
    if (url.endsWith('/csrf')) return Promise.resolve(new Response('{"token":"proof"}'))
    if (init?.method === 'POST') {
      bodies.push(String(init.body))
      if (bodies.length === 1) return Promise.reject(new TypeError('connection lost'))
      return Promise.resolve(
        new Response('{"id":"task-one","status":"RUNNING","executionState":"RUNNING"}', {
          status: 202,
        })
      )
    }
    return Promise.resolve(
      new Response(
        JSON.stringify(
          url === '/api/models'
            ? [{ id: 'model', name: '模型' }]
            : url.endsWith('/documents')
              ? []
              : { items: [], hasMore: false }
        )
      )
    )
  })
  vi.stubGlobal('fetch', fetch)
  const panel = mount(TaskPanel, { props: { conversationId: 'conversation', ownerId: 'owner' } })
  await flushPromises()
  await panel.get('select[name="modelId"]').setValue('model')
  await panel.get('textarea').setValue('整理资料')
  await panel.get('form[aria-label="提交任务"]').trigger('submit')
  await flushPromises()
  await panel.get('form[aria-label="提交任务"]').trigger('submit')
  await flushPromises()
  expect(bodies).toHaveLength(2)
  expect(bodies[0]).toBe(bodies[1])
  panel.unmount()
  expect(fetch.mock.calls.some(([url]) => url.includes('/cancel'))).toBe(false)
})
it('keeps an uncertain request identity after reopening the same conversation', async () => {
  localStorage.clear()
  const bodies: string[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      if (url.endsWith('/csrf')) return Promise.resolve(new Response('{"token":"proof"}'))
      if (init?.method === 'POST') {
        bodies.push(String(init.body))
        return Promise.reject(new TypeError('lost'))
      }
      return Promise.resolve(
        new Response(
          JSON.stringify(
            url === '/api/models'
              ? [{ id: 'model', name: '模型' }]
              : url.endsWith('/documents')
                ? []
                : { items: [], hasMore: false }
          )
        )
      )
    })
  )
  let panel = mount(TaskPanel, { props: { conversationId: 'reopen', ownerId: 'owner' } })
  await flushPromises()
  await panel.get('select[name="modelId"]').setValue('model')
  await panel.get('textarea').setValue('要求')
  await panel.get('form[aria-label="提交任务"]').trigger('submit')
  await flushPromises()
  panel.unmount()
  panel = mount(TaskPanel, { props: { conversationId: 'reopen', ownerId: 'owner' } })
  await flushPromises()
  expect(panel.text()).toContain('重试确认')
  await panel.get('form[aria-label="提交任务"]').trigger('submit')
  await flushPromises()
  expect(bodies[0]).toBe(bodies[1])
  panel.unmount()
  localStorage.clear()
})

it('waits for an in-flight upload before accepting the task input', async () => {
  let finish!: (response: Response) => void
  const fetch = vi.fn((url: string, init?: RequestInit) => {
    if (url.endsWith('/csrf')) return Promise.resolve(new Response('{"token":"proof"}'))
    if (url === '/api/materials' && init?.method === 'POST')
      return new Promise<Response>((resolve) => {
        finish = resolve
      })
    return Promise.resolve(
      new Response(
        JSON.stringify(
          url === '/api/models'
            ? [{ id: 'model', name: '模型' }]
            : url.endsWith('/documents')
              ? []
              : { items: [], hasMore: false }
        )
      )
    )
  })
  vi.stubGlobal('fetch', fetch)
  const panel = mount(TaskPanel, { props: { conversationId: 'upload', ownerId: 'owner' } })
  await flushPromises()
  const file = new File(['source'], 'source.txt')
  Object.defineProperty(panel.get('input[type=file]').element, 'files', { value: [file] })
  await panel.get('input[type=file]').trigger('change')
  await panel.get('form[aria-label="上传资料"]').trigger('submit')
  await flushPromises()
  const form = panel.get('form[aria-label="提交任务"]')
  expect(form.get('button[type=submit]').attributes('disabled')).toBeDefined()
  await form.trigger('submit')
  await flushPromises()
  expect(
    fetch.mock.calls.some(([url, init]) => url.includes('/tasks') && init?.method === 'POST')
  ).toBe(false)
  finish(new Response('{"id":"saved","name":"source.txt","byteSize":6}'))
  await flushPromises()
  expect(form.get('button[type=submit]').attributes('disabled')).toBeUndefined()
  panel.unmount()
})
it('preserves explicit document output when retrying an uncertain request', async () => {
  localStorage.clear()
  const bodies: string[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      if (url.endsWith('/csrf')) return Promise.resolve(new Response('{"token":"proof"}'))
      if (init?.method === 'POST') {
        bodies.push(String(init.body))
        return Promise.reject(new TypeError('lost'))
      }
      return Promise.resolve(
        new Response(
          JSON.stringify(
            url === '/api/models'
              ? [{ id: 'model', name: '模型' }]
              : url.endsWith('/documents')
                ? []
                : { items: [], hasMore: false }
          )
        )
      )
    })
  )
  let panel = mount(TaskPanel, { props: { conversationId: 'document', ownerId: 'owner' } })
  await flushPromises()
  await panel.get('select[name="modelId"]').setValue('model')
  await panel.get('select[name="outputMode"]').setValue('DOCUMENT')
  await panel.get('textarea').setValue('生成文档')
  await panel.get('form[aria-label="提交任务"]').trigger('submit')
  await flushPromises()
  panel.unmount()
  panel = mount(TaskPanel, { props: { conversationId: 'document', ownerId: 'owner' } })
  await flushPromises()
  expect((panel.get('select[name="outputMode"]').element as HTMLSelectElement).value).toBe(
    'DOCUMENT'
  )
  await panel.get('form[aria-label="提交任务"]').trigger('submit')
  await flushPromises()
  expect(JSON.parse(bodies[0]!).outputMode).toBe('DOCUMENT')
  expect(bodies[0]).toBe(bodies[1])
  panel.unmount()
  localStorage.clear()
})
it('selects a personal version and preserves its source across an uncertain retry', async () => {
  localStorage.clear()
  const version = {
    id: 'artifact',
    versionId: 'source-version',
    title: '项目计划',
    versionNumber: 1,
    taskId: 'old-task',
    byteSize: 30,
  }
  const bodies: string[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      if (url.endsWith('/csrf')) return Promise.resolve(new Response('{"token":"proof"}'))
      if (init?.method === 'POST') {
        bodies.push(String(init.body))
        return Promise.reject(new TypeError('lost'))
      }
      return Promise.resolve(
        new Response(
          JSON.stringify(
            url === '/api/models'
              ? [{ id: 'model', name: '模型' }]
              : url === '/api/document-versions/source-version'
                ? version
                : url === '/api/document-versions?page=0'
                  ? { items: [version], hasMore: false }
                  : url.endsWith('/documents')
                    ? []
                    : { items: [], hasMore: false }
          )
        )
      )
    })
  )
  let panel = mount(TaskPanel, { props: { conversationId: 'revision', ownerId: 'owner' } })
  await flushPromises()
  await panel.get('#all-document-versions').setValue(true)
  await flushPromises()
  await panel
    .findAll('button')
    .find((button) => button.text() === '基于此版本修改')!
    .trigger('click')
  expect(panel.text()).toContain('基于《项目计划》版本 1 修改')
  expect(panel.get('select[name="outputMode"]').attributes('disabled')).toBeDefined()
  await panel.get('select[name="modelId"]').setValue('model')
  await panel.get('textarea').setValue('补充步骤')
  await panel.get('form[aria-label="提交任务"]').trigger('submit')
  await flushPromises()
  panel.unmount()
  panel = mount(TaskPanel, { props: { conversationId: 'revision', ownerId: 'owner' } })
  await flushPromises()
  await panel.get('form[aria-label="提交任务"]').trigger('submit')
  await flushPromises()
  expect(JSON.parse(bodies[0]!)).toMatchObject({
    baseVersionId: 'source-version',
    outputMode: 'DOCUMENT',
  })
  expect(bodies[0]).toBe(bodies[1])
  panel.unmount()
  localStorage.clear()
})
it('retains a selected personal agent across uncertain submission and reopening without overriding its model', async () => {
  localStorage.clear()
  const bodies: string[] = []
  const agent = { id: 'agent', name: '写作助手', modelId: 'model', instructions: '整理行动' }
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      if (url.endsWith('/csrf')) return Promise.resolve(new Response('{"token":"proof"}'))
      if (init?.method === 'POST') {
        bodies.push(String(init.body))
        return Promise.reject(new TypeError('lost'))
      }
      const value =
        url === '/api/models'
          ? [{ id: 'model', name: '模型' }]
          : url === '/api/agents/agent'
            ? agent
            : url.startsWith('/api/agents')
              ? { items: [agent], hasMore: false }
              : url.endsWith('/documents')
                ? []
                : { items: [], hasMore: false }
      return Promise.resolve(new Response(JSON.stringify(value)))
    })
  )
  let panel = mount(TaskPanel, {
    props: { conversationId: 'agent-conversation', ownerId: 'owner' },
  })
  await flushPromises()
  await panel.get('select[name="agentId"]').setValue('agent')
  await panel.get('textarea').setValue('写方案')
  await panel.get('form[aria-label="提交任务"]').trigger('submit')
  await flushPromises()
  panel.unmount()
  panel = mount(TaskPanel, { props: { conversationId: 'agent-conversation', ownerId: 'owner' } })
  await flushPromises()
  expect(panel.text()).toContain('写作助手')
  await panel.get('form[aria-label="提交任务"]').trigger('submit')
  await flushPromises()
  expect(bodies).toHaveLength(2)
  expect(bodies[0]).toBe(bodies[1])
  expect(JSON.parse(bodies[0]!)).toMatchObject({ agentId: 'agent' })
  expect(JSON.parse(bodies[0]!)).not.toHaveProperty('modelId')
  panel.unmount()
  localStorage.clear()
})

it('creates a draft conversation once and retries its first task with the same request', async () => {
  const submissions: string[] = []
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith('/csrf')) return new Response('{"token":"proof"}')
    if (url === '/api/models') return new Response('[{"id":"model","name":"模型"}]')
    if (url.endsWith('/documents')) return new Response('[]')
    if (url === '/api/conversations' && init?.method === 'POST')
      return new Response('{"id":"first"}', { status: 201 })
    if (url === '/api/conversations/first/tasks' && init?.method === 'POST') {
      submissions.push(String(init.body))
      if (submissions.length === 1) throw new TypeError('connection lost')
      return new Response('{}', { status: 202 })
    }
    return new Response('{"items":[],"hasMore":false}')
  })
  vi.stubGlobal('fetch', fetch)
  const panel = mount(TaskPanel, { props: { ownerId: 'draft-owner' } })
  await flushPromises()
  expect(fetch.mock.calls.some(([url]) => url === '/api/conversations')).toBe(false)
  await panel.get('#task-model').setValue('model')
  await panel.get('#task-message').setValue('生成项目报告')
  const form = panel.get('form[aria-label="提交任务"]')
  await form.trigger('submit')
  await form.trigger('submit')
  await flushPromises()
  expect(submissions).toHaveLength(1)
  await form.trigger('submit')
  await flushPromises()
  expect(submissions).toHaveLength(2)
  expect(submissions[1]).toBe(submissions[0])
  expect(fetch.mock.calls.filter(([url]) => url === '/api/conversations')).toHaveLength(1)
  expect(panel.emitted('sent')).toEqual([['first']])
  panel.unmount()
})
