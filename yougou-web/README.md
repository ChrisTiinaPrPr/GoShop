# 优购商城消费者端

## 启动

```powershell
npm.cmd install
npm.cmd run dev
```

浏览器访问 `http://localhost:5173`。Vite 已将 `/api` 代理到 `http://localhost:8080`。

## 后端联调约定

前端请求由 `src/api.js` 统一管理，默认 API 前缀为 `/api/v1`，响应格式固定为：

```json
{ "code": 0, "message": "success", "data": {} }
```

后端按开发文档实现认证、商品、购物车与订单端点后，页面会自动采用真实数据；接口未可用时，首页、详情和购物车使用演示数据，方便先确认交互与布局。

## 放入 GoShop 项目

将本目录复制为 `E:\JAVA\GoShop\yougou-web`。开发阶段运行 Vite；生产阶段执行 `npm.cmd run build`，再将 `dist` 由 Nginx 托管并反向代理 `/api` 到 Spring Boot。
