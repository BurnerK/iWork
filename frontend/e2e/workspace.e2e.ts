import { expect, test, type Page } from '@playwright/test'

const emptyPage = { items: [], hasMore: false }
const user = { id: 'alice', displayName: 'Alice', role: 'USER', mustChangePassword: false }
const conversation = {
  id: 'first',
  title: '工作会话',
  updatedAt: '2026-11-18T00:00:00Z',
  projectId: null,
}
const envelope = (data: unknown) => ({ success: true, data, error: null })

async function expectComposerLayout(page: Page) {
  const [card, message, tools, target, send] = await Promise.all(
    [
      '.task-composer',
      '#task-message',
      '.composer-material-toggle',
      '#task-model',
      '.composer-send',
    ].map(async (selector) => (await page.locator(selector).boundingBox())!)
  )
  expect(message.y + message.height).toBeLessThanOrEqual(tools.y)
  expect(send.y).toBe(tools.y)
  expect(tools.x + tools.width).toBeLessThanOrEqual(target.x)
  expect(target.x + target.width).toBeLessThanOrEqual(send.x)
  expect(send.x + send.width).toBeLessThanOrEqual(card.x + card.width)
  expect(send.x + send.width).toBeLessThanOrEqual(page.viewportSize()!.width)
  expect(send.width).toBe(send.height)
}

test('keeps the composer appearance stable while a reply is running', async ({ page }) => {
  let running = false
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    let data: unknown = emptyPage
    if (path === '/api/auth/me') data = user
    if (path === '/api/models') data = [{ id: 'model', name: '测试模型' }]
    if (path === '/api/conversations/first') data = conversation
    if (path === '/api/conversations/first/tasks' && running) {
      data = {
        items: [
          { id: 'running', requestId: 'request', status: 'RUNNING', executionState: 'RUNNING' },
        ],
        hasMore: false,
      }
    }
    await route.fulfill({ json: envelope(data) })
  })
  await page.goto('/#/conversations/first')
  const composer = page.locator('.task-composer')
  const message = page.locator('#task-message')
  const model = page.locator('#task-model')
  await model.selectOption('model')
  await message.fill('后续要求')
  const send = page.getByRole('button', { name: '提交任务' })
  await expect(send).toBeEnabled()
  const initialHeight = (await composer.boundingBox())?.height

  running = true
  await page.getByRole('button', { name: '刷新任务' }).click()
  await expect(page.getByRole('status').filter({ hasText: '运行中' })).toBeVisible()
  await expect(send).toBeDisabled()
  for (const field of [message, model]) {
    await expect(field).toBeDisabled()
    await expect(field).toHaveCSS('background-color', 'rgb(255, 255, 255)')
    await expect(field).toHaveCSS('color', 'rgb(0, 0, 0)')
  }
  expect((await composer.boundingBox())?.height).toBe(initialHeight)
})

