import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import UsagePanel from './UsagePanel.vue'
afterEach(() => vi.unstubAllGlobals())
it('distinguishes an internal estimate from unknown supplier usage', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(() =>
      Promise.resolve(
        new Response(
          JSON.stringify([
            {
              id: 'call',
              purpose: 'MAIN',
              status: 'CLOSED',
              source: 'LIMIT_ESTIMATE',
              inputTokens: 100,
              outputTokens: null,
              inputEstimate: 500,
              observedOutputBytes: 0,
              charged: '0.004196',
              reservation: '0.004596',
              currency: 'CNY',
              startedAt: '2026-11-17T01:00:00Z',
              providerResponseId: null,
            },
          ])
        )
      )
    )
  )
  const panel = mount(UsagePanel, { props: { taskId: 'task', running: false } })
  await flushPromises()
  expect(panel.text()).toContain('供应商用量待核实')
  expect(panel.text()).toContain('0.004196 CNY')
  expect(panel.text()).toContain('输出：未回报')
  panel.unmount()
})
