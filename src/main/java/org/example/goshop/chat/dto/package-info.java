/**
 * 聊天模块的接口数据传输对象。
 *
 * <p>请求 DTO 在此使用 Bean Validation 描述字段约束；响应 DTO 只暴露聊天页面需要的公开数据。
 * 特别注意：订单卡片不得返回收货地址、手机号等隐私信息，图片只返回短时效签名 URL，
 * 不得把数据库实体直接作为接口响应。</p>
 */
package org.example.goshop.chat.dto;
