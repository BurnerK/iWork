import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import ParsingPanel from './ParsingPanel.vue'
afterEach(() => vi.unstubAllGlobals())
it('keeps unconfirmed cleanup visible and renders parsed text as data', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) =>
      Promise.resolve(
        new Response(
          JSON.stringify(
            url.includes('?offset=')
              ? { text: '<script>unsafe()</script>', nextOffset: 24, hasMore: false }
              : [
                  {
                    id: 'parse',
                    name: '资料.md',
                    status: 'COMPLETED',
                    cleanup: 'UNKNOWN',
                    characters: 24,
                    errorCode: null,
                  },
                ]
          )
        )
      )
    )
  )
  const panel = mount(ParsingPanel, { props: { taskId: 'task' } })
  await flushPromises()
  expect(panel.text()).toContain('处理容器清理尚未确认')
  expect(panel.text()).toContain('不是正式文档成果')
  await panel.findAll('button')[0]!.trigger('click')
  await flushPromises()
  expect(panel.text()).toContain('<script>unsafe()</script>')
  expect(panel.find('script').exists()).toBe(false)
  panel.unmount()
})
