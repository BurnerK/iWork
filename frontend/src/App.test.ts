import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import App from './App.vue'
import { nextTick } from 'vue'

afterEach(() => vi.unstubAllGlobals())

it('shows availability only after the server confirms readiness', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"status":"UP"}')))
  const page = mount(App)
  await flushPromises()
  expect(page.get('[role="status"]').text()).toBe('服务已就绪')
})

it.each([
  new Response('{"status":"DOWN","details":"private diagnostic"}', { status: 503 }),
  new Response('{"status":"DOWN"}'),
  new Response('not json'),
])('does not claim readiness or expose diagnostics for an invalid response', async (response) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))
  const page = mount(App)
  await flushPromises()
  expect(page.get('[role="status"]').text()).toBe('服务暂不可用，请稍后重试')
  expect(page.text()).not.toContain('private diagnostic')
})

it('allows retry after a network failure and disables duplicate checks while pending', async () => {
  const fetch = vi.fn().mockRejectedValueOnce(new TypeError('network failure'))
    .mockResolvedValueOnce(new Response('{"status":"UP"}'))
  vi.stubGlobal('fetch', fetch)
  const page = mount(App)
  await nextTick()
  expect(page.get('button').attributes()).toHaveProperty('disabled')
  await flushPromises()
  expect(page.get('[role="status"]').text()).toBe('服务暂不可用，请稍后重试')
  await page.get('button').trigger('click')
  await flushPromises()
  expect(page.get('[role="status"]').text()).toBe('服务已就绪')
  expect(page.get('button').attributes()).not.toHaveProperty('disabled')
})
