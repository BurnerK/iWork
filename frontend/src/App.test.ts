import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from './App.vue'
import { makeRouter } from './router'

afterEach(() => vi.unstubAllGlobals())
it('requires a temporary-password user to change password before opening conversations', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: 'one', username: 'alice', displayName: 'Alice', role: 'USER', mustChangePassword: true }))))
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
  const fetch = vi.fn().mockResolvedValueOnce(new Response('{"error":"service_unavailable","detail":"jdbc:private"}', { status: 503 }))
    .mockResolvedValueOnce(new Response('{"error":"authentication_required"}', { status: 401 }))
  vi.stubGlobal('fetch', fetch)
  const router = makeRouter(createMemoryHistory()); await router.push('/conversations')
  const page = mount(App, { global: { plugins: [router] } })
  await flushPromises()
  expect(page.get('[role="alert"]').text()).toContain('服务暂不可用')
  expect(page.text()).not.toContain('jdbc:private')
  await page.get('button').trigger('click'); await flushPromises()
  expect(page.get('h1').text()).toBe('登录 iWork')
})

it('submits login with CSRF and prevents duplicate submissions while waiting', async () => {
  let resolveLogin!: (response: Response) => void
  const fetch = vi.fn((url: string) => {
    if (url.endsWith('/me')) return Promise.resolve(new Response('{"error":"authentication_required"}', { status: 401 }))
    if (url.endsWith('/csrf')) return Promise.resolve(new Response('{"token":"csrf-proof"}'))
    if (url.endsWith('/login')) return new Promise<Response>(resolve => { resolveLogin = resolve })
    return Promise.resolve(new Response('{"items":[],"hasMore":false}'))
  })
  vi.stubGlobal('fetch', fetch)
  const router = makeRouter(createMemoryHistory()); await router.push('/login')
  const page = mount(App, { global: { plugins: [router] } }); await flushPromises()
  await page.get('#username').setValue('alice'); await page.get('#password').setValue('Alice-password-11')
  await page.get('form').trigger('submit'); await flushPromises()
  expect(page.get('button[type="submit"]').attributes()).toHaveProperty('disabled')
  expect(fetch).toHaveBeenCalledWith('/api/auth/login', expect.objectContaining({ method: 'POST', headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'csrf-proof' }) }))
  resolveLogin(new Response('{"id":"one","username":"alice","displayName":"Alice","role":"USER","mustChangePassword":false}'))
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/conversations')
  expect(page.text()).toContain('还没有会话')
})

it('opens a direct conversation link and never renders message input', async () => {
  vi.stubGlobal('fetch', vi.fn((url: string) => Promise.resolve(new Response(JSON.stringify(
    url.endsWith('/me') ? { id: 'one', displayName: 'Alice', role: 'USER', mustChangePassword: false }
      : url.includes('?') ? { items: [{ id: 'abc', title: '新会话', updatedAt: '2026-11-16T00:00:00Z' }], hasMore: false }
      : { id: 'abc', title: '新会话', updatedAt: '2026-11-16T00:00:00Z' }
  )))))
  const router = makeRouter(createMemoryHistory()); await router.push('/conversations/abc')
  const page = mount(App, { global: { plugins: [router] } }); await flushPromises()
  expect(page.get('article').text()).toContain('暂时没有消息')
  expect(page.find('textarea').exists()).toBe(false)
  expect(page.text()).not.toContain('用户管理')
})

it('clears a generated temporary password when leaving user management', async () => {
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => Promise.resolve(new Response(JSON.stringify(
    url.endsWith('/me') ? { id: 'admin', displayName: '管理员', role: 'ADMIN', mustChangePassword: false }
      : url.endsWith('/csrf') ? { token: 'proof' }
      : init?.method === 'POST' ? { user: { username: 'alice' }, temporaryPassword: 'one-time-secret' }
      : { items: [], hasMore: false }
  )))))
  const router = makeRouter(createMemoryHistory()); await router.push('/admin')
  const page = mount(App, { global: { plugins: [router] } }); await flushPromises()
  await page.get('#new-username').setValue('alice'); await page.get('#display-name').setValue('Alice')
  await page.get('form').trigger('submit'); await flushPromises()
  expect(page.get('code').text()).toBe('one-time-secret')
  await router.push('/conversations'); await flushPromises()
  await router.push('/admin'); await flushPromises()
  expect(page.find('code').exists()).toBe(false)
})

it('does not expose the previous account conversations after expiry, another login, and a service failure', async () => {
  let account = 'A', expired = false
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    let data: object = { token: 'proof' }, status = 200
    if (url.endsWith('/me')) data = { id: 'A', displayName: 'A', role: 'USER', mustChangePassword: false }
    else if (url.endsWith('/login')) { account = 'B'; data = { id: 'B', displayName: 'B', role: 'USER', mustChangePassword: false } }
    else if (url.includes('/conversations')) {
      if (init?.method === 'POST') { expired = true; status = 401; data = { error: 'session_expired' } }
      else if (expired && account === 'B') { status = 503; data = { error: 'service_unavailable' } }
      else data = { items: [{ id: 'private-A', title: 'A confidential title', updatedAt: '2026-11-16T00:00:00Z' }], hasMore: false }
    }
    return Promise.resolve(new Response(JSON.stringify(data), { status }))
  }))
  const router = makeRouter(createMemoryHistory()); await router.push('/conversations')
  const page = mount(App, { global: { plugins: [router] } }); await flushPromises()
  expect(page.text()).toContain('A confidential title')
  await page.findAll('button').find(button => button.text() === '新建会话')!.trigger('click'); await flushPromises()
  await page.get('#username').setValue('B'); await page.get('#password').setValue('B-password-11')
  await page.get('form').trigger('submit'); await flushPromises()
  expect(page.text()).toContain('服务暂不可用')
  expect(page.text()).not.toContain('A confidential title')
})

it('ignores a conversation response that arrives after logout', async () => {
  let finishList!: (response: Response) => void
  let firstList = true
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (url.endsWith('/me') || url.endsWith('/login')) return Promise.resolve(new Response('{"id":"one","displayName":"User","role":"USER","mustChangePassword":false}'))
    if (url.endsWith('/csrf')) return Promise.resolve(new Response('{"token":"proof"}'))
    if (url.endsWith('/logout')) return Promise.resolve(new Response(null, { status: 204 }))
    if (firstList) { firstList = false; return new Promise<Response>(resolve => { finishList = resolve }) }
    return Promise.resolve(new Response('{"error":"service_unavailable"}', { status: 503 }))
  }))
  const router = makeRouter(createMemoryHistory()); await router.push('/conversations')
  const page = mount(App, { global: { plugins: [router] } }); await flushPromises()
  await page.findAll('button').find(button => button.text() === '退出登录')!.trigger('click'); await flushPromises()
  finishList(new Response('{"items":[{"id":"late","title":"Late private title","updatedAt":"2026-11-16T00:00:00Z"}],"hasMore":false}'))
  await flushPromises()
  await page.get('#username').setValue('another'); await page.get('#password').setValue('Another-password-11')
  await page.get('form').trigger('submit'); await flushPromises()
  expect(page.text()).not.toContain('Late private title')
})
