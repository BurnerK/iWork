export class ApiError extends Error {
  constructor(public status: number, public code: string) { super(code) }
}
export async function request<T>(path: string, method = 'GET', body?: object | URLSearchParams | File): Promise<T> {
  const headers: Record<string, string> = {}
  if (method !== 'GET') {
    const { token } = await request<{ token: string }>('/api/auth/csrf')
    headers['X-CSRF-TOKEN'] = token
    if (body instanceof File) headers['X-File-Name'] = encodeURIComponent(body.name)
    headers['Content-Type'] = body instanceof File ? 'application/octet-stream' : body instanceof URLSearchParams ? 'application/x-www-form-urlencoded' : 'application/json'
  }
  let response: Response
  try {
    response = await fetch(path, { method, headers, cache: 'no-store', credentials: 'same-origin',
      body: body instanceof File || body instanceof URLSearchParams ? body : body === undefined ? undefined : JSON.stringify(body),
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
    budget_exhausted: '内部可用额度不足，请联系管理员调整额度。',
    model_input_limit: '输入超出本次模型调用的长度限制。',
    model_attempt_limit: '任务已达到模型调用次数上限，请检查已有结果。',
    model_output_limit: '模型输出超过接收上限，任务已停止。',
    model_timeout: '模型调用超时，已请求停止；供应商用量可能仍待核实。',
    task_not_running: '任务已经结束，不能再发起模型调用。',
    invalid_file_name: '文件名无效，请移除路径或控制字符，长度不超过 200 字符。',
    unsupported_material: '仅支持 .txt 和 .md 文件。',
    material_too_large: '每份资料不能超过 2 MiB。',
    incomplete_material: '文件为空或上传不完整，请重新选择。',
    material_storage_unavailable: '原文暂时不可读取或保存，请稍后重试。',
    invalid_task: '请填写模型和不超过 20000 字符的工作要求。',
    execution_unavailable: '任务执行服务尚未就绪，请稍后重试。',
    model_not_authorized: '模型已停用或你没有使用权限，请刷新模型列表。',
    conversation_busy: '当前会话已有任务，请等待结束或取消后再提交。',
    idempotency_conflict: '请求标识已用于不同内容，请先核对已有任务。',
    task_capacity_exceeded: '当前执行容量已满，请稍后重试。',
    execution_failed: '任务执行失败，已保存内容保持不变。',
    process_restarted: '服务重启使任务中断，请明确发起新任务。',
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
