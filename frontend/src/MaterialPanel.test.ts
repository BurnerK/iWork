import { apiResponse } from './testResponse'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import MaterialPanel from './MaterialPanel.vue'
afterEach(() => vi.unstubAllGlobals())
it('uploads original bytes then makes the saved material selectable', async () => {
  const file = new File(['项目资料'], '资料.txt', { type: 'text/plain' })
  const saved = { id: 'material', name: file.name, byteSize: file.size }
  let uploaded = false
  const fetch = vi.fn((url: string, init?: RequestInit) => {
    if (init?.method === 'POST') uploaded = true
    return Promise.resolve(
      apiResponse(
        JSON.stringify(
          url.endsWith('/csrf')
            ? { token: 'proof' }
            : init?.method === 'POST'
              ? saved
              : { items: uploaded ? [saved] : [], hasMore: false }
        )
      )
    )
  })
  vi.stubGlobal('fetch', fetch)
  const panel = mount(MaterialPanel, { props: { modelValue: [], disabled: false } })
  await flushPromises()
  Object.defineProperty(panel.get('input[type=file]').element, 'files', { value: [file] })
  await panel.get('input[type=file]').trigger('change')
  await panel.get('form').trigger('submit')
  await flushPromises()
  expect(fetch).toHaveBeenCalledWith(
    '/api/materials',
    expect.objectContaining({
      method: 'POST',
      body: file,
      headers: expect.objectContaining({ 'X-File-Name': encodeURIComponent(file.name) }),
    })
  )
  expect(panel.emitted('update:modelValue')?.at(-1)).toEqual([['material']])
  expect(panel.get('a').attributes('href')).toBe('/api/materials/material/content')
})
