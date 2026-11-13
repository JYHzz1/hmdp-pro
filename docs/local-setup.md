# Windows 本地构建与启动

本说明针对本机隔离演示环境，使用 PowerShell。所有命令从仓库根目录执行。

## 依赖与端口

| 依赖 | 本次使用 | 本地端口 |
|---|---|---|
| Java | JetBrains Runtime 17.0.7，包含 javac | — |
| Maven | 3.9.14 | — |
| MySQL | 8.0.45，已有服务 | 3306 |
| Redis | 5.0.14.1，单独实例 | 6381 |
| Kafka | 3.9.1，单机 KRaft | 19092 / 控制器 19093 |
| 后端 | Spring Boot 3.5.4 | 8085 |
| 前端 | Node 24.18.0、Vue 3 / Vite | 5175 |

所有新启动网络服务只绑定 127.0.0.1。使用 hmdpplus_local_0 / hmdpplus_local_1，不重建原来的 hmdp 库。Redis 键与 Kafka topic 采用 hmdpplus-local 前缀，并且与旧版 Redis 进程隔离。

## 首次准备

指定现有 JDK 17。脚本会优先读取 HMDP_JAVA_HOME，本机默认路径是 PyCharm 自带的 JBR；其他机器必须改为自己的 JDK 路径，不更改系统 Java。

```powershell
$env:HMDP_JAVA_HOME = 'C:\Program Files\JetBrains\PyCharm Community Edition 2023.2\jbr'
$env:JAVA_HOME = $env:HMDP_JAVA_HOME
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:DB_PASSWORD = '你的本地MySQL密码'
.\scripts\download-kafka.ps1
Push-Location hmdp-vue3
npm ci --no-audit --no-fund
npm run build
Pop-Location
.\scripts\start-local.ps1
```

Redis 程序默认使用同级 ../redis/redis-server.exe。如安装位置不同，可以先调用 start-infra.ps1 -RedisHome 指定目录，再执行启动脚本。MySQL 客户端 mysql、Maven、node、tar 需要在 PATH 中。脚本面向本地 root 用户与 3306 端口。

Kafka 包从 Apache 官方归档下载，提取前验证 SHA-512。它、数据目录、日志和构建产物都不提交 Git。

首次 Maven 依赖较多。本次使用了 .local/maven-settings.xml 中的项目级镜像；该文件忽略提交，不影响全局配置。需要复用时自行生成：

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <mirrors><mirror><id>local-fast-central</id><mirrorOf>*</mirrorOf>
    <url>https://maven.aliyun.com/repository/public</url>
  </mirror></mirrors>
</settings>
```

然后先执行 mvn -s .local/maven-settings.xml -B -DskipTests package，成功后运行 .\scripts\start-local.ps1 -SkipBuild。

## 初始化行为

init-local.ps1 只在两个指定演示库不存在时导入数据；发现已有库就保留，不重复导入 DROP TABLE 脚本。若首次导入中断，要先检查这个演示库，不要盲目覆盖。

本地数据源配置从模板生成到 .local/shardingsphere-local.yaml，口令来自 DB_PASSWORD。后端通过 ShardingSphere absolutepath URL 读取它；公共源码没有本机口令。

上游启动初始化会清理本项目部分缓存并调整演示券时间，这是为什么必须隔离运行。新建测试券、订单和用户只留在两个演示库。不要将启动脚本指向生产数据。

## 启动与验证

已有最终 jar 时：

```powershell
$env:DB_PASSWORD = '你的本地MySQL密码'
.\scripts\start-local.ps1 -SkipBuild
.\scripts\smoke-local.ps1
```

页面：http://127.0.0.1:5175/。后端：http://127.0.0.1:8085。前端 /api 代理到后端，不需要原版的 80 端口 Nginx。

登录验证码由本地接口返回和日志打印，不发送真实短信。冒烟脚本会创建演示用户和券，验证令牌、Kafka 落库、查单、物理表数量、两端库存、重复令牌拒绝和指标接口；演示数据保留以供检查。它不是吞吐测试。

原仓库包含教学测试和环境依赖实验，本次未无选择地运行全部 mvn test。实际验收见《本地构建与验收》。

## 日志与停止

日志位于 .local/backend.out.log、backend.err.log、frontend.out.log、kafka.err.log 等文件。原始运行日志不上传公共仓库，可能含演示验证码和会话信息。

```powershell
.\scripts\stop-local.ps1
```

停止脚本核对记录的进程身份，只停止本项目服务，不停止 MySQL。再次构建后端前应先停止旧后端，避免 Windows 锁住正在运行的 jar。
