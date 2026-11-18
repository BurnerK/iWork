import { apiResponse } from './testResponse'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import ModelPanel from './ModelPanel.vue'
afterEach(() => vi.unstubAllGlobals())
it('clears the draft when cancelling model editing', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) =>
      Promise.resolve(
        apiResponse(
          JSON.stringify(
            url.includes('/users')
              ? { items: [], hasMore: false }
              : url.endsWith('/model-settings')
                ? { currency: 'CNY', locked: false }
                : [
                    {
                      id: 'writer',
                      name: '写作模型',
                      baseUrl: 'https://example.test',
                      modelName: 'writer',
                      credentialRef: 'IWORK_MODEL_KEY_TEST',
                      inputPrice: '1',
                      outputPrice: '2',
                      enabled: true,
                      contextWindow: 8192,
                    },
                  ]
          )
        )
      )
    )
  )
  const panel = mount(ModelPanel, { props: { admin: true } })
  await flushPromises()
  await panel
    .findAll('button')
    .find((button) => button.text() === '编辑 写作模型')!
    .trigger('click')
  expect((panel.get('#model-name').element as HTMLInputElement).value).toBe('写作模型')
  await panel
    .findAll('button')
    .find((button) => button.text() === '取消编辑')!
    .trigger('click')
  expect((panel.get('#model-name').element as HTMLInputElement).value).toBe('')
  expect(panel.text()).toContain('添加模型')
})
it('shows the personal budget and only server-authorized models', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) =>
      Promise.resolve(
        apiResponse(
          JSON.stringify(
            url === '/api/models'
              ? [
                  {
                    id: 'writer',
                    name: '写作模型',
                    inputPrice: '1.000000',
                    outputPrice: '2.000000',
                  },
                ]
              : {
                  currency: 'CNY',
                  limit: '10.000000',
                  spent: '2.000000',
                  reserved: '1.000000',
                  available: '7.000000',
                }
          )
        )
      )
    )
  )
  const panel = mount(ModelPanel, { props: { admin: false } })
  await flushPromises()
  expect(panel.text()).toContain('写作模型')
  expect(panel.text()).toContain('7.000000')
  expect(panel.find('form').exists()).toBe(false)
})
it('lets administrators save a model reference without accepting a secret', async () => {
  const fetch = vi.fn((url: string, init?: RequestInit) =>
    Promise.resolve(
      apiResponse(
        JSON.stringify(
          url.endsWith('/csrf')
            ? { token: 'proof' }
            : url.includes('/users')
              ? { items: [], hasMore: false }
              : url.endsWith('/model-settings')
                ? { currency: 'CNY', locked: false }
                : init?.method === 'POST'
                  ? { id: 'saved', ...JSON.parse(String(init.body)) }
                  : []
        )
      )
    )
  )
  vi.stubGlobal('fetch', fetch)
  const panel = mount(ModelPanel, { props: { admin: true } })
  await flushPromises()
  await panel.get('#model-name').setValue('写作')
  await panel.get('#model-url').setValue('https://models.example.test/v1')
  await panel.get('#model-upstream').setValue('writer')
  await panel.get('#model-window').setValue(131072)
  await panel.get('#model-credential').setValue('IWORK_MODEL_KEY_WRITER')
  await panel.get('form[aria-label="模型配置"]').trigger('submit')
  await flushPromises()
  expect(fetch).toHaveBeenCalledWith(
    '/api/admin/models',
    expect.objectContaining({
      method: 'POST',
      body: expect.stringContaining('IWORK_MODEL_KEY_WRITER'),
    })
  )
  expect(panel.find('input[type="password"]').exists()).toBe(false)
  expect(panel.text()).toContain('模型已保存')
})
it('clears the previous user details when paging the user selector', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) =>
      Promise.resolve(
        apiResponse(
          JSON.stringify(
            url.includes('/model-access')
              ? []
              : url.endsWith('/budget')
                ? { currency: 'CNY', limit: '10', spent: '0', reserved: '0', available: '10' }
                : url.includes('/users?page=0')
                  ? {
                      items: [
                        {
                          id: 'alice',
                          username: 'Alice',
                          displayName: 'Alice',
                          role: 'USER',
                          mustChangePassword: false,
                        },
                      ],
                      hasMore: true,
                    }
                  : url.includes('/users?page=1')
                    ? {
                        items: [
                          {
                            id: 'bob',
                            username: 'Bob',
                            displayName: 'Bob',
                            role: 'USER',
                            mustChangePassword: false,
                          },
                        ],
                        hasMore: false,
                      }
                    : url.endsWith('/model-settings')
                      ? { currency: 'CNY', locked: true }
                      : []
          )
        )
      )
    )
  )
  const panel = mount(ModelPanel, { props: { admin: true } })
  await flushPromises()
  await panel.get('#model-user').setValue('alice')
  await panel.get('form[aria-label="选择用户"]').trigger('submit')
  await flushPromises()
  expect(panel.find('form[aria-label="个人额度"]').exists()).toBe(true)
  await panel
    .findAll('button')
    .find((button) => button.text() === '下一页用户')!
    .trigger('click')
  await flushPromises()
  expect(panel.find('form[aria-label="个人额度"]').exists()).toBe(false)
})
