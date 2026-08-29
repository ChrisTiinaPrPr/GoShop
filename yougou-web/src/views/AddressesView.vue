<script setup>
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api'

const addresses = ref([])
const loading = ref(true)
const saving = ref(false)
const errorMessage = ref('')
const editingId = ref(null)
const emptyForm = () => ({ receiver: '', phone: '', province: '', city: '', district: '', detail: '', isDefault: false })
const form = reactive(emptyForm())

function resetForm() {
  editingId.value = null
  Object.assign(form, emptyForm())
}

async function loadAddresses() {
  loading.value = true
  errorMessage.value = ''
  try {
    addresses.value = await api.addresses()
  } catch (error) {
    errorMessage.value = error.message || '地址加载失败'
  } finally {
    loading.value = false
  }
}

function editAddress(address) {
  editingId.value = address.id
  Object.assign(form, address, { isDefault: address.isDefault === 1 })
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

async function saveAddress() {
  errorMessage.value = ''
  saving.value = true
  const payload = {
    receiver: form.receiver.trim(), phone: form.phone.trim(), province: form.province.trim(),
    city: form.city.trim(), district: form.district.trim(), detail: form.detail.trim()
  }
  try {
    if (editingId.value) {
      await api.updateAddress(editingId.value, payload)
    } else {
      await api.createAddress({ ...payload, isDefault: form.isDefault })
    }
    resetForm()
    await loadAddresses()
  } catch (error) {
    errorMessage.value = error.message || '地址保存失败'
  } finally {
    saving.value = false
  }
}

async function setDefault(address) {
  try {
    await api.setDefaultAddress(address.id)
    await loadAddresses()
  } catch (error) {
    errorMessage.value = error.message || '设置默认地址失败'
  }
}

async function removeAddress(address) {
  if (!window.confirm(`确认删除“${address.receiver}”的收货地址吗？`)) return
  try {
    await api.removeAddress(address.id)
    if (editingId.value === address.id) resetForm()
    await loadAddresses()
  } catch (error) {
    errorMessage.value = error.message || '地址删除失败'
  }
}

onMounted(loadAddresses)
</script>

<template>
  <section class="content narrow">
    <p class="eyebrow">DELIVERY ADDRESSES</p>
    <h1>收货地址</h1>
    <form class="form-card address-form" @submit.prevent="saveAddress">
      <h2>{{ editingId ? '编辑地址' : '新增地址' }}</h2>
      <label>收货人<input v-model.trim="form.receiver" required maxlength="50"></label>
      <label>手机号<input v-model.trim="form.phone" required inputmode="numeric" maxlength="11"></label>
      <div class="form-grid">
        <label>省<input v-model.trim="form.province" required maxlength="50"></label>
        <label>市<input v-model.trim="form.city" required maxlength="50"></label>
        <label>区/县<input v-model.trim="form.district" required maxlength="50"></label>
      </div>
      <label>详细地址<input v-model.trim="form.detail" required maxlength="200"></label>
      <label v-if="!editingId" class="check-label"><input v-model="form.isDefault" type="checkbox"> 设为默认地址</label>
      <p v-if="errorMessage" class="error">{{ errorMessage }}</p>
      <div class="form-actions"><button class="primary-button" :disabled="saving" type="submit">{{ saving ? '保存中…' : '保存地址' }}</button><button v-if="editingId" class="text-button" type="button" @click="resetForm">取消编辑</button></div>
    </form>

    <p v-if="loading" class="muted">地址加载中…</p>
    <p v-else-if="!addresses.length" class="muted">还没有收货地址，请先新增一条。</p>
    <article v-for="address in addresses" :key="address.id" class="address-card">
      <div>
        <strong>{{ address.receiver }} {{ address.phone }}</strong>
        <p>{{ address.province }}{{ address.city }}{{ address.district }}{{ address.detail }}</p>
        <small v-if="address.isDefault === 1" class="address-default">默认地址</small>
      </div>
      <div class="address-actions">
        <button v-if="address.isDefault !== 1" class="text-button" @click="setDefault(address)">设为默认</button>
        <button class="text-button" @click="editAddress(address)">编辑</button>
        <button class="text-button danger-button" @click="removeAddress(address)">删除</button>
      </div>
    </article>
  </section>
</template>
