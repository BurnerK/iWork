import { emptyTaskPage } from '../src/testResponse'
import { expect, test } from '@playwright/test'

for (const width of [390, 1280]) {
  test(`reads a growing reply without moving away from earlier text at ${width}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 760 })
    await page.addInitScript(() => {
      window.EventSource = class extends EventTarget {
        constructor(path: string) {
          super()
          this.onUpdate = (event) =>
            this.dispatchEvent(new MessageEvent('message', { data: event.detail }))
          if (path.endsWith('/running/ag-ui')) {
            window.addEventListener('reply', this.onUpdate)
          }
        }
        private onUpdate: (event: CustomEvent) => void
        close() {
          window.removeEventListener('reply', this.onUpdate)
        }
      } as unknown as typeof EventSource
    })
    let completed = false
    const paragraphs = Array.from({ length: 36 }, (_, i) => `段落 ${i + 1}`).join('\n\n')
    const final = `## 本次回复\n\n${paragraphs}\n\n已保存正文`
    await page.route('**/api/**', async (route) => {
      const { pathname, searchParams } = new URL(route.request().url())
      let data: unknown = { items: [], hasMore: false }
      if (pathname === '/api/auth/me')
        data = { id: 'alice', displayName: 'Alice', role: 'USER', mustChangePassword: false }
      if (pathname === '/api/models') data = []
      if (pathname === '/api/conversations/first')
        data = {
          id: 'first',
          title: '工作会话',
          updatedAt: '2026-11-18T00:00:00Z',
          projectId: null,
        }
      if (pathname.endsWith('/first/tasks'))
        data = {
          ...emptyTaskPage,
          items: [
            {
              id: 'running',
              requestId: 'request',
              status: completed ? 'COMPLETED' : 'RUNNING',
              executionState: completed ? 'STOPPED' : 'RUNNING',
            },
          ],
          hasMore: false,
        }
      if (pathname.endsWith('/first/messages')) {
        const after = Number(searchParams.get('after'))
        data = {
          items: [
            { id: 1, taskId: 'earlier', role: 'assistant', content: '## 先前回复' },
            { id: 2, taskId: 'running', role: 'user', content: '请分析这件事' },
            ...(completed ? [{ id: 3, taskId: 'running', role: 'assistant', content: final }] : []),
          ].filter((item) => item.id > after),
          hasMore: false,
        }
      }
      await route.fulfill({ json: { success: true, data, error: null } })
    })
    await page.goto('/#/conversations/first')
    await expect(page.getByRole('heading', { name: '先前回复' })).toBeVisible()
    await expect(page.getByRole('status').filter({ hasText: '运行中' })).toBeVisible()
    await expect(page.getByRole('button', { name: '查看用量' })).toBeHidden()
    const sendDelta = (delta: string) =>
      page.evaluate((delta) => {
        window.dispatchEvent(
          new CustomEvent('reply', {
            detail: JSON.stringify({ type: 'TEXT_MESSAGE_CONTENT', delta }),
          })
        )
      }, delta)
    const thread = page.locator('.task-thread')
    const distance = () => thread.evaluate((el) => el.scrollHeight - el.clientHeight - el.scrollTop)
    await sendDelta(`## 本次回复\n\n${paragraphs}`)
    await expect(page.getByText('段落 36', { exact: true })).toBeVisible()
    await expect.poll(distance).toBeLessThanOrEqual(1)

    await thread.evaluate((el) => {
      el.scrollTop = 130
    })
    const latest = page.getByRole('button', { name: '回到最新消息' })
    await expect(latest).toBeVisible()
    const position = await thread.evaluate((el) => el.scrollTop)
    await sendDelta('\n\n第二段更新')
    await expect(page.getByText('第二段更新')).toBeAttached()
    expect(await thread.evaluate((el) => el.scrollTop)).toBe(position)
    await latest.click()
    await sendDelta('\n\n已保存正文')
    await expect(page.getByText('已保存正文')).toBeVisible()
    await expect.poll(distance).toBeLessThanOrEqual(1)

    completed = true
    await page.evaluate(() =>
      window.dispatchEvent(
        new CustomEvent('reply', {
          detail: JSON.stringify({ type: 'RUN_FINISHED' }),
        })
      )
    )
    await expect(page.getByText('已完成', { exact: true })).toBeVisible()
    await expect(page.getByRole('heading', { name: '本次回复' })).toHaveCount(1)
  })
}

