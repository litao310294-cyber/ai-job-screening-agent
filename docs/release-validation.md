# 发布验收与来源（2026-11-10）

## 发布来源及历史保护

功能来源为独立开发工作区已认可提交`80313e37b6bcc83024ed830ace3ba29f12f703b3`，包含阶段6后的中文界面和规则记忆。开发工作区当时干净。仅选择源码、迁移、依赖锁、公开配置、第三方许可和合成测试数据，没有导入原核心工程Git历史。

发布分支`release/chinese-assistant-20261110`基于目标仓库原main提交`604368daffc44102c17dead64f3d98fe5531c1f2`。远程备份分支`backup/before-ai-assistant-20261110`已经确认指向同一提交。此次采用追加替换提交，保留原历史，不force push。

## 为独立克隆所做的调整

- 替换旧backend/frontend/userscripts/research目录，采用当前根目录Maven工程与extension源码，保留目标仓库MIT许可和第三方读取适配许可。
- 启动脚本使用标准Maven下载及显式版本classpath插件，不依赖另一个工作区缓存。PowerShell5.1通过兼容的随机字节API生成本地数据库密码。
- Compose使用本项目独立容器/命名卷，数据库和Redis端口可配。发布验收实际用13307、16380、16383，未写入已有3306或日常演示库。
- 真实PDF本机默认路径改为运行时生成的合成PDF；UI测试采用已提交合成夹具，真实脚本必须明确提供私有输入和启用开关，不再暗含个人画像版本或特定岗位的出勤条件。
- UI标题改为通用本机工作区；固定后端demo用户和规则持久化契约保留。没有重构核心分析、改变规则语义、提示词或依赖身份。
- 原有私有验收报告、真实请求/响应和截图不发布；本文件及架构/验证说明提供脱敏结论。合成评测数据保留来源标记，生成结果被忽略。

## 本次重新执行结果

验证环境：Java21.0.12.1、Node24.18.0、npm11.16.0、Maven Wrapper3.9.4、Docker Linux、Chrome157.0.8103.39；在独立目标仓库克隆中执行。

| 检查 | 本次结果与范围 |
|---|---|
| `npm ci` | 成功，0依赖漏洞；安装后正常执行WXT准备和构建 |
| `npm run typecheck` | 通过 |
| `npm test` | 4文件17项通过 |
| `npm run build` | Chrome MV3构建通过 |
| `node tests/ui-browser.mjs` | 实际Chrome加载构建，合成数据/通信替身，18项通过，含硬拒绝、UNKNOWN、过期、Markdown、输入法事件、复制、同请求重试、40轮分页和清空晚返回 |
| `node tests/capture-browser.mjs` | 模拟宿主页7项通过：参考字段、同URL切换、旧详情、选择变化、延迟挂载、DOM及归属冲突；两个后端切换检查未启用 |
| `./mvnw.cmd -B -Dmaven.repo.local=target/release-m2 -DrealChatEval=false test` | 298项，0失败/0错误/3跳过；从空验收缓存正常联网下载依赖 |
| 数据库隔离 | 继承环境MYSQL_URL及SPRING_DATASOURCE_URL指向isolation-canary.invalid；上下文初始化守卫及显式H2/Testcontainers配置通过；实际集成测试使用临时MySQL/Redis |
| `./mvnw.cmd -B -Dmaven.repo.local=target/release-m2 package -DskipTests` | BUILD SUCCESS；生产JAR不含ExtensionDemoTestServer及合成PDF生成器 |
| Windows PowerShell5.1 | 五个启动/配置脚本语法通过；init-demo生成随机密码及demo-env读取自定义端口通过；start-demo-test实际启动TEST服务并返回health=UP |
| `UI_FULL_FLOW=1 node tests/rules-browser.mjs` | 独立库/Redis实际HTTP，10项规则持久化检查通过；两次独立JVM和浏览器进程恢复；规则检查阶段模型审计0行 |
| 原浏览器完整业务回归 | 合成PDF1003字节实际扩展上传、结构化/修正/发布、重复文件、规则、分析、三轮聊天、刷新、冲突和晚返回通过；CORS预检204、非扩展来源403；页面错误0；测试Provider，收费调用0 |

后端3项跳过分别为RealChatEvalV1Test、FormalRetrievalEvalV1Test、DemoRedisConnectionTest，类级开关默认关闭。Trace故障注入产生预期ERROR日志，测试验证业务隔离后通过，不应把这些日志计为测试失败。PDF验证使用合成内容，未因缺少开发者个人PDF跳过。

生产JAR的单独REAL健康检查未执行：自动审批拒绝“启动临时生产JAR验收进程，并在结束时停止该进程”的命令，返回`blocked by policy`，没有细项；未换方式绕过。本次生产构建与TEST实际启动/HTTP回归已完成，不把它们称为REAL启动实测。发布本身未新增真实聊天或Embedding调用。历史已认可真实链路不作为此次新运行统计。

## 内容审查

已检查待提交文件和内容，排除.env.demo.local、真实简历/联系方式、真实岗位导出、聊天原文、模型完整输入输出、数据库导出、日志、缓存、浏览器配置、截图、target、node_modules、Maven缓存及Lucene。文件级忽略之外，执行索引审查和敏感模式扫描。

替换前远程可达398个blob及当前发布文件经已知Provider/GitHub密钥、私钥和明文凭据模式扫描，没有发现疑似秘密。这是检查范围内的结果，不宣称能够证明历史绝无任何秘密；本次没有重写远程历史。

本机原始日志和自动化截图仅保留在忽略路径或发布审计目录，不随发布上传。源码和公开锁文件、公钥、MIT许可保留。首次建议只在后端无保存行的v0表单出现，已有用户配置不重置；该行为和当前API版本一并保留。

## 验证限制

本次Chrome自动化使用独立配置及模拟宿主页，没有控制用户登录BOSS的个人会话；Windows中文输入法为composition事件回归，真实输入仍可人工检查。当前页面适配沿用已通过真实页面验收的实现，页面变化仍可能需要更新。真实Provider不在默认CI中开启；没有把模拟回归称为新的真实模型质量测试。
