import { afterEach, expect, it, vi } from 'vitest'
import { emptyTaskPage } from './testResponse'
import { request } from './api'

afterEach(() => vi.unstubAllGlobals())

it.each(['null', '<html>proxy error</html>', '{"error":"legacy"}'])(
  'retains the HTTP status for a malformed error response %s',
  async (body) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, { status: 403 })))
    await expect(request('/api/auth/me')).rejects.toMatchObject({
      status: 403,
      code: 'request_failed',
    })
  }
)

it('rejects an invalid nested task response before it reaches the component', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(
      Response.json({
        success: true,
        data: { ...emptyTaskPage, items: [{ id: 'task', status: 43 }] },
        error: null,
      })
    )
  )
  await expect(request('/api/conversations/one/tasks')).rejects.toMatchObject({
    code: 'invalid_response',
  })
})

it.each(['queuedCount', 'queuePaused', 'queuePauseVersion', 'queuedItems', 'confirmedRequestId'])(
  'rejects a task page missing %s',
  async (field) => {
    const data = Object.fromEntries(Object.entries(emptyTaskPage).filter(([key]) => key !== field))
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(Response.json({ success: true, data, error: null }))
    )
    await expect(request('/api/conversations/one/tasks')).rejects.toMatchObject({
      code: 'invalid_response',
    })
  }
)
