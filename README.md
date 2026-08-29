# GoShop 优购商城

GoShop 是一个面向个人学习与项目展示的全栈 B2C 商城，采用模块化单体架构。项目包含买家端、商家端、交易与支付、站内聊天，以及基于受控工具调用和 RAG 的购物助手能力。

## 已实现能力

- 买家验证码登录、商品浏览、收藏、购物车、地址、下单、取消、支付、退款与评价
- 商家注册登录、店铺资料、商品/分类、订单、退款和经营概览管理
- REST 持久化 + STOMP/WebSocket 实时推送的买家—商家聊天
- Spring AI + SSE 的买家购物 Agent，写操作采用二次确认
- 商家私有导购文档解析、Qdrant 向量检索、引用溯源与店铺内问答
- Redis 缓存与幂等控制、RabbitMQ + Transactional Outbox 异步订单事件

完整架构、接口契约、业务状态机和当前完成度见 [开发文档](yougou-mall-development-guide.md)。

## 技术栈

- 后端：Java 17、Spring Boot 4、Spring Security、MyBatis-Plus、MySQL、Redis、RabbitMQ
- AI：Spring AI 2.0、OpenAI-compatible Chat/Embedding、Qdrant
- 前端：Vue 3、Vite、Vue Router；商家端使用 Pinia 与 Element Plus
- 文件与支付：阿里云 OSS、支付宝沙箱

## 本地准备

1. 安装 Java 17、MySQL 8、Redis 7、Node.js，以及按需启用的 RabbitMQ/Qdrant。
2. 复制 `.env.example` 为 `.env`，填写本机连接信息和需要启用的第三方凭据。
3. 根目录执行 `./mvnw test`（Windows 使用 `.\mvnw.cmd test`）。
4. 买家端与商家端分别进入 `yougou-web`、`yougou-merchant-web`，执行 `npm install` 和 `npm run dev`。

RabbitMQ 与 Qdrant 可分别通过 `docker-compose.rabbitmq.yml`、`docker-compose.qdrant.yml` 启动。数据库迁移顺序和端口约定请以开发文档为准。
