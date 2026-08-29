import { createRouter, createWebHistory } from 'vue-router'
import { MERCHANT_TOKEN_KEY } from './api'
import MerchantLayout from './layouts/MerchantLayout.vue'
import LoginView from './views/LoginView.vue'
import RegisterView from './views/RegisterView.vue'
import DashboardView from './views/DashboardView.vue'
import ProfileView from './views/ProfileView.vue'
import CategoriesView from './views/CategoriesView.vue'
import ProductsView from './views/ProductsView.vue'
import ProductFormView from './views/ProductFormView.vue'
import OrdersView from './views/OrdersView.vue'
import OrderDetailView from './views/OrderDetailView.vue'
import RefundsView from './views/RefundsView.vue'
import RefundDetailView from './views/RefundDetailView.vue'
import ChatView from './views/ChatView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView, meta: { guest: true } },
    { path: '/register', component: RegisterView, meta: { guest: true } },
    { path: '/', component: MerchantLayout, meta: { auth: true }, children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', component: DashboardView, meta: { title: '经营概览' } },
      { path: 'profile', component: ProfileView, meta: { title: '店铺资料' } },
      { path: 'categories', component: CategoriesView, meta: { title: '店内分类' } },
      { path: 'products', component: ProductsView, meta: { title: '商品管理' } },
      { path: 'products/new', component: ProductFormView, meta: { title: '新增商品' } },
      { path: 'products/:id/edit', component: ProductFormView, props: true, meta: { title: '编辑商品' } },
      { path: 'orders', component: OrdersView, meta: { title: '订单管理' } },
      { path: 'orders/:orderNo', component: OrderDetailView, props: true, meta: { title: '订单详情' } },
      { path: 'refunds', component: RefundsView, meta: { title: '退款审核' } },
      { path: 'refunds/:refundNo', component: RefundDetailView, props: true, meta: { title: '退款详情' } },
      { path: 'messages/:conversationId?', component: ChatView, meta: { title: '客户消息' } }
    ] }
  ]
})

router.beforeEach((to) => {
  const loggedIn = Boolean(localStorage.getItem(MERCHANT_TOKEN_KEY))
  if (to.meta.auth && !loggedIn) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.meta.guest && loggedIn) return '/dashboard'
})

export default router
