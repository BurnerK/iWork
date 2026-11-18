import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from './App.vue'
import { makeRouter } from './router'

afterEach(() => vi.unstubAllGlobals())
it('requires a temporary-password user to change password before opening conversations', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          id: 'one',
          username: 'alice',
          displayName: 'Alice',
          role: 'USER',
          mustChangePassword: true,
        })
      )
    )
  )
  const router = makeRouter(createMemoryHistory())
  await router.push('/conversations')
  const page = mount(App, { global: { plugins: [router] } })
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/password')
  expect(page.text()).toContain('首次登录，请设置新密码')
  expect(page.find('input[autocomplete="new-password"]').exists()).toBe(true)
  expect(page.find('a[href="/admin"]').exists()).toBe(false)
})

it('shows a safe service error and allows retry without claiming the user is logged out', async () => {
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(
      new Response('{"error":"service_unavailable","detail":"jdbc:private"}', { status: 503 })
    )
    .mockResolvedValueOnce(new Response('{"error":"authentication_required"}', { status: 401 }))
  vi.stubGlobal('fetch', fetch)
  const router = makeRouter(createMemoryHistory())
  await router.push('/conversations')
  const page = mount(App, { global: { plugins: [router] } })
  await flushPromises()
  expect(page.get('[role="alert"]').text()).toContain('服务暂不可用')
  expect(page.text()).not.toContain('jdbc:private')
  await page.get('button').trigger('click')
  await flushPromises()
  expect(page.get('h1').text()).toBe('登录 iWork')
})

it('submits login with CSRF and prevents duplicate submissions while waiting', async () => {
  let resolveLogin!: (response: Response) => void
  const fetch = vi.fn((url: string) => {
    if (url.endsWith('/me'))
      return Promise.resolve(new Response('{"error":"authentication_required"}', { status: 401 }))
    if (url.endsWith('/csrf')) return Promise.resolve(new Response('{"token":"csrf-proof"}'))
    if (url.endsWith('/login'))
      return new Promise<Response>((resolve) => {
        resolveLogin = resolve
      })
    return Promise.resolve(new Response('{"items":[],"hasMore":false}'))
  })
  vi.stubGlobal('fetch', fetch)
  const router = makeRouter(createMemoryHistory())
  await router.push('/login')
  const page = mount(App, { global: { plugins: [router] } })
  await flushPromises()
  await page.get('#username').setValue('alice')
  await page.get('#password').setValue('Alice-password-11')
  await page.get('form').trigger('submit')
  await flushPromises()
  expect(page.get('button[type="submit"]').attributes()).toHaveProperty('disabled')
  expect(fetch).toHaveBeenCalledWith(
    '/api/auth/login',
    expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'csrf-proof' }),
    })
  )
  resolveLogin(
    new Response(
      '{"id":"one","username":"alice","displayName":"Alice","role":"USER","mustChangePassword":false}'
    )
  )
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/conversations')
  expect(page.text()).toContain('还没有会话')
})

it('opens a direct conversation link with its task composer', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) =>
      Promise.resolve(
        new Response(
          JSON.stringify(
            url.endsWith('/me')
              ? { id: 'one', displayName: 'Alice', role: 'USER', mustChangePassword: false }
              : url.endsWith('/api/models')
                ? []
                : url.includes('/tasks') || url.includes('/messages')
                  ? { items: [], hasMore: false }
                  : url.includes('?')
                    ? {
                        items: [{ id: 'abc', title: '新会话', updatedAt: '2026-11-16T00:00:00Z' }],
                        hasMore: false,
                      }
                    : { id: 'abc', title: '新会话', updatedAt: '2026-11-16T00:00:00Z' }
          )
        )
      )
    )
  )
  const router = makeRouter(createMemoryHistory())
  await router.push('/conversations/abc')
  const page = mount(App, { global: { plugins: [router] } })
  await flushPromises()
  expect(page.get('article').text()).toContain('暂时没有消息')
  expect(page.find('textarea').exists()).toBe(true)
  page.unmount()
  expect(page.text()).not.toContain('用户管理')
})

it('clears a generated temporary password when leaving user management', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) =>
      Promise.resolve(
        new Response(
          JSON.stringify(
            url.endsWith('/me')
              ? { id: 'admin', displayName: '管理员', role: 'ADMIN', mustChangePassword: false }
              : url.endsWith('/csrf')
                ? { token: 'proof' }
                : init?.method === 'POST'
                  ? { user: { username: 'alice' }, temporaryPassword: 'one-time-secret' }
                  : { items: [], hasMore: false }
          )
        )
      )
    )
  )
  const router = makeRouter(createMemoryHistory())
  await router.push('/admin')
  const page = mount(App, { global: { plugins: [router] } })
  await flushPromises()
  await page.get('#new-username').setValue('alice')
  await page.get('#display-name').setValue('Alice')
  await page.get('form[aria-label="创建用户"]').trigger('submit')
  await flushPromises()
  expect(page.get('code').text()).toBe('one-time-secret')
  await router.push('/conversations')
  await flushPromises()
  await router.push('/admin')
  await flushPromises()
  expect(page.find('code').exists()).toBe(false)
})

