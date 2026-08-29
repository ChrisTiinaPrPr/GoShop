const BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api/v1/buyer'
export const TOKEN_KEY = 'yougou_buyer_access_token'

export function saveSession(accessToken) {
  localStorage.setItem(TOKEN_KEY, accessToken)
  window.dispatchEvent(new Event('yougou-auth-changed'))
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY)
  window.dispatchEvent(new Event('yougou-auth-changed'))
}

/** 所有后端响应均通过这里处理，避免页面各自处理令牌和错误码。 */
async function request(path, options = {}) {
  const token = localStorage.getItem(TOKEN_KEY)
  const isFormData = options.body instanceof FormData
  const response = await fetch(`${BASE_URL}${path}`, {
    ...options,
    headers: {
      // FormData 必须交给浏览器补充 multipart boundary，不能手动设置 Content-Type。
      ...(isFormData ? {} : { 'Content-Type': 'application/json' }),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...options.headers
    }
  })

  const body = await response.json().catch(() => ({}))
  // 兼容当前后端的 Result.ok(data)=200 与文档约定的成功码 0。
  const isSuccess = body.code === 0 || body.code === 200
  if (!response.ok || !isSuccess) {
    if (response.status === 401) clearSession()
    const error = new Error(body.message || '网络请求失败，请稍后重试')
    // 保留稳定业务错误码，页面可以区分 40901（运行中）和普通网络错误。
    error.code = body.code
    error.httpStatus = response.status
    throw error
  }
  return body.data
}

function queryString(params = {}) {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') search.set(key, value)
  })
  const result = search.toString()
  return result ? `?${result}` : ''
}

/**
 * 使用 POST + fetch ReadableStream 读取 Agent SSE。
 *
 * 浏览器原生 EventSource 只支持 GET，也不能方便地添加 Bearer Token，
 * 所以购物 Agent 必须自行解析 text/event-stream。解析器按空行切分事件，
 * 支持一个 JSON data 被网络拆成任意多个字节块，不能假设一次 read()
 * 就对应一个完整事件。
 */
export async function streamAgentMessage(
  conversationId,
  payload,
  { onEvent, signal } = {}
) {
  const token = localStorage.getItem(TOKEN_KEY)
  const response = await fetch(
    BASE_URL + '/agent/conversations/' + conversationId + '/messages',
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        ...(token ? { Authorization: 'Bearer ' + token } : {})
      },
      body: JSON.stringify(payload),
      signal
    }
  )

  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    if (response.status === 401) clearSession()

    const error = new Error(body.message || '购物助手请求失败，请稍后重试')
    error.code = body.code
    error.httpStatus = response.status
    throw error
  }

  /*
   * 当前项目的 BusinessException 可能使用 HTTP 200 + Result.fail 返回。
   * 此时响应不是 SSE，必须先识别 application/json，否则解析器会把整段
   * JSON 当作“没有事件的正常流”，掩盖 40401/40901 等真实业务错误。
   */
  const contentType = response.headers.get('content-type') || ''
  if (!contentType.includes('text/event-stream')) {
    const body = await response.json().catch(() => ({}))
    const error = new Error(body.message || '购物助手返回了非流式响应')
    error.code = body.code
    error.httpStatus = response.status
    throw error
  }

  if (!response.body) {
    throw new Error('当前浏览器无法读取购物助手流式响应')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  async function dispatchBlock(block) {
    const data = block
      .split('\n')
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trimStart())
      .join('\n')
      .trim()

    // 心跳、注释和只有 event/id 字段的块不包含业务 JSON，直接忽略。
    if (!data) return

    let event
    try {
      event = JSON.parse(data)
    } catch (_) {
      throw new Error('购物助手返回了无法解析的流式事件')
    }

    if (onEvent) await onEvent(event)
  }

  while (true) {
    const { value, done } = await reader.read()
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
    buffer = buffer.replace(/\r\n/g, '\n')

    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const block = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      await dispatchBlock(block)
      boundary = buffer.indexOf('\n\n')
    }

    if (done) break
  }

  // 某些代理在连接结束前不会补最后一个空行，需要处理剩余事件。
  if (buffer.trim()) await dispatchBlock(buffer)
}

/**
 * 使用 POST + fetch ReadableStream 消费店铺智能导购 SSE。
 *
 * 与购物 Agent 一样，店铺导购需要在 POST 请求中携带问题和 Bearer Token，
 * 因此不能使用只支持 GET 的 EventSource。网络分片与 SSE 事件边界没有一一
 * 对应关系，必须先按空行重组完整事件，再解析其中的 JSON data。
 */
