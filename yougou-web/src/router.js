import { createRouter, createWebHistory } from 'vue-router'
import HomeView from './views/HomeView.vue'
import LoginView from './views/LoginView.vue'
import ProductView from './views/ProductView.vue'
import CartView from './views/CartView.vue'
import OrdersView from './views/OrdersView.vue'
import OrderDetailView from './views/OrderDetailView.vue'
import CheckoutView from './views/CheckoutView.vue'
import ProfileView from './views/ProfileView.vue'
import AddressesView from './views/AddressesView.vue'
import MerchantView from './views/MerchantView.vue'
import ChatView from './views/ChatView.vue'
import AssistantView from './views/AssistantView.vue'
import FavoritesView from './views/FavoritesView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: HomeView },
    { path: '/login', component: LoginView },
    { path: '/product/:id', component: ProductView, props: true },
    { path: '/cart', component: CartView, meta: { requiresAuth: true } },
    { path: '/checkout', component: CheckoutView, meta: { requiresAuth: true } },
    { path: '/orders', component: OrdersView, meta: { requiresAuth: true } },
    { path: '/orders/:orderNo', component: OrderDetailView, props: true, meta: { requiresAuth: true } },
    { path: '/profile', component: ProfileView, meta: { requiresAuth: true } },
    { path: '/addresses', component: AddressesView, meta: { requiresAuth: true } },
    { path: '/favorites', component: FavoritesView, meta: { requiresAuth: true } },
    { path: '/messages/:conversationId?', component: ChatView, meta: { requiresAuth: true, fullHeight: true } },
    { path: '/assistant/:conversationId?', component: AssistantView, meta: { requiresAuth: true, fullHeight: true } },
    { path: '/merchant/:id', component: MerchantView, props: true }
  ],
  scrollBehavior: () => ({ top: 0 })
})

// 路由守卫只改善体验；接口的 JWT 校验仍由后端负责。
router.beforeEach((to) => {
  if (to.meta.requiresAuth && !localStorage.getItem('yougou_buyer_access_token')) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
})

export default router
