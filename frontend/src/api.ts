export class ApiError extends Error {
  constructor(public status: number, public code: string) { super(code) }
}
export async function request<T>(path: string, method = 'GET', body?: object | URLSearchParams): Promise<T> {
  const headers: Record<string, string> = {}
  if (method !== 'GET') {
    const { token } = await request<{ token: string }>('/api/auth/csrf')
    headers['X-CSRF-TOKEN'] = token
    headers['Content-Type'] = body instanceof URLSearchParams ? 'application/x-www-form-urlencoded' : 'application/json'
  }
  let response: Response
  try {
    response = await fetch(path, { method, headers, cache: 'no-store', credentials: 'same-origin',
      body: body instanceof URLSearchParams ? body : body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(15000) })
  } catch { throw new ApiError(503, 'service_unavailable') }
  if (response.status === 204) return undefined as T
  let data: any
  try { data = await response.json() } catch { throw new ApiError(503, 'service_unavailable') }
  if (!response.ok) throw new ApiError(response.status, data.error || 'request_failed')
  return data as T
}
export function errorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return '操作失败，请重试。'
  const messages: Record<string, string> = {
    invalid_model_url: '服务地址须为 HTTPS，不能包含账号、查询参数或片段。',
    invalid_amount: '金额须为非负十进制数，最多 12 位整数和 6 位小数。',
    invalid_currency: '请填写有效的三位币种代码，例如 CNY。',
    currency_locked: '已经建立模型或预算，不能修改实例币种。',
    invalid_credential_reference: '凭据引用须以 IWORK_MODEL_KEY_ 开头，只含大写字母、数字或下划线。',
    credential_not_configured: '该凭据引用尚未在服务端配置，请联系部署者。',
    invalid_credentials: '用户名或密码不正确，或账号不可用。连续 5 次失败后请等待 15 分钟。',
    password_change_required: '请先修改临时密码。', invalid_password: '密码至少 12 个字符，且不超过 72 个 UTF-8 字节。',
    password_unchanged: '新密码必须与当前密码不同。',
    wrong_current_password: '当前密码不正确。', username_exists: '用户名已存在，请使用其他用户名。',
    invalid_username: '用户名须为 3–64 位字母、数字、点、下划线或连字符，并以字母或数字开头。',
    invalid_display_name: '请填写不超过 100 个字符的显示名称。',
  }
  return messages[error.code] || ({ 401: '登录已失效，请重新登录。', 403: '无法执行此操作，请刷新页面后重试。',
    404: '会话不存在或你没有访问权限。', 503: '服务暂不可用，请稍后重试。' }[error.status] || '提交内容无效，请检查后重试。')
}
