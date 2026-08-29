import axios from 'axios'

export const MERCHANT_TOKEN_KEY = 'yougou_merchant_access_token'

const client = axios.create({ baseURL: '/api/v1/merchant', timeout: 15000 })

client.interceptors.request.use((config) => {
  const token = localStorage.getItem(MERCHANT_TOKEN_KEY)
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

client.interceptors.response.use(
  (response) => {
    const body = response.data || {}
    if (body.code !== 0 && body.code !== 200) return Promise.reject(new Error(body.message || '请求失败'))
    return body.data
  },
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem(MERCHANT_TOKEN_KEY)
      if (!location.pathname.startsWith('/login')) location.assign('/login')
    }
    // 403 不清理会话：Token 仍然有效，只是它不是商家作用域或没有资源权限。
    if (error.response?.status === 403) {
      return Promise.reject(new Error(error.response?.data?.message || '无商家权限，请使用商家账号登录'))
    }
    return Promise.reject(new Error(error.response?.data?.message || error.message || '网络请求失败'))
  }
)

const productForm = (product, mainImage) => {
  const form = new FormData()
  form.append('product', JSON.stringify(product))
  if (mainImage) form.append('mainImage', mainImage)
  return form
}

export const merchantApi = {
  sendCode: (phone, purpose = 'LOGIN') => client.post(`/auth/code?purpose=${purpose}`, { phone }),
  login: (phone, code) => client.post('/auth/login', { phone, code }),
  register: (form) => client.post('/auth/register', form),
  logout: () => client.post('/auth/logout'),
  profile: () => client.get('/me'),
  updateProfile: (form) => client.patch('/me', form),
  dashboard: () => client.get('/dashboard'),
  categories: () => client.get('/categories'),
  createCategory: (data) => client.post('/categories', data),
  updateCategory: (id, data) => client.patch(`/categories/${id}`, data),
  deleteCategory: (id) => client.delete(`/categories/${id}`),
  products: (params) => client.get('/products', { params }),
  product: (id) => client.get(`/products/${id}`),
  createProduct: (product, image) => client.post('/products', productForm(product, image)),
  updateProduct: (id, product, image) => client.patch(`/products/${id}`, productForm(product, image)),
  updateProductStatus: (id, status) => client.patch(`/products/${id}/status`, { status }),
  orders: (params) => client.get('/orders', { params }),
  order: (orderNo) => client.get(`/orders/${encodeURIComponent(orderNo)}`),
  shipOrder: (orderNo, data) => client.post(`/orders/${encodeURIComponent(orderNo)}/ship`, data),
  refunds: (params) => client.get('/refunds', { params }),
  refund: (refundNo) => client.get(`/refunds/${encodeURIComponent(refundNo)}`),
  approveRefund: (refundNo, reviewRemark) => client.post(`/refunds/${encodeURIComponent(refundNo)}/approve`, { reviewRemark }),
  rejectRefund: (refundNo, reviewRemark) => client.post(`/refunds/${encodeURIComponent(refundNo)}/reject`, { reviewRemark }),

  // 聊天接口。商家不能主动创建会话，只能回复买家已经创建的会话。
  chatConversations: (page = 1, pageSize = 50) => client.get('/chat/conversations', {
    params: { page, pageSize }
  }),
  chatMessages: (conversationId, params = {}) => client.get(
    `/chat/conversations/${conversationId}/messages`,
    { params }
  ),
  sendChatMessage: (conversationId, data) => client.post(
    `/chat/conversations/${conversationId}/messages`,
    data
  ),
  sendChatImage: (conversationId, clientMessageId, file) => {
    const formData = new FormData()
    formData.append('clientMessageId', clientMessageId)
    formData.append('file', file)
    return client.post(`/chat/conversations/${conversationId}/images`, formData)
  },
  markChatRead: (conversationId, lastReadMessageId) => client.put(
    `/chat/conversations/${conversationId}/read`,
    { lastReadMessageId }
  )
}
