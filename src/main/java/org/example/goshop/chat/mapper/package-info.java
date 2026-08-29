/**
 * 聊天模块的数据访问层。
 *
 * <p>Mapper 负责会话和消息的数据库读写，包括会话唯一查询、消息游标分页、
 * 幂等消息查询及已读游标的条件更新。跨表业务规则由 {@code service} 层组织，
 * 不在 Controller 中拼装 SQL。</p>
 */
package org.example.goshop.chat.mapper;
