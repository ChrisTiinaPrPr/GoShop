/**
 * 聊天模块的 STOMP/WebSocket 接入与事件发布层。
 *
 * <p>该包负责 WebSocket 端点、STOMP CONNECT 阶段的 JWT 身份建立、
 * 用户专属订阅授权以及事务提交后的事件推送。首版 WebSocket 只承担实时通知，
 * 不接受客户端通过 STOMP SEND 写入聊天消息；断线期间的数据由 REST 增量查询补齐。</p>
 */
package org.example.goshop.chat.websocket;
