<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { merchantApi } from '../api'
import { ElMessage } from 'element-plus'
const router=useRouter();const rows=ref([]);const total=ref(0);const q=reactive({page:1,pageSize:20,status:'',orderNo:'',startAt:'',endAt:''})
const createdRange=ref([])
const labels={PENDING_PAYMENT:'待付款',WAITING_SHIPMENT:'待发货',WAITING_RECEIPT:'待收货',COMPLETED:'已完成',CANCELLED:'已取消',REFUNDING:'退款中',REFUNDED:'已退款'}
const money=c=>`¥${(Number(c||0)/100).toFixed(2)}`
async function load(){
  // 日期组件输出后端可直接解析的 ISO LocalDateTime；清空日期时不发送空参数。
  q.startAt=createdRange.value?.[0]||'';q.endAt=createdRange.value?.[1]||''
  try{const d=await merchantApi.orders(q);rows.value=d.records||[];total.value=Number(d.total||0);q.page=Number(d.page||q.page)}catch(e){ElMessage.error(e.message)}
}
onMounted(load)
</script>
<template><el-card><div class="toolbar"><el-input v-model="q.orderNo" placeholder="订单号" clearable style="width:240px"/><el-select v-model="q.status" placeholder="全部状态" clearable style="width:150px"><el-option v-for="(v,k) in labels" :key="k" :label="v" :value="k"/></el-select><el-date-picker v-model="createdRange" type="datetimerange" start-placeholder="开始时间" end-placeholder="结束时间" value-format="YYYY-MM-DDTHH:mm:ss"/><el-button @click="q.page=1;load()">查询</el-button></div><el-table :data="rows"><el-table-column prop="orderNo" label="订单号" width="210"/><el-table-column label="状态"><template #default="s">{{labels[s.row.status]||s.row.status}}</template></el-table-column><el-table-column prop="receiver" label="收货人"/><el-table-column label="金额"><template #default="s">{{money(s.row.payAmountCent)}}</template></el-table-column><el-table-column prop="createdAt" label="下单时间" width="180"/><el-table-column label="操作"><template #default="s"><el-button link @click="router.push(`/orders/${s.row.orderNo}`)">详情</el-button></template></el-table-column></el-table><div class="pagination"><el-pagination v-model:current-page="q.page" :page-size="q.pageSize" :total="total" @current-change="load" layout="prev,pager,next,total"/></div></el-card></template>