export async function streamMerchantAiQuestion(
  merchantId,
  question,
  { onEvent, signal } = {}
) {
  const token = localStorage.getItem(TOKEN_KEY)
  const response = await fetch(
    `${BASE_URL}/merchants/${merchantId}/ai-assistant/questions`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        ...(token ? { Authorization: `Bearer ${token}` } : {})
      },
      body: JSON.stringify({ question }),
      signal
    }
  )

  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    if (response.status === 401) clearSession()
    const error = new Error(body.message || '智能导购暂时无法回答，请稍后重试')
    error.code = body.code
    error.httpStatus = response.status
    throw error
  }

  /*
   * 检索、限流和身份校验在 SSE 首事件写出前执行，业务异常仍可能通过
   * Result JSON 返回。显式校验 Content-Type 可以保留真实业务错误，避免
   * 把普通 JSON 当成“没有任何事件的成功流”。
   */
  const contentType = response.headers.get('content-type') || ''
  if (!contentType.includes('text/event-stream')) {
    const body = await response.json().catch(() => ({}))
    const error = new Error(body.message || '智能导购返回了非流式响应')
    error.code = body.code
    error.httpStatus = response.status
    throw error
  }

  if (!response.body) {
    throw new Error('当前浏览器无法读取智能导购流式响应')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  async function dispatchBlock(block) {
    const data = block
      .split('\n')
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trimStart())
      .join('\n')
      .trim()

    // 代理心跳或 SSE 注释没有 data 字段，不属于业务事件。
    if (!data) return

    let event
    try {
      event = JSON.parse(data)
    } catch (_) {
      throw new Error('智能导购返回了无法解析的流式事件')
    }
    if (onEvent) await onEvent(event)
  }

  while (true) {
    const { value, done } = await reader.read()
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
    buffer = buffer.replace(/\r\n/g, '\n')

    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const block = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      await dispatchBlock(block)
      boundary = buffer.indexOf('\n\n')
    }
    if (done) break
  }

  // 兼容连接关闭前没有最后一个 SSE 空行的代理实现。
  if (buffer.trim()) await dispatchBlock(buffer)
}

