# hmdp-plus · 本地学习与工程化适配

基于 [java-up-up/hmdp-plus](https://github.com/java-up-up/hmdp-plus) 的公开学习仓库，保留 Apache-2.0 许可和作者署名。上游版本：03594d190275a32d151e5d8b45da24c0f4efbe14。

项目围绕本地生活优惠券抢购，包含短时令牌与限流、分层缓存、Kafka 异步订单、库存轨迹与对账、订阅通知及分片路由。本仓库完成 Windows 本地环境适配和功能验收；这些架构新增主要来自上游，不声称由本次从零设计。

原作者完整介绍保存在 [UPSTREAM_README.md](UPSTREAM_README.md)。实际代码与宣传范围的差异见下方对比文档。

## 文档入口

- [本地构建与启动](docs/local-setup.md)
- [本次运行验收与修复记录](docs/本地构建与验收.md)
- [相对黑马点评的改动对比](docs/与黑马点评的改动对比.md)
- [20 道简历导向面试题与参考答案](docs/简历导向面试问题与答案.md)

原来的 [hmdp-lab](https://github.com/JYHzz1/hmdp-lab) 及其两套面试文档继续保留。

## 本机启动

依赖 JDK 17、Maven、MySQL、Redis、Kafka、Node。完整首次安装和目录说明见启动文档；不要把原版 Java 8 直接用于此项目。

首次准备前端并下载已校验的 Kafka 包：

```powershell
$env:HMDP_JAVA_HOME = '你的JDK17目录'
$env:DB_PASSWORD = '你的本地MySQL密码'
.\scripts\download-kafka.ps1
Push-Location hmdp-vue3
npm ci --no-audit --no-fund
npm run build
Pop-Location
.\scripts\start-local.ps1
```

已构建 jar 时使用 .\scripts\start-local.ps1 -SkipBuild。依赖下载较慢时，可按启动文档使用项目级 Maven 镜像，不更改全局设置。

访问 [http://127.0.0.1:5175/](http://127.0.0.1:5175/)。后端 8085、Redis 6381、Kafka 19092；所有新服务绑定回环。数据库为 hmdpplus_local_0 / hmdpplus_local_1，不覆盖原 hmdp 库。

```powershell
.\scripts\smoke-local.ps1
.\scripts\stop-local.ps1
```

本地验证码是教学模拟，不发送真实短信。冒烟会保留隔离库内的演示券、用户和订单，便于继续查看。

## 本次实际验证

后端 Maven 多模块打包与 Vue 3 构建通过；登录、商铺缓存、券创建、令牌领取/消费、Kafka 异步落库和查单通过。直接检查四张订单物理表，确认一笔订单且两端库存 2→1；取消后库存恢复，快速领令牌触发限流。最终版本连续两轮冒烟通过。

另修复了新增多张券时 selectOne 返回多条的问题，改用已有 Snowflake ID；补充 Java 参数名编译、上传路径配置和可复现脚本。

没有完成持续吞吐对比、多节点 Kafka 故障、跨分片事务故障和全部补偿时序验证。附近查询在当前上游入口被关闭，不能按 README 描述声称全部能力都已启用；支付、核销和生产安全也不是本次验收成果。

## 目录与配置

- hmdp-core-service：核心应用。
- hmdp-redis-tool-framework / hmdp-redisson-framework：缓存、限流、锁、布隆过滤、重复执行与延迟队列组件。
- hmdp-mq-framework：消息收发封装。
- hmdp-sharding / hmdp-id-generator-framework：分片与 ID。
- hmdp-parameter / hmdp-common：参数和公共模型。
- hmdp-vue3：Vue 3 前端。
- sql：上游演示数据，包含 DROP TABLE，只由初始化脚本向新建隔离库导入。
- .local：本地口令配置、下载包、进程数据与日志，忽略提交。

修改数据库口令通过 DB_PASSWORD，生成的 ShardingSphere 文件只保存在 .local。禁止将本地运行日志或 .local 文件提交到公开仓库。
