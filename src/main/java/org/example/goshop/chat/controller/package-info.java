/**
 * 聊天模块的 REST 接口适配层。
 *
 * <p>该包只负责接收并校验 HTTP 请求、读取当前登录身份以及返回统一响应；
 * 会话成员校验、消息幂等、订单归属和事务处理必须委托给 {@code service} 层，
 * Controller 不直接访问 Mapper，也不直接向 WebSocket 会话推送消息。</p>
 */
package org.example.goshop.chat.controller;
