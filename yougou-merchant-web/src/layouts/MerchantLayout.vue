<script setup>
import { onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
onMounted(() => auth.loadProfile().catch(() => {}))
async function logout() { await auth.logout(); router.replace('/login') }
</script>

<template>
  <el-container class="shell">
    <el-aside width="220px" class="sidebar">
      <div class="logo">优购商家后台</div>
      <el-menu router :default-active="route.path" background-color="#17202d" text-color="#d9e0e8" active-text-color="#fff">
        <el-menu-item index="/dashboard">经营概览</el-menu-item>
        <el-menu-item index="/products">商品管理</el-menu-item>
        <el-menu-item index="/categories">店内分类</el-menu-item>
        <el-menu-item index="/orders">订单管理</el-menu-item>
        <el-menu-item index="/refunds">退款审核</el-menu-item>
        <el-menu-item index="/messages">客户消息</el-menu-item>
        <el-menu-item index="/profile">店铺资料</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <strong>{{ route.meta.title }}</strong>
        <div class="merchant-head">
          <el-avatar :src="auth.profile?.logoUrl">{{ auth.profile?.name?.slice(0, 1) }}</el-avatar>
          <span>{{ auth.profile?.name || '商家' }}</span>
          <el-button link type="danger" @click="logout">退出</el-button>
        </div>
      </el-header>
      <el-main><RouterView /></el-main>
    </el-container>
  </el-container>
</template>