export const api = {
  // 认证
  sendCode: (phone) => request('/auth/code', { method: 'POST', body: JSON.stringify({ phone }) }),
  login: (phone, code) => request('/auth/login', { method: 'POST', body: JSON.stringify({ phone, code, loginType: 'USER' }) }),
  logout: () => request('/auth/logout', { method: 'POST' }),

  // 商品、分类与公开商家资料
  products: (params = {}) => request(`/products${queryString(params)}`),
  product: (id) => request(`/products/${id}`),
  categories: () => request('/categories'),
  merchant: (id) => request(`/merchants/${id}`),
  merchantProducts: (merchantId, params = {}) => request(
    `/merchants/${merchantId}/products${queryString(params)}`
  ),
  // 商品收藏。商品 ID 是后端雪花 Long，始终作为字符串原样拼接，避免 Number 精度丢失。
  favorites: (page = 1, pageSize = 12) => request(
    `/favorites${queryString({ page, pageSize })}`
  ),
  favoriteStatus: (productId) => request(`/favorites/${productId}/status`),
  addFavorite: (productId) => request(`/favorites/${productId}`, { method: 'POST' }),
  removeFavorite: (productId) => request(`/favorites/${productId}`, { method: 'DELETE' }),

  // 商品评价：创建接口读取 JWT；订单评价状态仅返回本人订单；商品评价列表允许游客访问。
  createReview: (payload) => request('/reviews', {
    method: 'POST',
    body: JSON.stringify(payload)
  }),
  orderReviews: (orderNo) => request(`/reviews/orders/${encodeURIComponent(orderNo)}`),
  productReviews: (productId, page = 1, pageSize = 10) => request(
    `/products/${productId}/reviews${queryString({ page, pageSize })}`
  ),

  // 当前用户资料和地址
  profile: () => request('/me'),
  // 查询当前登录用户的钱包余额，金额单位为“分”。
  wallet: () => request('/me/wallet'),
  updateProfile: ({ nickname, avatar }) => {
    const formData = new FormData()
    if (nickname !== undefined) formData.append('nickName', nickname)
    if (avatar) formData.append('avatar', avatar)
    return request('/me', { method: 'PATCH', body: formData })
  },
  addresses: () => request('/me/addresses'),
  createAddress: (payload) => request('/me/addresses', { method: 'POST', body: JSON.stringify(payload) }),
  updateAddress: (id, payload) => request(`/me/addresses/${id}`, { method: 'PATCH', body: JSON.stringify(payload) }),
  removeAddress: (id) => request(`/me/addresses/${id}`, { method: 'DELETE' }),
  setDefaultAddress: (id) => request(`/me/addresses/${id}/default`, { method: 'PATCH' }),

  // 购物车
  cart: () => request('/cart/items'),
  addCart: (skuId, quantity = 1) => request('/cart/items', { method: 'POST', body: JSON.stringify({ skuId, quantity }) }),
  updateCart: (skuId, payload) => request(`/cart/items/${skuId}`, {
    method: 'PATCH',
    body: JSON.stringify(typeof payload === 'number' ? { quantity: payload } : payload)
  }),
  removeCart: (skuId) => request(`/cart/items/${skuId}`, { method: 'DELETE' }),
  clearInvalidCart: () => request('/cart/items', { method: 'DELETE' }),

  // 订单
  orders: (page = 1, pageSize = 10) => request(`/orders${queryString({ page, pageSize })}`),
  order: (orderNo) => request(`/orders/${encodeURIComponent(orderNo)}`),
  createOrders: (payload, idempotencyKey) => request('/orders', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(payload)
  }),
  // 创建支付单。当前订单页使用 BALANCE，后续仍可复用此方法接入其他渠道。
  createPayment: (orderNo, channel = 'BALANCE') => request(`/orders/${encodeURIComponent(orderNo)}/payment`, {
    method: 'POST',
    body: JSON.stringify({ channel })
  }),
  // 买家只能取消自己的待支付订单；库存恢复由后端 ORDER_CANCELLED 事件异步完成。
  cancelOrder: (orderNo) => request(`/orders/${encodeURIComponent(orderNo)}/cancel`, {
    method: 'POST'
  }),
  // 申请整单退款；退款金额由后端根据原支付记录确定。
  applyRefund: (orderNo, reason) => request(`/orders/${encodeURIComponent(orderNo)}/refunds`, {
    method: 'POST',
    body: JSON.stringify({ reason })
  }),
  confirmReceipt: (orderNo) => request(`/orders/${encodeURIComponent(orderNo)}/receipt`, { method: 'POST' }),

  // 站内聊天。MySQL 是消息事实来源，WebSocket 只负责实时提醒。
  createChatConversation: (merchantId) => request('/chat/conversations', {
    method: 'POST',
    body: JSON.stringify({ merchantId })
  }),
  chatConversations: (page = 1, pageSize = 50) => request(
    `/chat/conversations${queryString({ page, pageSize })}`
  ),
  chatMessages: (conversationId, params = {}) => request(
    `/chat/conversations/${conversationId}/messages${queryString(params)}`
  ),
  sendChatMessage: (conversationId, payload) => request(
    `/chat/conversations/${conversationId}/messages`,
    { method: 'POST', body: JSON.stringify(payload) }
  ),
  sendChatImage: (conversationId, clientMessageId, file) => {
    const formData = new FormData()
    formData.append('clientMessageId', clientMessageId)
    formData.append('file', file)
    return request(`/chat/conversations/${conversationId}/images`, {
      method: 'POST',
      body: formData
    })
  },
  markChatRead: (conversationId, lastReadMessageId) => request(
    `/chat/conversations/${conversationId}/read`,
    { method: 'PUT', body: JSON.stringify({ lastReadMessageId }) }
  ),

  // 买家购物 Agent 第一阶段：会话列表、创建、历史游标分页和硬删除。
  // 会话 ID 是后端雪花 Long，经 Jackson 转成字符串后必须原样拼接，
  // 不要在前端使用 Number() 转换，否则会丢失精度并访问错误的会话。
  agentConversations: (page = 1, pageSize = 50) => request(
    `/agent/conversations${queryString({ page, pageSize })}`
  ),
  createAgentConversation: () => request('/agent/conversations', {
    method: 'POST'
  }),
  agentMessages: (conversationId, params = {}) => request(
    `/agent/conversations/${conversationId}/messages${queryString(params)}`
  ),
  deleteAgentConversation: (conversationId) => request(
    `/agent/conversations/${conversationId}`,
    { method: 'DELETE' }
  ),
  confirmAgentAction: (actionId, idempotencyKey) => request(
    `/agent/actions/${actionId}/confirm`,
    {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey }
    }
  ),
  cancelAgentAction: (actionId) => request(
    `/agent/actions/${actionId}/cancel`,
    { method: 'POST' }
  )
}
