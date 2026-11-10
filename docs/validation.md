# 可复现验证与数据边界

## 默认回归

根目录执行`./mvnw.cmd -B test`。普通Spring测试显式使用隔离H2；Testcontainers自行创建临时MySQL/Redis。真实模型评测`realChatEval`、`realProviderEval`和本机Redis验证`demoRedisPing`由测试类级开关控制，默认跳过；不依赖方法内提前return隔离。

PDF回归默认生成带`SYNTHETIC TEST ONLY`标记的内容；可显式传`-DdemoResumePdf=<local-file>`读取自己的PDF，但结果及文件均不得提交。仓库不携带任何PDF。合成文本与`docs/eval/datasets`不代表真实候选人事实或当前模型质量。

前端在`extension`运行`npm ci`、`npm run typecheck`、`npm test`、`npm run build`。随后`node tests/ui-browser.mjs`使用已提交的合成夹具，在Chrome实际加载构建扩展但替换通信；`node tests/capture-browser.mjs`使用本机结构模拟页面验证采集，不需要真实BOSS账户。采集脚本中的两个本机服务切换检查需`RUN_BACKEND_SWITCH=true`并预先配置两套服务，默认单独记录跳过。

## 实际HTTP与隔离持久化回归

先按README初始化配置，启动MySQL和测试Redis，关闭其他18085服务。编译测试类和classpath，再生成临时PDF（Windows命令）：

```powershell
.\mvnw.cmd -B test-compile org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath -Dmdep.outputFile=target/phase5-classpath.txt -DincludeScope=test
$testClasspath = 'target/classes;target/test-classes;' + (Get-Content target/phase5-classpath.txt -Raw -Encoding UTF8).Trim()
java -cp $testClasspath com.aijob.analyzer.demo.SyntheticResumeFixture
cd extension
$env:UI_FULL_FLOW='1'
node tests/rules-browser.mjs
Remove-Item Env:UI_FULL_FLOW
```

脚本读取本地配置的数据库与测试Redis端口，在本仓库专用容器`ai-job-assistant-mysql`创建`ai_job_ui_<timestamp>`验收库，授权应用用户。测试启动器移除真实模型环境参数，只使用测试Provider。记录两次独立JVM及浏览器重启、规则保存/失败/冲突/空值/全部关闭恢复；后续完整流程使用临时合成PDF实际上传及聊天。结束停止其自身JVM，保留验收库和`target`证据便于复核，不删除日常数据。

## 可选真实验证

手动上传并确认自己的简历、保存规则，在登录BOSS页面核对采集，提交分析和至少三轮连续问答，是最终真实链路验收。与模拟页面/Provider测试分开记录。历史已完成的本机真实验收不包含在本次重新执行统计中。

`node tests/ui-real-read.mjs`只读当前18080后端保存的分析和历史；可设`UI_ANALYSIS_ID`，不再依赖开发者历史文件。需要已有成功分析与聊天，记录实际可见条数。该脚本仅GET，不统计Provider审计用量，不应据此宣称重新生成真实分析。

`node tests/real-browser.mjs`是显式付费冒烟：需设置`RUN_REAL_FLOW=true`和`REAL_CAPTURE_FILE=<your-local-export.json>`，后端须REAL且已有画像。脚本通过实际扩展后台保存岗位、按当前画像/规则版本提交分析和三个问题，并校验证据归属；不改规则，不替用户补岗位出勤周期。私有输出只在忽略的`target/real-e2e-evidence`，不能提交。

真实评测可通过`./mvnw.cmd -Dtest=RealChatEvalV1Test -DrealChatEval=true test`显式启用，读取环境中的模型配置。查询Embedding评测需`-DrealProviderEval=true`。不要在共享CI默认打开付费测试；每次记录模型、角色、用量和分层校验结果。

## 发布卫生

根目录、extension均有忽略规则，但发布审查同时检查实际文件和Git索引。`target`、`node_modules`、`.m2`、`.env.demo.local`、Lucene、上传文件、浏览器配置、截图、日志和`docs/eval/results`不发布。正式模型结果文件即使由测试生成，也属于本地证据。所有依赖锁文件、公开扩展公钥和第三方MIT许可保留。
