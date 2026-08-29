<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { merchantApi } from '../api'
import { ElMessage } from 'element-plus'
const router=useRouter();const rows=ref([]);const total=ref(0);const q=reactive({page:1,pageSize:20,status:''});const labels={PENDING:'待审核',SUCCESS:'已退款',REJECTED:'已拒绝',PROCESSING:'处理中',FAILED:'失败'};const money=c=>`¥${(Number(c||0)/100).toFixed(2)}`
async function load(){try{const d=await merchantApi.refunds(q);rows.value=d.records||[];total.value=Number(d.total||0);q.page=Number(d.page||q.page)}catch(e){ElMessage.error(e.message)}}onMounted(load)
</script>
<template><el-card><div class="toolbar"><el-select v-model="q.status" placeholder="全部状态" clearable><el-option v-for="(v,k) in labels" :key="k" :label="v" :value="k"/></el-select><el-button @click="q.page=1;load()">查询</el-button></div><el-table :data="rows"><el-table-column prop="refundNo" label="退款单号" width="210"/><el-table-column prop="orderNo" label="订单号" width="210"/><el-table-column label="金额"><template #default="s">{{money(s.row.amountCent)}}</template></el-table-column><el-table-column prop="paymentChannel" label="支付渠道"/><el-table-column label="状态"><template #default="s">{{labels[s.row.refundStatus]||s.row.refundStatus}}</template></el-table-column><el-table-column label="操作"><template #default="s"><el-button link @click="router.push(`/refunds/${s.row.refundNo}`)">审核</el-button></template></el-table-column></el-table><div class="pagination"><el-pagination v-model:current-page="q.page" :page-size="q.pageSize" :total="total" @current-change="load" layout="prev,pager,next,total"/></div></el-card></template>
