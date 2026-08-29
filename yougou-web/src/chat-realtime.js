import { Client } from '@stomp/stompjs'
import { TOKEN_KEY } from './api'

function websocketUrl() {
  if (import.meta.env.VITE_CHAT_WS_URL) return import.meta.env.VITE_CHAT_WS_URL
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${location.host}/ws/chat`
}

/**
 * 创建买家端聊天实时连接。
 * 客户端只订阅私有队列，不通过 STOMP SEND 写消息。
 */
export function createBuyerChatRealtime({ onEvent, onStatus }) {
  const client = new Client({
    brokerURL: websocketUrl(),
    reconnectDelay: 3000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    debug: () => {},
    beforeConnect: () => {
      const token = localStorage.getItem(TOKEN_KEY)
      client.connectHeaders = token ? { Authorization: `Bearer ${token}` } : {}
      onStatus?.('connecting')
    },
    onConnect: () => {
      onStatus?.('connected')
      client.subscribe('/user/queue/chat.events', (frame) => {
        try {
          onEvent?.(JSON.parse(frame.body))
        } catch (_) {
          // 单条异常帧不能破坏后续实时消息。
        }
      })
    },
    onStompError: () => onStatus?.('error'),
    onWebSocketClose: () => onStatus?.('disconnected'),
    onWebSocketError: () => onStatus?.('error')
  })

  return {
    connect() {
      if (!client.active) client.activate()
    },
    disconnect() {
      return client.deactivate()
    }
  }
}
