/**
 * 将店铺导购正文拆成只包含普通文本和加粗文本的安全片段。
 *
 * 后端保留 [资料N] 是为了内部追踪回答来源，但买家页面不展示知识库
 * 文件名、分片、向量分数或引用编号。这里在渲染前移除引用标记，同时
 * 继续使用 Vue 文本插值，避免使用 v-html 引入商家文档 XSS 风险。
 */
export function merchantAiAnswerParts(answer) {
  const text = String(answer || '')
  const pattern = /(\*\*[^*\n]+\*\*|\[资料\d+\])/g
  const parts = []
  let cursor = 0
  let match

  while ((match = pattern.exec(text)) !== null) {
    if (match.index > cursor) {
      parts.push({ type: 'text', text: text.slice(cursor, match.index) })
    }
    /* 引用编号只供内部追踪，命中时前移游标但不创建可见片段。 */
    if (!match[0].startsWith('[资料')) {
      parts.push({ type: 'strong', text: match[0].slice(2, -2) })
    }
    cursor = pattern.lastIndex
  }

  if (cursor < text.length) {
    parts.push({ type: 'text', text: text.slice(cursor) })
  }
  return parts
}
