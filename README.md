# AI 求职助手：Java 分析后端 + Chrome 插件

在招聘页面读取岗位，结合自己确认过的简历和筛选条件，查看有证据的分析，并围绕当前岗位连续提问。采用 Java / Spring Boot 后端与 WXT、Vue 3、TypeScript、Manifest V3 扩展。

本版本以已完成的后端分析链路和中文插件为基础，替换仓库原有的独立前端及 userscript 应用。旧版本历史保留，替换前快照位于 [`backup/before-ai-assistant-20261110`](https://github.com/litao310294-cyber/ai-job-screening-agent/tree/backup/before-ai-assistant-20261110)。

## 当前能力

- PDF 上传、文本提取、事实检查修正及确认发布；按文件摘要与处理版本去重。索引就绪后切换画像，失败保留原有效画像，重启恢复关键词和向量检索。
- BOSS 岗位只读 Vue 适配、DOM 回退、采集预览修正及手动 JD；保留来源、原始描述和模拟标记，处理异步详情与同 URL 切换。
- 按后端用户保存规则，支持城市、方向、出勤、实习周期、薪资单位和排除词。版本化保存、并发冲突检测；关闭所有条件也是有效配置。只有从未保存时预填可编辑建议，保存后自动恢复。
- 规则返回满足、不满足、待确认；明确违反硬性条件才提前结束，偏好条件不硬拒绝。日期只保存展示；日薪/月薪不自动换算。
- 需求解析 → 混合检索 → 模型分析 → JSON/Schema/业务/证据引用校验。结果展示结论、优势、缺口、风险、建议及绑定版本的简历证据。
- 缓存和数据库结果复用以依赖身份区分，画像、规则及语义版本变化后旧分析过期；同请求标识重试可恢复原操作。
- 多轮非流式聊天、后端历史分页和上下文持久化；问题绑定发送时的岗位/分析/画像/规则版本，支持显式比较、失败恢复与清空代次隔离。
- 中文面板、可调宽度、独立工作区、安全 Markdown、输入法保护、复制及证据展开。标签页各自选择岗位，共享后端用户配置和历史。

当前是**本机单用户、单后端实例**应用。固定演示用户标识为 `demo-li-tao`；名称是演示身份，不附带任何个人简历或预建画像。请自行上传自己的 PDF 并核对事实、规则和模型结果。

## 环境

| 组件 | 要求 / 本次发布验证环境 |
|---|---|
| Java | JDK 21；验证 Microsoft OpenJDK 21.0.12.1 |
| Maven | 使用根目录 Wrapper，自动下载 Maven 3.9.4 和依赖 |
| Node / npm | 验证 Node 24.18.0、npm 11.16.0；锁文件安装 |
| Docker | Docker Desktop Linux 容器模式；MySQL 8.4、Redis 7.4 |
| Chrome | Manifest V3；验证 Chrome 157.0.8103.39 |
| 启动脚本 | Windows PowerShell 5.1，路径相对于当前仓库 |

模型采用 OpenAI-compatible 接口，聊天和 Embedding 可分别配置。真实调用会产生对应服务商费用。服务端保存密钥，扩展不接收模型密钥。

## 从干净克隆启动

以下在 PowerShell 执行。首次联网下载依赖，不需要其他工作区或手工复制 Maven 缓存。

```powershell
git clone https://github.com/litao310294-cyber/ai-job-screening-agent.git
cd ai-job-screening-agent
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/init-demo.ps1
```

初始化生成忽略提交的 `.env.demo.local`，使用随机数据库密码，不覆盖已有文件。`.env.example`只含占位值。默认数据库3307、Redis6380、测试Redis6383；先用 `docker ps` 检查现有服务，端口冲突时修改本地文件中的 `DEMO_MYSQL_PORT`、`DEMO_REDIS_PORT`、`DEMO_TEST_REDIS_PORT`，不要停止其他项目服务。

```powershell
docker compose --env-file .env.demo.local -f compose.demo.yaml up -d
docker compose --env-file .env.demo.local -f compose.demo.yaml ps
```

MySQL健康后，在将启动后端的终端设置以下环境变量（将占位符换成自己的配置；脚本也支持读取当前用户环境变量）：

```powershell
$env:AI_CHAT_BASE_URL='<chat API base URL>'
$env:AI_CHAT_API_KEY='<your chat API key>'
$env:AI_CHAT_MODEL='<chat model ID>'
$env:AI_EMBEDDING_BASE_URL='<embedding API base URL>'
$env:AI_EMBEDDING_API_KEY='<your embedding API key>'
$env:AI_EMBEDDING_MODEL='<embedding model ID>'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/start-demo-real.ps1
```

REAL服务绑定 `127.0.0.1:18080`。另开终端检查：

```powershell
Invoke-RestMethod http://127.0.0.1:18080/api/health
Invoke-RestMethod http://127.0.0.1:18080/api/session
```

构建插件：

```powershell
cd extension
npm ci
npm run typecheck
npm test
npm run build
```

Chrome打开 `chrome://extensions`，启用开发者模式，加载已解压扩展目录 `extension/.output/chrome-mv3`。固定公开公钥对应开发扩展ID记录在 `extension/extension-id.json`，后端脚本读取同一文件精确允许扩展来源；若实际ID不同，更新该公开配置的origin后重启后端。API不允许任意网站来源，后台只访问配置的本机18080/18085地址。

打开BOSS岗位页并刷新，点击“AI 求职”；也可在扩展面板打开独立工作区。连接与恢复中选择 `http://127.0.0.1:18080/api`，确认显示真实服务。重新构建后，在扩展管理页重新加载并刷新原标签页。

## 使用流程

1. 简历：上传自己的PDF（最大10 MiB），整理事实，核查原文与修正结果，再确认发布。加密/损坏/无文本PDF会报明确状态；扫描件可使用文本补救，不内置OCR。
2. 规则：核对出勤、周期和方向等建议。首次建议尚未保存，不是已确认简历事实；根据实际情况修改并点击“保存设置”。已有保存值自动恢复，未保存编辑不会静默参与新分析。
3. 岗位：读取页面，核对标题、公司、城市、地址、薪资、学历、经验、技能和完整JD；缺失保持未知，必要时手动补充。固定模拟岗位有明确标记。
4. 分析：查看结论和优势/缺口，展开逐项要求及原分析绑定的简历证据。硬拒绝会明确提示未做AI分析；待确认并不等于满足条件。
5. 聊天：围绕当前岗位连续提问。输入区Enter发送、Shift+Enter换行；历史由后端保存。清空会影响此后端用户所有岗位聊天，保留简历、规则和分析。修改规则或画像后，重新分析再提问。

## TEST模式与回归

TEST通过测试classpath启动，使用确定性Provider，不调用收费聊天/Embedding。测试画像明确标记，使用独立测试数据库、Redis和索引；生产打包不包含测试Provider。

```powershell
# 仓库根目录；先启动上面的MySQL
docker compose --env-file .env.demo.local -f compose.demo.yaml --profile test up -d
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/start-demo-test.ps1
```

TEST监听18085；插件选择18085应显示测试模式。此启动器不需要真实模型环境变量。REAL与TEST的画像和结果不通用。

普通后端回归使用隔离H2与Testcontainers临时MySQL/Redis，不继承日常3306数据库；Docker可用时执行集成测试。

```powershell
.\mvnw.cmd -B test
.\mvnw.cmd -B package -DskipTests
cd extension
node tests/ui-browser.mjs
node tests/capture-browser.mjs
```

这两个浏览器脚本加载实际Chrome扩展，使用合成数据/模拟宿主页，不代表真实BOSS登录页或真实模型验收。其他真实模型测试由类级开关控制，默认关闭。可选付费分析脚本需显式设置 `RUN_REAL_FLOW=true` 和指向本地岗位导出的 `REAL_CAPTURE_FILE`，并先导入自己的画像；详见[验证说明](docs/validation.md)。不要提交脚本生成的私有结果。

本次发布复测与历史阶段验收分别记录在[发布验收](docs/release-validation.md)；不要将历史统计视为当前重新执行结果。

## 数据、边界与许可

- MySQL：`ai-job-assistant-mysql`，库`ai_job_analyzer`；Docker命名卷`ai_job_assistant_mysql_data`保存到容器`/var/lib/mysql`。规则、事实、操作和聊天持久化于数据库。
- Redis：`ai-job-assistant-redis`，命名卷`ai_job_assistant_redis_data`、AOF；测试Redis使用独立卷。Lucene位于仓库根`lucene-index/`，恢复向量依赖数据库投影及对应模型身份。
- 迁移位于`src/main/resources/db/migration`，启动时Flyway增量执行。不要对其他项目数据库运行本应用。
- 仅本机回环，无多租户登录；不应直接开放公网。进程内协调及上下文策略按单后端实例实现。
- BOSS页面结构、Vue字段和登录状态变化可能需要适配；采集须人工核对。没有自动投递、成功率/匹配分、分布式执行或流式聊天能力。
- 结果必须通过结构及证据约束，合法格式也不代表现实判断绝对准确。模型更换时可能需要调整提示词/契约语义版本及重新构建索引。
- 仓库不含真实简历、岗位导出、聊天、完整模型输入输出、数据库导出、截图或依赖缓存。合成夹具均明确标识。

[架构与接口边界](docs/architecture.md)。项目沿用MIT许可；岗位读取参考boss-helper的只读适配思路，原作者及完整MIT许可见[第三方说明](extension/THIRD_PARTY_NOTICES.md)与`extension/public/boss-helper-LICENSE.txt`。
