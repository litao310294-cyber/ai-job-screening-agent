# Dedicated integration backend. Run in a separate PowerShell process.
$ErrorActionPreference = 'Stop'
$phase5Root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $phase5Root
. (Join-Path $PSScriptRoot 'demo-env.ps1')
$env:MYSQL_URL = "jdbc:mysql://127.0.0.1:$env:DEMO_MYSQL_PORT/ai_job_assistant_test?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC"
$env:SPRING_DATASOURCE_URL = $env:MYSQL_URL
$env:SPRING_DATASOURCE_USERNAME = $env:MYSQL_USERNAME
$env:SPRING_DATASOURCE_PASSWORD = $env:MYSQL_PASSWORD
$env:REDIS_PORT = $env:DEMO_TEST_REDIS_PORT
$env:LUCENE_ROOT = Join-Path $phase5Root 'target/phase5-acceptance-index'
$phase5Sql = "CREATE DATABASE IF NOT EXISTS ai_job_assistant_test CHARACTER SET utf8mb4; GRANT ALL ON ai_job_assistant_test.* TO 'ai_job_demo'@'%';"
$phase5Sql | & docker exec -i ai-job-assistant-mysql sh -c 'export MYSQL_PWD=$MYSQL_ROOT_PASSWORD; exec mysql -uroot'
if ($LASTEXITCODE -ne 0) { throw 'Could not create isolated test database.' }
Remove-Item Env:AI_CHAT_API_KEY,Env:AI_CHAT_BASE_URL,Env:AI_CHAT_MODEL,Env:AI_EMBEDDING_API_KEY,Env:AI_EMBEDDING_BASE_URL,Env:AI_EMBEDDING_MODEL -ErrorAction SilentlyContinue
& .\mvnw.cmd -B test-compile org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath '-Dmdep.outputFile=target/phase5-classpath.txt' '-DincludeScope=test'
if ($LASTEXITCODE -ne 0) { throw 'Test backend compilation failed.' }
$phase5Classpath = (Join-Path $phase5Root 'target/classes') + ';' + (Join-Path $phase5Root 'target/test-classes') + ';' + (Get-Content target/phase5-classpath.txt -Raw -Encoding UTF8).Trim()
$phase5Origin = (Get-Content extension/extension-id.json -Raw | ConvertFrom-Json).origin
& java -cp $phase5Classpath com.aijob.analyzer.demo.ExtensionDemoTestServer '--spring.profiles.active=phase2a-test,phase5-extension-test' '--server.port=18085' '--app.demo.provider-mode=TEST' "--app.demo.extension-origin=$phase5Origin" '--app.embedding.model=TEST-EMBEDDING' '--app.versions.resume-parser.model=TEST-CHAT' '--app.versions.requirement-parser.model=TEST-CHAT' '--app.versions.final-analysis.model=TEST-CHAT' '--app.versions.chat.model=TEST-CHAT'
exit $LASTEXITCODE
