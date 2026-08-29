import assert from 'node:assert/strict'
import test from 'node:test'
import { watch } from 'vue'

import { createReactiveStreamMessage } from './agent-stream-state.js'

test('CONTENT_DELTA 修改会同步触发响应式正文更新', () => {
  const message = createReactiveStreamMessage({
    role: 'ASSISTANT',
    status: 'STREAMING',
    content: ''
  })
  const rendered = []

  watch(
    () => message.content,
    (content) => rendered.push(content),
    { flush: 'sync' }
  )

  message.content += '流'
  message.content += '式'
  message.content += '回答'

  assert.deepEqual(rendered, ['流', '流式', '流式回答'])
})

test('拒绝把用户消息误用作助手流状态', () => {
  assert.throws(
    () => createReactiveStreamMessage({ role: 'USER', content: '' }),
    /助手消息/
  )
})