for (const confirmByPolling of [false, true]) {
  test(`recovers the first send after a lost response (polling=${confirmByPolling})`, async ({
    page,
  }) => {
    let created = 0
    const submissions: {
      requestId: string
      materialIds: string[]
      outputMode: string
      message: string
      modelId: string
    }[] = []
    const accepted: { id: string; status: string; executionState: string; requestId: string }[] = []
    let showAccepted = confirmByPolling
    const replies = [{ id: 1, taskId: 'task-1', role: 'assistant', content: '资料已整理。' }]
    await page.route('**/api/**', async (route) => {
      const request = route.request()
      const { pathname: path, searchParams } = new URL(request.url())
      let data: unknown = emptyPage
      let status = 200
      if (path === '/api/auth/me') data = user
      if (path === '/api/auth/csrf') data = { token: 'test-token' }
      if (path === '/api/models') data = [{ id: 'model', name: '测试模型' }]
      if (path === '/api/materials' && submissions.length === 0)
        data = { items: [{ id: 'source', name: 'source.md', byteSize: 12 }], hasMore: false }
      if (path === '/api/conversations/first') data = conversation
      if (path === '/api/conversations' && request.method() === 'POST') {
        created++
        data = conversation
        status = 201
      }
      if (path === '/api/conversations/first/tasks' && request.method() === 'GET')
        data = { items: showAccepted ? accepted : [], hasMore: false }
      if (path === '/api/conversations/first/messages')
        data = {
          items: showAccepted && searchParams.get('after') === '0' ? replies : [],
          hasMore: false,
        }
      if (path === '/api/conversations/first/tasks' && request.method() === 'POST') {
        const submitted = request.postDataJSON()
        submissions.push(submitted)
        const existing = accepted.find((task) => task.requestId === submitted.requestId)
        const task = existing ?? {
          id: `task-${submissions.length}`,
          status: 'COMPLETED',
          executionState: 'STOPPED',
          requestId: submitted.requestId,
        }
        if (!existing) accepted.push(task)
        if (submissions.length === 1) {
          return route.abort()
        }
        showAccepted = true
        data = task
        status = 202
      }
      await route.fulfill({ status, json: envelope(data) })
    })
    await page.goto('/#/conversations')
    await page.locator('#task-model').selectOption('model')
    await expectComposerLayout(page)
    const tools = page.getByRole('button', { name: /^资料/ })
    const output = page.locator('#task-output')
    await expect(output).toBeHidden()
    await tools.press('Enter')
    await expect(output).toBeVisible()
    await output.selectOption('DOCUMENT')
    await page.locator('#material-source').check()
    if (!confirmByPolling) {
      const refresh = page.getByRole('button', { name: '刷新资料', exact: true })
      await refresh.hover()
      await expect(refresh).toHaveCSS('background-color', 'rgb(242, 242, 242)')
      await expect(refresh).toHaveCSS('color', 'rgb(33, 33, 33)')
    }
    await page.keyboard.press('Escape')
    await expect(tools).toBeFocused()
    await expect(output).toBeHidden()
    await expect(page.getByRole('list', { name: '已选资料' }).getByText('source.md')).toBeVisible()
    const message = page.locator('#task-message')
    await message.hover()
    const placeholderStyle = () =>
      message.evaluate((field) => {
        const { color, opacity } = getComputedStyle(field, '::placeholder')
        return { color, opacity }
      })
    expect(await placeholderStyle()).toEqual({ color: 'rgb(33, 33, 33)', opacity: '0.62' })
    await message.click()
    expect(await placeholderStyle()).toEqual({ color: 'rgb(33, 33, 33)', opacity: '0.62' })
    await expect(message).toHaveCSS('resize', 'none')
    const emptyHeight = await message.evaluate((field) => field.offsetHeight)
    await message.fill('第一行\n第二行\n第三行\n第四行')
    expect(await message.evaluate((field) => field.offsetHeight)).toBeGreaterThan(emptyHeight)
    await message.fill('多行内容\n'.repeat(20))
    expect(await message.evaluate((field) => field.scrollHeight)).toBeGreaterThan(
      await message.evaluate((field) => field.clientHeight)
    )
    await message.fill('整理资料')
    expect(await message.evaluate((field) => field.offsetHeight)).toBe(emptyHeight)
    await expect(message).toHaveCSS('outline-style', 'none')
    await expect(message).toHaveCSS('border-top-color', 'rgba(0, 0, 0, 0)')
    const send = page.getByRole('button', { name: '提交任务' })
    if (!confirmByPolling) {
      for (const control of [
        page.locator('.workspace-rail > a').first(),
        page.getByRole('button', { name: '切换导航' }),
        tools,
        send,
      ]) {
        await control.hover()
        await expect(control).toHaveCSS('background-color', 'rgb(242, 242, 242)')
        await expect(control).toHaveCSS('color', 'rgb(33, 33, 33)')
      }
      await expect(send.locator('img')).toHaveCSS('filter', 'none')
    }
    await send.click()
    await expect(page.getByRole('button', { name: '重试确认' })).toBeVisible()

    if (!confirmByPolling) {
      await page.reload()
      await expect(page).toHaveURL(/#\/conversations\/first$/)
      await expect(page.locator('#task-message')).toHaveValue('整理资料')
      await expect(
        page.getByRole('list', { name: '已选资料' }).getByText('source.md')
      ).toBeVisible()
      await page.getByRole('button', { name: '重试确认' }).click()
      await expect.poll(() => submissions.length).toBe(2)
      expect(submissions[1]).toEqual(submissions[0])
    }

    await expect(page).toHaveURL(/#\/conversations\/first$/)
    await expect(page.getByText('资料已整理。')).toBeVisible()
    expect(accepted).toHaveLength(1)
    await expect(page.locator('#task-message')).toHaveValue('')
    await page.locator('#task-model').selectOption('model')
    await tools.click()
    await output.selectOption('TEXT')
    await tools.press('Escape')
    await message.fill('单独处理')
    await message.press('Shift+Enter')
    await page.keyboard.insertText('后续要求')
    await expect(message).toHaveValue('单独处理\n后续要求')
    for (const ime of [{ isComposing: true }, { keyCode: 229 }]) {
      expect(
        await message.evaluate(
          (field, options) =>
            field.dispatchEvent(
              new KeyboardEvent('keydown', {
                key: 'Enter',
                cancelable: true,
                bubbles: true,
                ...options,
              })
            ),
          ime
        )
      ).toBe(true)
    }
    await message.press('Enter')
    await expect.poll(() => submissions.length).toBe(confirmByPolling ? 2 : 3)
    expect(created).toBe(1)
    expect(submissions[0]?.materialIds).toEqual(['source'])
    expect(submissions[0]?.outputMode).toBe('DOCUMENT')
    expect(submissions[0]).toMatchObject({ message: '整理资料', modelId: 'model' })
    expect(submissions.at(-1)).toMatchObject({
      message: '单独处理\n后续要求',
      modelId: 'model',
      outputMode: 'TEXT',
    })
    expect(submissions.at(-1)?.materialIds).toEqual([])
    expect(submissions.at(-1)?.requestId).not.toBe(submissions[0]?.requestId)
  })
}

test.describe('touch composer', () => {
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })

  test('reviews selected files and sends a multiline draft with the button', async ({ page }) => {
    const projectConversation = { ...conversation, projectId: 'project' }
    const submissions: unknown[] = []
    const upload = { id: 'upload', name: 'upload.md', byteSize: 12 }
    const projectMaterial = { id: 'reference', name: 'project.md', byteSize: 12 }
    const version = {
      id: 'document',
      taskId: 'earlier',
      versionId: 'source-version',
      versionNumber: 2,
      title: '原始方案',
      byteSize: 12,
      markdown: '# 原始方案',
    }
    await page.route('**/api/**', async (route) => {
      const request = route.request()
      const path = new URL(request.url()).pathname
      let data: unknown = emptyPage
      if (path === '/api/auth/me') data = user
      if (path === '/api/auth/csrf') data = { token: 'test-token' }
      if (path === '/api/models') data = [{ id: 'model', name: '很长的模型名称'.repeat(20) }]
      if (path === '/api/conversations/first') data = projectConversation
      if (path === '/api/document-versions/source-version') data = version
      if (path === '/api/projects/project/materials')
        data = { items: [projectMaterial], hasMore: false }
      if (path === '/api/materials')
        data = request.method() === 'POST' ? upload : { items: [upload], hasMore: false }
      if (path === '/api/conversations/first/tasks' && request.method() === 'POST') {
        submissions.push(request.postDataJSON())
        data = { id: 'sent' }
      }
      await route.fulfill({ json: envelope(data) })
    })
    await page.goto('/#/conversations/first?version=source-version')
    const composer = page.locator('.task-composer')
    const message = page.getByRole('textbox', { name: '工作要求（必填）' })
    const tools = page.getByRole('button', { name: /^资料/ })
    const target = page.getByRole('combobox', { name: '模型或智能体（必选）' })
    const send = page.getByRole('button', { name: '提交任务' })
    await target.selectOption('model')
    await expectComposerLayout(page)
    const initialHeight = (await message.boundingBox())!.height

    await tools.tap()
    await page.getByLabel(/project\.md/).check()
    await page.getByLabel('选择文件（必填）').setInputFiles({
      name: upload.name,
      mimeType: 'text/markdown',
      buffer: Buffer.from('# 新资料'),
    })
    await page.getByRole('button', { name: '上传并保存原文' }).tap()
    await expect(page.getByText('已保存：upload.md')).toBeVisible()
    await tools.tap()
    const selected = page.getByRole('list', { name: '已选资料' })
    await expect(selected.getByText('project.md')).toBeVisible()
    await expect(selected.getByText('upload.md')).toBeVisible()
    await expect(composer.getByText('基于《原始方案》版本 2 修改。')).toBeVisible()
    await tools.tap()
    await page.getByLabel(/project\.md/).uncheck()
    await tools.tap()
    await expect(selected.getByText('project.md')).toHaveCount(0)

    await message.fill('多行工作要求\n'.repeat(12))
    expect((await message.boundingBox())!.height).toBeGreaterThan(initialHeight)
    await message.fill('第一行')
    expect((await message.boundingBox())!.height).toBe(initialHeight)
    await message.press('End')
    await message.press('Enter')
    await page.keyboard.insertText('第二行')
    await expect(message).toHaveValue('第一行\n第二行')
    expect(submissions).toHaveLength(0)
    await send.tap()
    await expect(message).toHaveValue('')
    expect(submissions).toEqual([
      expect.objectContaining({
        message: '第一行\n第二行',
        modelId: 'model',
        materialIds: ['upload'],
        outputMode: 'DOCUMENT',
        baseVersionId: 'source-version',
      }),
    ])
  })
})

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
  let failFirstPreview = true
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
    if (path === '/api/document-versions/old-version' && failFirstPreview) {
      failFirstPreview = false
      await route.fulfill({
        status: 503,
        json: { success: false, data: null, error: 'service_unavailable' },
      })
      return
    }
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
  const card = page.getByRole('button', { name: '早期成果 · 版本 1' })
  await card.click()
  await expect(page.getByRole('alert')).toBeVisible()
  await card.click()
  const preview = page.getByRole('dialog', { name: '文档预览' })
  await expect(preview.getByText('早期正文')).toBeVisible()
  await expect(preview.getByRole('link', { name: '下载 .md' })).toHaveAttribute(
    'href',
    '/api/document-versions/old-version/content'
  )
  await preview.getByRole('button', { name: '基于此版本修改' }).click()
  await expect(page.getByText('基于《早期成果》版本 1 修改。')).toBeVisible()
})

