import { expect, test } from '@playwright/test'

const emptyPage = { items: [], hasMore: false }
const user = { id: 'alice', displayName: 'Alice', role: 'USER', mustChangePassword: false }
const conversation = {
  id: 'first',
  title: '工作会话',
  updatedAt: '2026-11-18T00:00:00Z',
  projectId: null,
}
const envelope = (data: unknown) => ({ success: true, data, error: null })

for (const confirmByPolling of [false, true]) {
  test(`recovers the first send after a lost response (polling=${confirmByPolling})`, async ({
    page,
  }) => {
    let created = 0
    const submissions: { requestId: string; materialIds: string[] }[] = []
    const accepted: { id: string; status: string; executionState: string; requestId: string }[] = []
    await page.route('**/api/**', async (route) => {
      const request = route.request()
      const path = new URL(request.url()).pathname
      let data: unknown = emptyPage
      let status = 200
      if (path === '/api/auth/me') data = user
      if (path === '/api/auth/csrf') data = { token: 'test-token' }
      if (path === '/api/models') data = [{ id: 'model', name: '测试模型' }]
      if (path === '/api/materials')
        data = { items: [{ id: 'source', name: 'source.md', byteSize: 12 }], hasMore: false }
      if (path === '/api/conversations/first') data = conversation
      if (path === '/api/conversations' && request.method() === 'POST') {
        created++
        data = conversation
        status = 201
      }
      if (path === '/api/conversations/first/tasks' && request.method() === 'GET')
        data = { items: accepted, hasMore: false }
      if (path === '/api/conversations/first/tasks' && request.method() === 'POST') {
        const submitted = request.postDataJSON()
        submissions.push(submitted)
        const task = {
          id: `task-${submissions.length}`,
          status: 'COMPLETED',
          executionState: 'STOPPED',
          requestId: submitted.requestId,
        }
        if (submissions.length === 1) {
          if (confirmByPolling) accepted.push(task)
          return route.abort()
        }
        accepted.push(task)
        data = task
        status = 202
      }
      await route.fulfill({ status, json: envelope(data) })
    })
    await page.goto('/#/conversations')
    await page.locator('#task-model').selectOption('model')
    await page.getByRole('button', { name: '资料' }).click()
    await page.locator('#material-source').check()
    await page.locator('#task-message').fill('整理资料')
    await page.getByRole('button', { name: '提交任务' }).click()
    await expect(page.getByRole('button', { name: '重试确认' })).toBeVisible()

    if (!confirmByPolling) {
      await page.reload()
      await expect(page).toHaveURL(/#\/conversations\/first$/)
      await expect(page.locator('#task-message')).toHaveValue('整理资料')
      await page.getByRole('button', { name: '重试确认' }).click()
      await expect.poll(() => submissions.length).toBe(2)
      expect(submissions[1]).toEqual(submissions[0])
    }

    await expect(page).toHaveURL(/#\/conversations\/first$/)
    await expect(page.locator('#task-message')).toHaveValue('')
    await page.locator('#task-model').selectOption('model')
    await page.locator('#task-message').fill('单独处理后续要求')
    await page.getByRole('button', { name: '提交任务' }).click()
    await expect.poll(() => submissions.length).toBe(confirmByPolling ? 2 : 3)
    expect(created).toBe(1)
    expect(submissions[0]?.materialIds).toEqual(['source'])
    expect(submissions.at(-1)?.materialIds).toEqual([])
    expect(submissions.at(-1)?.requestId).not.toBe(submissions[0]?.requestId)
  })
}

for (const width of [390, 1280]) {
  test(`renders the model form and management titles at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 850 })
    await page.route('**/api/**', async (route) => {
      const path = new URL(route.request().url()).pathname
      const data =
        path === '/api/auth/me'
          ? { ...user, role: 'ADMIN' }
          : path === '/api/admin/models'
            ? []
            : path === '/api/admin/model-settings'
              ? { currency: 'CNY', locked: false }
              : emptyPage
      await route.fulfill({ json: envelope(data) })
    })
    await page.goto('/#/admin-models')
    const title = page.locator('.workspace-topbar h1')
    await expect(title).toHaveText('模型管理')
    const field = page.locator('#model-name')
    await field.fill('审查回归')
    expect((await field.boundingBox())?.width).toBeGreaterThan(50)
    await page.getByRole('button', { name: '保存模型' }).click({ trial: true })
    await page.goto('/#/admin-diagnostics')
    await expect(title).toHaveText('运行诊断')
    await page.goto('/#/admin')
    await expect(title).toHaveText('用户管理')
  })
}

test('opens and revises a task version outside the current document page', async ({ page }) => {
  const oldVersion = {
    id: 'artifact',
    taskId: 'task',
    versionId: 'old-version',
    versionNumber: 1,
    title: '早期成果',
    byteSize: 21,
    markdown: '# 早期正文',
  }
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const data =
      path === '/api/auth/me'
        ? user
        : path === '/api/conversations/first'
          ? conversation
          : path === '/api/models'
            ? [{ id: 'model', name: '测试模型' }]
            : path.endsWith('/tasks')
              ? {
                  items: [
                    {
                      id: 'task',
                      status: 'COMPLETED',
                      executionState: 'STOPPED',
                      requestId: 'old-request',
                      publishedVersion: oldVersion,
                    },
                  ],
                  hasMore: false,
                }
              : path === '/api/document-versions/old-version'
                ? oldVersion
                : emptyPage
    await route.fulfill({ json: envelope(data) })
  })
  await page.goto('/#/conversations/first')
  await page.getByRole('button', { name: '早期成果 · 版本 1' }).click()
  const preview = page.getByRole('dialog', { name: '文档预览' })
  await expect(preview.getByText('早期正文')).toBeVisible()
  await expect(preview.getByRole('link', { name: '下载 .md' })).toHaveAttribute(
    'href',
    '/api/document-versions/old-version/content'
  )
  await preview.getByRole('button', { name: '基于此版本修改' }).click()
  await expect(page.getByText('基于《早期成果》版本 1 修改。')).toBeVisible()
})
