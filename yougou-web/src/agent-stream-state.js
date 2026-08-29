import { reactive } from 'vue'

/**
 * 创建后续会被 SSE 事件持续修改的助手消息代理。
 *
 * Vue 会在读取 ref 数组元素时返回代理，但不会把调用方仍持有的原始对象
 * 就地改成代理。如果流处理器继续修改原始对象，CONTENT_DELTA 不会触发
 * 组件渲染，最终完成事件刷新时才会整段出现。因此流状态和消息数组必须
 * 从一开始就共享同一个 reactive 代理引用。
 */
export function createReactiveStreamMessage(message) {
  if (!message || message.role !== 'ASSISTANT') {
    throw new TypeError('流式消息必须是助手消息')
  }
  return reactive(message)
}
