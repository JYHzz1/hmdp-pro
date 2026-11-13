# hmdp-pro · 连锁门店预约与跨店履约

基于 [hmdp-plus 本地适配版](https://github.com/JYHzz1/hmdp-plus) 的 `467e8b2`，保留原始 [Apache-2.0 许可证](LICENSE) 和 [上游说明](UPSTREAM_README.md)。原版购券继续使用 Redis + Lua、Kafka 和 ShardingSphere。预约域使用独立 MySQL 数据源处理时段容量、预约权益和改期。

## 新增能力

- 同一张券可在同商家多家门店预约，工位按固定一小时的时段计数。取消预约后可以持原券重新预约。
- 跨店改期时保留原预约，目标时段限时占位。确认在同一事务内换位，定时扫描回收过期占位。
- 停业后按用户设置的距离与时间范围筛选候选，比较最近优先与可选项少优先。发出方案时重新验证名额，冲突后重新规划一轮。
- 店员按门店权限、时间窗和预约版本核销。用户命令按请求 ID 重放，保存容量流转事件。

## 本地运行

需要 JDK 17、Maven、MySQL、Redis、Kafka 和 Node。首次安装及启动见 [Windows 启动说明](docs/local-setup.md)。前端为 [127.0.0.1:5176](http://127.0.0.1:5176/booking)，后端 8086，Redis 6382，Kafka 19192。交易库为 `hmdppro_local_0/1`，预约库为 `hmdppro_booking`。

```powershell
node scripts/smoke-booking.mjs
```

在设置本地 `DB_PASSWORD` 后运行。脚本通过真实购券 API 准备两份预约权益，并检查并发预约、改期、核销及数据库计数。使用 `node scripts/smoke-booking.mjs --demo` 可只生成一张演示券、两个持券用户和一个门店操作员。详细操作见 [演示和验收](docs/booking-demo.md)。

## 实现范围

预约回归由 JUnit 和真实 MySQL 执行。策略对比页展示两种贪心与最大流。热点压测和策略数据集的运行条件、结果与复现命令见 [预约测试报告](docs/booking-testing.md)。

预约券通过已落库的正常订单按需登记。预约页面展示改派邀请，门店和排班通过演示脚本初始化。当前开放固定时长的演示服务，退款、真实支付、外部消息通知及后台排班界面属于后续扩展。停业按时段操作。策略对比结果是本地构造样例，适用于展示同一容量下的分配差异。

[Plus 版本验收记录](docs/本地构建与验收.md) · [Plus 面试材料](docs/简历导向面试问题与答案.md)
