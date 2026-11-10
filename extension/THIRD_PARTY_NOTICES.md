# 第三方许可

岗位 Vue 适配参考用户提供的 boss-helper-0.5.0.zip 中 src/composables/useVue.ts 和 src/entrypoints/boss/index.ts。保留 DOM 节点 __vue__ 读取及 jobDetail 适配思路，重写为只读、白名单、短超时桥接。未复用投递、聊天、接口抓取或页面修改功能。

原作者 Ocyss，MIT Copyright (c) 2024 Ocyss(git@ocyss.icu)。完整许可见 public/boss-helper-LICENSE.txt，构建目录保留同文件。项目其他依赖许可随 npm 包提供，精确版本见 package-lock.json。

public-key.json 仅包含用于稳定开发扩展 ID 的公开公钥，不含私钥或模型凭据。