test('pauses, continues and retries within one reply', async ({ page }) => {
  await page.addInitScript(() => {
    window.EventSource = class extends EventTarget {
      private onReply = (event: Event) =>
        this.dispatchEvent(new MessageEvent('message', { data: (event as CustomEvent).detail }))
      constructor(path: string) {
        super()
        window.addEventListener(path, this.onReply)
        this.path = path
      }
      private path: string
      close() {
        window.removeEventListener(this.path, this.onReply)
      }
    } as unknown as typeof EventSource
  })
  const first = '已生成第一段。'
  const second = first + ' 接着写第二段。'
  const final = second + ' 这次写完了。'
  let status = 'RUNNING'
  let id = 'original'
  let saved = ''
  let messageId = 1
  const current = () => ({
    id,
    replyTaskId: id === 'original' ? null : 'original',
    requestId: id,
    status,
    executionState: status === 'RUNNING' ? 'RUNNING' : 'STOPPED',
    errorCode: status === 'FAILED' ? 'execution_failed' : null,
  })
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const { pathname, searchParams } = new URL(request.url())
    let data: unknown = { items: [], hasMore: false }
    if (pathname === '/api/auth/me')
      data = { id: 'alice', displayName: 'Alice', role: 'USER', mustChangePassword: false }
    if (pathname === '/api/auth/csrf') data = { token: 'test-token' }
    if (pathname === '/api/models') data = []
    if (pathname === '/api/conversations/first')
      data = { id: 'first', title: '工作会话', updatedAt: '2026-11-18T00:00:00Z' }
    if (pathname.endsWith('/first/tasks')) data = { ...emptyTaskPage, items: [current()] }
    if (pathname.endsWith('/first/messages')) {
      data = {
        items: [
          { id: 1, taskId: 'original', role: 'user', content: '请写三段' },
          ...(saved
            ? [{ id: messageId, taskId: 'original', role: 'assistant', content: saved }]
            : []),
        ].filter((item) => item.id > Number(searchParams.get('after'))),
        hasMore: false,
      }
    }
    if (pathname === '/api/tasks/original/pause') {
      expect(request.method()).toBe('POST')
      status = 'PAUSED'
      saved = first
      messageId++
      data = current()
    }
    if (
      pathname === '/api/tasks/original/continue' ||
      pathname === '/api/tasks/continued/continue'
    ) {
      expect(request.method()).toBe('POST')
      id = pathname.includes('/original/') ? 'continued' : 'retried'
      status = 'RUNNING'
      data = current()
    }
    await route.fulfill({ json: { success: true, data, error: null } })
  })
  const stream = (attempt: string, content: string) =>
    page.evaluate(
      ({ attempt, content }) =>
        window.dispatchEvent(
          new CustomEvent(`/api/tasks/${attempt}/ag-ui`, {
            detail: JSON.stringify({ type: 'TEXT_MESSAGE_CONTENT', delta: content }),
          })
        ),
      { attempt, content }
    )

  await page.goto('/#/conversations/first')
  const reply = page.getByLabel('助手', { exact: true })
  const pause = page.getByRole('button', { name: '暂停回答', exact: true })
  await expect(pause).toBeEnabled()
  await stream('original', first)
  await expect(reply.getByText(first)).toBeVisible()
  await pause.click()
  await expect(page.getByText('已暂停', { exact: true })).toBeVisible()
  await page.reload()
  await expect(reply.getByText(first)).toBeVisible()
  const resume = page.getByRole('button', { name: '继续回答', exact: true })
  await expect(resume).toHaveAttribute('title', '继续回答')
  await expect(resume).toHaveText('')
  await resume.press('Enter')
  await expect(pause).toBeEnabled()
  await stream('continued', second)
  await expect(reply.getByText(second, { exact: true })).toBeVisible()

  status = 'FAILED'
  saved = second
  messageId++
  await expect(reply.getByRole('alert')).toHaveText(/任务执行失败/)
  await expect(reply.getByText(second, { exact: true })).toBeVisible()
  await reply.getByRole('button', { name: '重试回答' }).click()
  await expect(pause).toBeEnabled()
  status = 'COMPLETED'
  saved = final
  messageId++
  await expect(reply.getByText(final, { exact: true })).toBeVisible()
  await expect(reply).toHaveCount(1)
  await expect(page.getByText('请写三段', { exact: true })).toHaveCount(1)
})

