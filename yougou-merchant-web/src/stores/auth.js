import { defineStore } from 'pinia'
import { merchantApi, MERCHANT_TOKEN_KEY } from '../api'

export const useAuthStore = defineStore('merchant-auth', {
  state: () => ({ token: localStorage.getItem(MERCHANT_TOKEN_KEY), profile: null }),
  getters: { loggedIn: (state) => Boolean(state.token) },
  actions: {
    saveSession(result) {
      this.token = result.accessToken
      localStorage.setItem(MERCHANT_TOKEN_KEY, result.accessToken)
    },
    async loadProfile() { this.profile = await merchantApi.profile() },
    async logout() {
      try { await merchantApi.logout() } catch (_) { /* 本地退出不依赖服务端 */ }
      this.token = null
      this.profile = null
      localStorage.removeItem(MERCHANT_TOKEN_KEY)
    }
  }
})
