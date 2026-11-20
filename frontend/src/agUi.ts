import { z } from 'zod'
import { ApiError, fetchApi } from './api'

const taskReceipt = z.object({
  type: z.literal('CUSTOM'),
  name: z.literal('iwork.task'),
  value: z.object({ id: z.string().min(1), requestId: z.string() }),
})

export async function submitRun(
  threadId: string,
  { requestId, message, ...options }: { requestId: string; message: string }
) {
  const response = await fetchApi(
    `/api/conversations/${encodeURIComponent(threadId)}/ag-ui`,
    'POST',
    {
      threadId,
      runId: requestId,
      messages: [{ id: requestId, role: 'user', content: message }],
      state: {},
      tools: [],
      context: [],
      forwardedProps: options,
    },
    'text/event-stream'
  )
  if (!response.headers.get('content-type')?.startsWith('text/event-stream') || !response.body) {
    throw new ApiError(503, 'invalid_response')
  }
  // After the durable receipt, the workbench observes by task ID independently of the submit stream.
  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader()
  let pending = ''
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      pending += value
      for (let boundary; (boundary = /\r?\n\r?\n/.exec(pending));) {
        const frame = pending.slice(0, boundary.index)
        pending = pending.slice(boundary.index + boundary[0].length)
        const data = frame
          .split(/\r?\n/)
          .filter((line) => line.startsWith('data:'))
          .map((line) => line.slice(5).replace(/^ /, ''))
          .join('\n')
        if (!data) continue
        const receipt = taskReceipt.safeParse(JSON.parse(data))
        if (receipt.success && receipt.data.value.requestId === requestId) return
      }
      if (pending.length > 65536) break
    }
  } catch {
    throw new ApiError(503, 'invalid_response')
  } finally {
    void reader.cancel().catch(() => undefined)
  }
  throw new ApiError(503, 'invalid_response')
}
