/**
 * 联系商家模块的配置绑定与启动期校验。
 *
 * <p>该包只保存跨聊天子模块共享的配置对象；WebSocket、图片上传和限流实现通过构造器注入
 * 读取配置，禁止在业务代码中直接读取环境变量或散落默认值。</p>
 */
package org.example.goshop.chat.config;