it('does not expose the previous account conversations after expiry, another login, and a service failure', async () => {
  let account = 'A',
    expired = false
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      let data: object = { token: 'proof' },
        status = 200
      if (url.endsWith('/me'))
        data = { id: 'A', displayName: 'A', role: 'USER', mustChangePassword: false }
      else if (url.endsWith('/models')) data = [{ id: 'model', name: 'Model' }]
      else if (url.endsWith('/agents?page=0')) data = { items: [], hasMore: false }
      else if (url.endsWith('/login')) {
        account = 'B'
        data = { id: 'B', displayName: 'B', role: 'USER', mustChangePassword: false }
      } else if (url.includes('/conversations')) {
        if (init?.method === 'POST') {
          expired = true
          status = 401
          data = { error: 'session_expired' }
        } else if (expired && account === 'B') {
          status = 503
          data = { error: 'service_unavailable' }
        } else
          data = {
            items: [
              { id: 'private-A', title: 'A confidential title', updatedAt: '2026-11-16T00:00:00Z' },
            ],
            hasMore: false,
          }
      }
      return Promise.resolve(new Response(JSON.stringify(data), { status }))
    })
  )
  const router = makeRouter(createMemoryHistory())
  await router.push('/conversations')
  const page = mount(App, { global: { plugins: [router] } })
  await flushPromises()
  expect(page.text()).toContain('A confidential title')
  await page.get('#task-model').setValue('model')
  await page.get('#task-message').setValue('整理资料')
  await page.get('form[aria-label="提交任务"]').trigger('submit')
  await flushPromises()
  await page.get('#username').setValue('B')
  await page.get('#password').setValue('B-password-11')
  await page.get('form').trigger('submit')
  await flushPromises()
  expect(page.text()).toContain('服务暂不可用')
  expect(page.text()).not.toContain('A confidential title')
})

it('ignores a conversation response that arrives after logout', async () => {
  let finishList!: (response: Response) => void
  let firstList = true
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      if (url.endsWith('/me') || url.endsWith('/login'))
        return Promise.resolve(
          new Response('{"id":"one","displayName":"User","role":"USER","mustChangePassword":false}')
        )
      if (url.endsWith('/csrf')) return Promise.resolve(new Response('{"token":"proof"}'))
      if (url.endsWith('/logout')) return Promise.resolve(new Response(null, { status: 204 }))
      if (url.startsWith('/api/projects'))
        return Promise.resolve(new Response('{"items":[],"hasMore":false}'))
      if (firstList) {
        firstList = false
        return new Promise<Response>((resolve) => {
          finishList = resolve
        })
      }
      return Promise.resolve(new Response('{"error":"service_unavailable"}', { status: 503 }))
    })
  )
  const router = makeRouter(createMemoryHistory())
  await router.push('/conversations')
  const page = mount(App, { global: { plugins: [router] } })
  await flushPromises()
  await page
    .findAll('button')
    .find((button) => button.text() === '退出登录')!
    .trigger('click')
  await flushPromises()
  finishList(
    new Response(
      '{"items":[{"id":"late","title":"Late private title","updatedAt":"2026-11-16T00:00:00Z"}],"hasMore":false}'
    )
  )
  await flushPromises()
  await page.get('#username').setValue('another')
  await page.get('#password').setValue('Another-password-11')
  await page.get('form').trigger('submit')
  await flushPromises()
  expect(page.text()).not.toContain('Late private title')
})

it('requires an explicit login after password change', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) =>
      Promise.resolve(
        url.endsWith('/password')
          ? new Response(null, { status: 204 })
          : new Response(
              JSON.stringify(
                url.endsWith('/csrf')
                  ? { token: 'proof' }
                  : { id: 'one', displayName: 'User', role: 'USER', mustChangePassword: true }
              )
            )
      )
    )
  )
  const router = makeRouter(createMemoryHistory())
  await router.push('/password')
  const page = mount(App, { global: { plugins: [router] } })
  await flushPromises()
  await page.get('#current-password').setValue('Temporary-password-11')
  await page.get('#new-password').setValue('Changed-password-11')
  await page.get('form').trigger('submit')
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/login')
  expect(page.get('[role="status"]').text()).toContain('使用新密码重新登录')
  expect(page.find('nav[aria-label="主导航"]').exists()).toBe(false)
})

it('preserves an unsent task draft and selected material when moving its conversation', async () => {
  let projectId: string | null = null
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      if (init?.method === 'PUT') {
        projectId = JSON.parse(String(init.body)).projectId
        return Promise.resolve(new Response(null, { status: 204 }))
      }
      const data = url.endsWith('/csrf')
        ? { token: 'proof' }
        : url.endsWith('/me')
          ? { id: 'one', displayName: 'Alice', role: 'USER', mustChangePassword: false }
          : url === '/api/models'
            ? [{ id: 'model', name: 'Model' }]
            : url.startsWith('/api/projects?')
              ? { items: [{ id: 'project', name: '蓝鲸' }], hasMore: false }
              : url === '/api/conversations/abc'
                ? { id: 'abc', title: '会话', projectId }
                : url === '/api/materials?page=0'
                  ? { items: [{ id: 'material', name: 'source.md', byteSize: 12 }], hasMore: false }
                  : url.endsWith('/documents')
                    ? []
                    : { items: [], hasMore: false }
      return Promise.resolve(new Response(JSON.stringify(data)))
    })
  )
  const router = makeRouter(createMemoryHistory())
  await router.push('/conversations/abc')
  const page = mount(App, { global: { plugins: [router] } })
  await flushPromises()
  await page.get('#task-message').setValue('请保留这个未提交要求')
  await page.get('#task-model').setValue('model')
  await page.get('#material-material').setValue(true)
  await page.get('#conversation-project').setValue('project')
  await page.get('[aria-label="会话所属项目"]').trigger('submit')
  await flushPromises()
  expect((page.get('#task-message').element as HTMLTextAreaElement).value).toBe(
    '请保留这个未提交要求'
  )
  expect((page.get('#task-model').element as HTMLSelectElement).value).toBe('model')
  expect((page.get('#material-material').element as HTMLInputElement).checked).toBe(true)
  expect(page.find('[aria-label="项目引用资料"]').exists()).toBe(true)
  page.unmount()
})