for (const action of ['answer', 'queue', 'both'] as const) {
  test(`continues ${action} independently after pausing a report`, async ({ page }) => {
    let report = { id: 'report', requestId: 'report', status: 'PAUSED', executionState: 'STOPPED' }
    let queuePaused = true
    let pauseVersion = 4
    let mailStarted = false
    let mailCancelled = false
    const tasks = '/api/conversations/first/tasks'
    const finishReport = () => {
      report = { ...report, status: 'COMPLETED', executionState: 'STOPPED' }
      if (!queuePaused) mailStarted = true
    }
    await page.route('**/api/**', async (route) => {
      const { pathname, searchParams } = new URL(route.request().url())
      let data: unknown = { items: [], hasMore: false }
      if (pathname === '/api/auth/me')
        data = { id: 'alice', displayName: 'Alice', role: 'USER', mustChangePassword: false }
      if (pathname === '/api/auth/csrf') data = { token: 'test-token' }
      if (pathname === '/api/models') data = []
      if (['/api/conversations/first', '/api/conversations/other'].includes(pathname))
        data = {
          id: pathname.split('/').at(-1),
          title: '工作会话',
          updatedAt: '2026-11-19T00:00:00Z',
        }
      if (pathname === tasks)
        data = {
          ...emptyTaskPage,
          items: mailStarted
            ? [
                {
                  id: 'mail',
                  requestId: 'mail',
                  status: mailCancelled ? 'CANCELLED' : 'RUNNING',
                  executionState: mailCancelled ? 'STOPPED' : 'RUNNING',
                },
                report,
              ]
            : [report],
          hasMore: false,
          queuedCount: mailStarted ? 0 : 1,
          queuedItems: mailStarted ? [] : [{ id: 'mail', summary: '写封邮件' }],
          queuePaused,
          queuePauseVersion: pauseVersion,
        }
      if (pathname === '/api/conversations/first/messages')
        data = {
          items: [
            { id: 1, taskId: 'report', role: 'user', content: '写份周报' },
            { id: 2, taskId: 'report', role: 'assistant', content: '周报已写一半。' },
            ...(report.status === 'COMPLETED'
              ? [{ id: 3, taskId: 'report', role: 'assistant', content: '周报已写完。' }]
              : []),
            ...(mailStarted ? [{ id: 4, taskId: 'mail', role: 'user', content: '写封邮件' }] : []),
          ].filter((item) => item.id > Number(searchParams.get('after'))),
          hasMore: false,
        }
      if (pathname === '/api/tasks/report/continue') {
        expect(route.request().method()).toBe('POST')
        report = { ...report, status: 'RUNNING', executionState: 'RUNNING' }
        data = report
      }
      if (pathname === `${tasks}/queue/resume`) {
        expect(route.request().method()).toBe('POST')
        if (action === 'queue' && pauseVersion === 4) {
          expect(searchParams.get('pauseVersion')).toBe('4')
          pauseVersion = 5
        } else {
          expect(searchParams.get('pauseVersion')).toBe(String(pauseVersion))
          queuePaused = false
          if (report.status === 'PAUSED') report = { ...report, status: 'INTERRUPTED' }
          if (report.status !== 'RUNNING') mailStarted = true
        }
        await route.fulfill({ status: 204 })
        return
      }
      if (pathname === '/api/tasks/mail/cancel') {
        expect(route.request().method()).toBe('POST')
        expect(route.request().postDataJSON()).toEqual({ pauseQueue: true })
        mailCancelled = true
        queuePaused = true
        data = { id: 'mail', requestId: 'mail', status: 'CANCELLED', executionState: 'STOPPED' }
      }
      if (pathname.endsWith('/ag-ui')) {
        await route.fulfill({ contentType: 'text/event-stream', body: '' })
        return
      }
      await route.fulfill({ json: { success: true, data, error: null } })
    })

    await page.goto('/#/conversations/first')
    const answer = page.getByRole('button', { name: '继续回答', exact: true })
    const queue = page.getByRole('button', { name: '继续发送队列', exact: true })
    const paused = page.getByText('队列已暂停', { exact: true })
    const mailPrompt = page.locator('.task-thread').getByText('写封邮件', { exact: true })
    await expect(answer).toHaveAttribute('title', '继续回答')
    await expect(answer).toHaveText('')
    await expect(queue).toHaveAttribute('title', '继续发送队列')
    await expect(queue).toHaveText('')
    if (action !== 'queue') {
      await answer.press('Enter')
      await expect(page.getByRole('button', { name: '暂停回答', exact: true })).toBeEnabled()
      await expect(paused).toBeVisible()
    }
    if (action === 'answer') {
      finishReport()
      await expect(page.getByText('周报已写完。')).toBeVisible()
      await page.reload()
      await expect(paused).toBeVisible()
      await page.goto('/#/conversations/other')
      await expect(paused).toBeHidden()
      await page.goto('/#/conversations/first')
      await expect(paused).toBeVisible()
      await expect(mailPrompt).toBeHidden()
      return
    }

    await queue.focus()
    await expect(queue).toBeFocused()
    if (action === 'queue') {
      const stale = page.waitForResponse((response) => response.url().includes('/queue/resume'))
      await page.keyboard.press('Space')
      await stale
      await expect(queue).toBeEnabled()
      await expect(queue).toBeFocused()
      await expect(paused).toBeVisible()
    }
    await page.keyboard.press('Space')
    await expect(paused).toBeHidden()
    await expect(page.getByRole('button', { name: '资料', exact: true })).toBeFocused()
    if (action === 'both') {
      await expect(page.getByRole('button', { name: '暂停回答', exact: true })).toBeEnabled()
      await expect(mailPrompt).toBeHidden()
      finishReport()
      await expect(page.getByText('周报已写完。')).toBeVisible()
    } else {
      await expect(page.getByText('已中断', { exact: true })).toBeVisible()
      await expect(page.getByText('周报已写一半。')).toBeVisible()
    }
    await expect(mailPrompt).toBeVisible()
    if (action === 'both') {
      await page.getByRole('button', { name: '取消任务', exact: true }).click()
      await expect(page.getByText('已取消', { exact: true })).toBeVisible()
    }
  })
}

