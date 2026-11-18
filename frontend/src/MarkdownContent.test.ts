import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import MarkdownContent from './MarkdownContent.vue'

it('renders Markdown without executing embedded HTML or loading external images', () => {
  const view = mount(MarkdownContent, {
    props: {
      content:
        '# Report\n\n| A | B |\n| - | - |\n| 1 | 2 |\n\n```js\nhello()\n```\n\n<script>alert(1)</script>\n\n[link](javascript:alert%281%29)\n\n![image](https://example.com/image.png)',
    },
  })
  expect(view.get('h1').text()).toBe('Report')
  expect(view.get('table').text()).toContain('1')
  expect(view.get('pre code').text()).toContain('hello()')
  expect(view.find('script, img, a[href^="javascript:"]').exists()).toBe(false)
})