for (const width of [390, 1280]) {
  test(`reads the first of two weekly reports and returns to the conversation at ${width}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 700 })
    const tasks: Record<string, unknown>[] = []
    const replies: {
      id: number
      taskId: string
      role: string
      content: string
      publishedVersion?: (typeof versions)[number]
    }[] = []
    const versions = [1, 2].map((number) => ({
      id: 'weekly-report',
      taskId: `report-task-${number}`,
      versionId: `report-version-${number}`,
      versionNumber: number,
      title: '项目周报',
      byteSize: 48,
      markdown: number === 1 ? '# 初稿正文：完成需求梳理' : '# 修订正文：增加上线进度',
    }))
    await page.route('**/api/**', async (route) => {
      const request = route.request()
      const { pathname, searchParams } = new URL(request.url())
      let data: unknown = emptyPage
      if (pathname === '/api/auth/me') data = user
      if (pathname === '/api/auth/csrf') data = { token: 'test-token' }
      if (pathname === '/api/models') data = [{ id: 'model', name: '测试模型' }]
      if (pathname === '/api/conversations/first') data = conversation
      if (pathname === '/api/conversations/first/tasks') {
        if (request.method() === 'POST') {
          const submitted = request.postDataJSON()
          const version = versions[tasks.length]!
          expect(submitted.outputMode).toBe('DOCUMENT')
          expect(submitted.baseVersionId).toBe(tasks.length ? versions[0]!.versionId : undefined)
          const task = {
            id: version.taskId,
            requestId: submitted.requestId,
            status: 'COMPLETED',
            executionState: 'STOPPED',
            outputMode: 'DOCUMENT',
            publishedVersion: version,
          }
          tasks.unshift(task)
          replies.push(
            { id: replies.length + 1, taskId: task.id, role: 'user', content: submitted.message },
            {
              id: replies.length + 2,
              taskId: task.id,
              role: 'assistant',
              content: version.versionNumber === 1 ? '已整理本周完成事项。' : '已补充上线进度。',
              publishedVersion: version,
            }
          )
          data = task
        } else data = { items: tasks.slice(0, 1), hasMore: tasks.length > 1 }
      }
      if (pathname === '/api/conversations/first/messages') {
        data = {
          items: replies.filter((reply) => reply.id > Number(searchParams.get('after'))),
          hasMore: false,
        }
      }
      if (pathname === '/api/conversations/first/documents') {
        data = { items: versions.slice(0, tasks.length).reverse(), hasMore: false }
      }
      const version = versions.find(
        (entry) => pathname === `/api/document-versions/${entry.versionId}`
      )
      if (version) data = version
      await route.fulfill({ json: envelope(data) })
    })

    await page.goto('/#/conversations/first')
    const draft = page.getByRole('textbox', { name: '工作要求（必填）' })
    const send = page.getByRole('button', { name: '提交任务' })
    const dialog = page.getByRole('dialog', { name: '文档预览' })
    const firstReply = page
      .locator('[aria-label="助手"]')
      .filter({ hasText: '已整理本周完成事项。' })
    const firstCard = firstReply.getByRole('button', { name: '项目周报 · 版本 1' })
    await page.locator('#task-model').selectOption('model')
    await page.getByRole('button', { name: '资料', exact: true }).click()
    await page.locator('#task-output').selectOption('DOCUMENT')
    await page.getByRole('button', { name: '资料', exact: true }).click()
    await draft.fill(`生成项目周报。\n${'本周事项。\n'.repeat(28)}`)
    await send.click()

    await firstCard.click()
    await dialog.getByRole('button', { name: '基于此版本修改' }).click()
    await draft.fill(`修订项目周报，增加上线进度。\n${'补充背景。\n'.repeat(40)}`)
    await send.click()
    const secondReply = page.locator('[aria-label="助手"]').filter({ hasText: '已补充上线进度。' })
    await expect(secondReply.getByRole('button', { name: '项目周报 · 版本 2' })).toBeVisible()

    await page.reload()
    await draft.fill('下次还要补充的问题')
    await firstCard.scrollIntoViewIfNeeded()
    await firstCard.focus()
    const thread = page.getByLabel('会话消息', { exact: true })
    const readingPosition = await thread.evaluate((el) => el.scrollTop)
    expect(readingPosition).toBeGreaterThan(0)
    expect(
      await thread.evaluate((el) => el.scrollHeight - el.clientHeight - el.scrollTop)
    ).toBeGreaterThan(32)
    await firstCard.press('Enter')
    await expect(dialog.getByRole('heading', { name: '项目周报 · 版本 1' })).toBeVisible()
    await expect(dialog.getByRole('heading', { name: '初稿正文：完成需求梳理' })).toBeVisible()
    await expect(dialog.getByText('修订正文：增加上线进度')).toHaveCount(0)
    const bounds = (await dialog.boundingBox())!
    expect(bounds.x + bounds.width).toBe(width)
    expect(bounds.x).toBe(width === 390 ? 0 : width - 640)
    expect(bounds.y).toBe(0)
    expect(bounds.height).toBe(700)
    await dialog.getByRole('button', { name: '关闭预览' }).click()
    await expect(dialog).toBeHidden()
    await expect(firstCard).toBeFocused()
    await expect(draft).toHaveValue('下次还要补充的问题')
    expect(await thread.evaluate((el) => el.scrollTop)).toBe(readingPosition)

    await page.getByText('浏览文档版本', { exact: true }).click()
    const secondVersion = page.getByRole('listitem').filter({ hasText: '项目周报 · 版本 2' })
    await secondVersion.getByRole('button', { name: '预览文档' }).click()
    await expect(dialog.getByRole('heading', { name: '修订正文：增加上线进度' })).toBeVisible()
  })
}