test('refreshes and reconnects from two tabs to the same running task', async ({
  context,
  page,
}) => {
  const taskId = 'report'
  const first = '第一段。'
  const restored = first + '第二段。'
  const last = '第三段。'
  const final = restored + last
  const version = { versionId: 'report-v1', title: '周报', versionNumber: 1 }
  let continued = false
  let completed = false
  await context.route('**/api/**', async (route) => {
    expect(route.request().method()).toBe('GET')
    const { pathname, searchParams } = new URL(route.request().url())
    let data: unknown = { items: [], hasMore: false }
    if (pathname === '/api/auth/me')
      data = { id: 'alice', displayName: 'Alice', role: 'USER', mustChangePassword: false }
    if (pathname === '/api/models') data = []
    if (pathname === '/api/conversations/first')
      data = { id: 'first', title: '工作会话', updatedAt: '2026-11-20T00:00:00Z' }
    if (pathname.endsWith('/first/tasks'))
      data = {
        ...emptyTaskPage,
        items: [
          {
            id: taskId,
            requestId: 'write-report',
            status: completed ? 'COMPLETED' : 'RUNNING',
            executionState: completed ? 'STOPPED' : 'RUNNING',
          },
        ],
        hasMore: false,
        queuedItems: [],
      }
    if (pathname.endsWith('/first/messages'))
      data = {
        items: [
          { id: 1, taskId, role: 'user', content: '请写周报' },
          ...(completed
            ? [{ id: 2, taskId, role: 'assistant', content: final, publishedVersion: version }]
            : []),
        ].filter((item) => item.id > Number(searchParams.get('after'))),
        hasMore: false,
      }
    if (pathname === '/api/tasks/report/ag-ui') {
      const snapshot = completed ? final : continued ? restored : first
      const events = [
        { type: 'RUN_STARTED' },
        ...['RUNNING', ...(continued ? ['COMPLETED'] : [])].map((status) => ({
          type: 'CUSTOM',
          name: 'iwork.tool',
          value: {
            id: 'read-source',
            name: 'read_task_material',
            materialLabel: '工作记录.txt',
            status,
          },
        })),
        ...(continued
          ? [
              {
                type: 'CUSTOM',
                name: 'iwork.tool',
                value: { id: 'prepare', name: 'prepare_document', status: 'COMPLETED' },
              },
            ]
          : []),
        {
          type: 'MESSAGES_SNAPSHOT',
          messages: [{ id: 'report-reply', role: 'assistant', content: snapshot }],
        },
        ...(continued && !completed ? [{ type: 'TEXT_MESSAGE_CONTENT', delta: last }] : []),
        ...(completed ? [{ type: 'RUN_FINISHED' }] : []),
      ]
      await route.fulfill({
        contentType: 'text/event-stream',
        body:
          'retry: 100\n\n' + events.map((event) => `data: ${JSON.stringify(event)}\n\n`).join(''),
      })
      return
    }
    await route.fulfill({ json: { success: true, data, error: null } })
  })

  await page.goto('/#/conversations/first')
  await expect(page.getByText(first, { exact: true })).toBeVisible()
  await expect(page.getByText('工作记录.txt · 正在读取')).toBeVisible()
  await expect(page.getByRole('button', { name: '周报 · 版本 1' })).toHaveCount(0)
  await page.reload()
  await expect(page.getByText(first, { exact: true })).toBeVisible()

  const other = await context.newPage()
  await other.goto('/#/conversations/first')
  await expect(other.getByText(first, { exact: true })).toBeVisible()
  continued = true
  for (const tab of [page, other]) {
    await expect(tab.getByText(final, { exact: true })).toBeVisible()
    await expect(tab.getByText('工作记录.txt · 读取完成')).toHaveCount(1)
    await expect(tab.getByText('文档正文已准备', { exact: true })).toBeVisible()
    await expect(tab.getByRole('button', { name: '周报 · 版本 1' })).toHaveCount(0)
    await expect(tab.getByText('请写周报', { exact: true })).toHaveCount(1)
  }

  completed = true
  for (const tab of [page, other]) {
    await expect(tab.getByText(final, { exact: true })).toBeVisible()
    await expect(tab.getByText('已完成', { exact: true })).toBeVisible()
    await expect(tab.getByRole('button', { name: '周报 · 版本 1' })).toHaveCount(1)
  }
})

