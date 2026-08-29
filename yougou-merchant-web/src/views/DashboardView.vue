<script setup>
import { onMounted, ref } from 'vue'
import { merchantApi } from '../api'
import { ElMessage } from 'element-plus'
const data = ref({}); const money = (cent) => `¥${(Number(cent || 0) / 100).toFixed(2)}`
onMounted(async () => { try { data.value = await merchantApi.dashboard() } catch(e) { ElMessage.error(e.message) } })
const metrics = [ ['todayPaidOrderCount','今日支付订单'], ['todayPaidAmountCent','今日有效成交额'], ['waitingShipmentCount','待发货'], ['pendingRefundCount','待审退款'], ['onSaleProductCount','在售商品'], ['lowStockSkuCount','低库存 SKU'] ]
</script>
<template><div class="metric-grid"><el-card v-for="item in metrics" :key="item[0]" class="metric"><span>{{ item[1] }}</span><strong>{{ item[0] === 'todayPaidAmountCent' ? money(data[item[0]]) : Number(data[item[0]] || 0) }}</strong></el-card></div></template>
