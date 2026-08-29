import { Client } from '@stomp/stompjs'
import { MERCHANT_TOKEN_KEY } from './api'

function websocketUrl() {
  if (import.meta.env.VITE_CHAT_WS_URL) return import.meta.env.VITE_CHAT_WS_URL
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${location.host}/ws/chat`
}

/** 商家端只订阅私有队列，所有消息写入仍走 REST。 */
export function createMerchantChatRealtime({ onEvent, onStatus }) {
  const client = new Client({
    brokerURL: websocketUrl(),
    reconnectDelay: 3000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    debug: () => {},
    beforeConnect: () => {
      const token = localStorage.getItem(MERCHANT_TOKEN_KEY)
      client.connectHeaders = token ? { Authorization: `Bearer ${token}` } : {}
      onStatus?.('connecting')
    },
    onConnect: () => {
      onStatus?.('connected')
      client.subscribe('/user/queue/chat.events', (frame) => {
        try {
          onEvent?.(JSON.parse(frame.body))
        } catch (_) {
          // 忽略单条非法事件，保持后续订阅有效。
        }
      })
    },
    onStompError: () => onStatus?.('error'),
    onWebSocketClose: () => onStatus?.('disconnected'),
    onWebSocketError: () => onStatus?.('error')
  })

  return {
    connect: () => { if (!client.active) client.activate() },
    disconnect: () => client.deactivate()
  }
}