for (const saveFailed of [false, true]) {
  test(`shows the ${saveFailed ? 'final save' : 'material'} failure without an artifact card`, async ({
    page,
  }) => {
    const errorCode = saveFailed ? 'document_storage_unavailable' : 'material_parse_failed'
    const progress = saveFailed
      ? { id: 'prepare', name: 'prepare_document', status: 'COMPLETED' }
      : {
          id: 'read',
          name: 'read_task_material',
          materialLabel: '损坏的记录.txt（第 2 份资料）',
          status: 'FAILED',
        }
    await page.route('**/api/**', async (route) => {
      const { pathname } = new URL(route.request().url())
      let data: unknown = { items: [], hasMore: false }
      if (pathname === '/api/auth/me')
        data = { id: 'alice', displayName: 'Alice', role: 'USER', mustChangePassword: false }
      if (pathname === '/api/models') data = []
      if (pathname === '/api/conversations/first')
        data = { id: 'first', title: '工作会话', updatedAt: '2026-11-20T00:00:00Z' }
      if (pathname.endsWith('/first/tasks'))
        data = {
          ...emptyTaskPage,
          items: [
            {
              id: 'report',
              requestId: 'write',
              status: 'FAILED',
              executionState: 'STOPPED',
              outputMode: 'DOCUMENT',
              errorCode,
              toolProgress: [progress],
            },
          ],
          hasMore: false,
        }
      if (pathname.endsWith('/first/messages'))
        data = {
          items: [{ id: 1, taskId: 'report', role: 'user', content: '写周报' }],
          hasMore: false,
        }
      await route.fulfill({ json: { success: true, data, error: null } })
    })

    await page.goto('/#/conversations/first')
    await expect(
      page.getByText(saveFailed ? '文档正文已准备' : '损坏的记录.txt（第 2 份资料） · 读取失败', {
        exact: true,
      })
    ).toBeVisible()
    await expect(
      page.getByRole('alert').filter({ hasText: saveFailed ? '文档保存' : '资料无法解析' })
    ).toBeVisible()
    await expect(page.getByRole('button', { name: /版本 1/ })).toHaveCount(0)
  })
}
