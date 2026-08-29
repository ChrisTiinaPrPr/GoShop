import assert from 'node:assert/strict'
import test from 'node:test'

import { merchantAiAnswerParts } from './merchant-ai-answer.js'

test('买家正文不展示回答引用编号', () => {
  const parts = merchantAiAnswerParts('适合游戏[资料1]，价格 199 元[资料2]。')

  assert.equal(parts.map((part) => part.text).join(''), '适合游戏，价格 199 元。')
  assert.equal(parts.some((part) => part.type === 'citation'), false)
})

test('移除引用时仍保留受控加粗文本', () => {
  assert.deepEqual(
    merchantAiAnswerParts('推荐 **M7 鼠标**[资料3]。'),
    [
      { type: 'text', text: '推荐 ' },
      { type: 'strong', text: 'M7 鼠标' },
      { type: 'text', text: '。' }
    ]
  )
})
