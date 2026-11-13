# hmdp-pro · 连锁门店预约与跨店履约

基于 [hmdp-plus 本地适配版](https://github.com/JYHzz1/hmdp-plus) 的 `467e8b2`，保留原始 [Apache-2.0 许可证](LICENSE) 和 [上游说明](UPSTREAM_README.md)。原版购券继续使用 Redis + Lua、Kafka 和 ShardingSphere。预约域使用独立 MySQL 数据源处理时段容量、预约权益和改期。

## 新增能力

- 同一张券可在同商家多家门店预约，工位按固定一小时的时段计数。取消预约后可以持原券重新预约。
- 用户主动改期在同一事务内确认新工位并释放旧工位，目标满位时保留原预约。
- 停约后可自行换时段，店员也可分批取消受影响预约并恢复服务券。运营可选用改派对比工具，经人工确认后发出限时备选邀请。
- 店员按门店权限、时间窗和预约版本核销。用户命令按请求 ID 重放，保存容量流转事件。

## 本地运行

需要 JDK 17、Maven、MySQL、Redis、Kafka 和 Node。首次安装及启动见 [Windows 启动说明](docs/local-setup.md)。前端为 [127.0.0.1:5176](http://127.0.0.1:5176/booking)，后端 8086，Redis 6382，Kafka 19192。交易库为 `hmdppro_local_0/1`，预约库为 `hmdppro_booking`。

```powershell
node scripts/smoke-booking.mjs
```

在设置本地 `DB_PASSWORD` 后运行。脚本通过真实购券 API 准备两份预约权益，并检查并发预约、改期、核销及数据库计数。使用 `node scripts/smoke-booking.mjs --demo` 可只生成一张演示券、两个持券用户和一个门店操作员。详细操作见 [演示和验收](docs/booking-demo.md)。

## 实现范围

预约回归由 JUnit 和真实 MySQL 执行。策略对比页展示两种贪心与最大流。热点压测和策略数据集的运行条件、结果与复现命令见 [预约测试报告](docs/booking-testing.md)。

预约券通过已落库的正常订单按需登记。当前使用固定时长、单类工位容量和单预约库，时段查询最多返回 300 项，门店和排班通过演示脚本初始化。门店取消后在预约页显示原因，恢复的是可用券权益。真实退款、站外通知、客服工单及多资源排班属于后续扩展。策略对比结果来自本地合成样本。

[Plus 版本验收记录](docs/本地构建与验收.md) · [Plus 面试材料](docs/简历导向面试问题与答案.md)
