<script setup>
import { onMounted, ref } from 'vue'
import { merchantApi } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'
const props=defineProps({refundNo:{type:String,required:true}});const refund=ref(null);const remark=ref('');const loading=ref(false);const money=c=>`¥${(Number(c||0)/100).toFixed(2)}`
const statusLabels={PENDING:'待审核',SUCCESS:'已退款',REJECTED:'已拒绝',PROCESSING:'处理中',FAILED:'退款失败'}
async function load(){try{refund.value=await merchantApi.refund(props.refundNo)}catch(e){ElMessage.error(e.message)}}
async function review(action){const verb=action==='approve'?'通过':'拒绝';await ElMessageBox.confirm(`确认${verb}该退款申请？`);loading.value=true;try{refund.value=action==='approve'?await merchantApi.approveRefund(props.refundNo,remark.value):await merchantApi.rejectRefund(props.refundNo,remark.value);ElMessage.success(`已${verb}`)}catch(e){ElMessage.error(e.message)}finally{loading.value=false}}
onMounted(load)
</script>
<template><el-card v-if="refund"><template #header><strong>退款单 {{refund.refundNo}}</strong></template><dl class="detail-grid"><dt>订单号</dt><dd>{{refund.orderNo}}</dd><dt>退款金额</dt><dd>{{money(refund.amountCent)}}</dd><dt>支付渠道</dt><dd>{{refund.paymentChannel}}</dd><dt>退款原因</dt><dd>{{refund.reason}}</dd><dt>当前状态</dt><dd>{{statusLabels[refund.refundStatus]||refund.refundStatus}}</dd></dl><template v-if="refund.refundStatus==='PENDING'"><el-divider>审核</el-divider><el-input v-model="remark" type="textarea" :rows="4" maxlength="255" placeholder="审核意见"/><div style="margin-top:16px"><el-button type="success" :disabled="refund.paymentChannel!=='BALANCE'" :loading="loading" @click="review('approve')">通过并退款</el-button><el-button type="danger" :loading="loading" @click="review('reject')">拒绝</el-button></div><el-alert v-if="refund.paymentChannel!=='BALANCE'" title="第一期不支持支付宝自动退款，只能拒绝或等待后续接入。" type="warning" style="margin-top:16px"/></template></el-card></template>
