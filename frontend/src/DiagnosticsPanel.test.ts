import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import DiagnosticsPanel from './DiagnosticsPanel.vue'
afterEach(() => vi.unstubAllGlobals())
it('keeps supplier uncertainty and cleanup retry separate after business cancellation', async () => {
  const diagnostic = {
    task: { id: 'task', ownerId: 'owner', conversationId: 'cid', modelId: 'model', status: 'CANCELLED', executionState: 'STOPPED', errorCode: null, createdAt: '2026-11-17T00:00:00Z', updatedAt: '2026-11-17T00:01:00Z', contextState: 'SNAPSHOT_REJECTED', summaryState: 'FALLBACK' },
    calls: [{ id: 'call', purpose: 'SUMMARY', status: 'CLOSED', source: 'PARTIAL_ESTIMATE', providerState: 'UNKNOWN', inputTokens: 100, outputTokens: null, charged: '0.1', currency: 'CNY' }],
    parses: [{ id: 'parse', sandboxJobId: 'job', materialId: 'material', status: 'FAILED', cleanup: 'UNKNOWN', cleanupAttempts: 2, errorCode: 'sandbox_unavailable' }], versions: [],
  }
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify(diagnostic)))))
  const panel = mount(DiagnosticsPanel, { props: { taskId: 'task' } }); await flushPromises()
  expect(panel.text()).toContain('业务已取消')
  expect(panel.text()).toContain('本地执行已结束')
  expect(panel.text()).toContain('未知，不能确认已停止')
  expect(panel.text()).toContain('处理停止未知，清理待重试')
  expect(panel.text()).toContain('供应商用量待核实')
  expect(panel.text()).toContain('上下文摘要')
  expect(panel.text()).toContain('快照不一致')
  diagnostic.parses[0]!.cleanup = 'CONFIRMED'
  await panel.get('button').trigger('click'); await flushPromises()
  expect(panel.text()).toContain('处理容器已确认清理')
  expect(panel.text()).toContain('未知，不能确认已停止')
  panel.unmount()
})
