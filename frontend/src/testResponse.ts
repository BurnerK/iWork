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
