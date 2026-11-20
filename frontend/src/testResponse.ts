export function apiResponse(body: string | null, init?: ResponseInit): Response {
  if (body === null) return new Response(null, init)
  const data = JSON.parse(body)
  return Response.json(
    (init?.status ?? 200) < 400
      ? { success: true, data, error: null }
      : { success: false, data: null, error: data.error },
    init
  )
}

export function agUiReceipt(id: string, requestId: string): Response {
  const frames = [
    { type: 'RUN_STARTED', runId: requestId },
    { type: 'CUSTOM', name: 'iwork.task', value: { id, requestId } },
  ]
  return new Response(frames.map((event) => `data: ${JSON.stringify(event)}\r\n\r\n`).join(''), {
    headers: { 'Content-Type': 'text/event-stream' },
  })
}
