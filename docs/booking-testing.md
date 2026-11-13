# 预约回归、改派实验与热点压测

2026-11-13 本地记录。Windows、i5-1155G7（4 核 8 线程）、15.8 GiB 内存、Java 17、MySQL 8.0.45、预约 Hikari 池 8 个连接。应用、数据库和 k6 共用一台机器。

## JUnit 和 MySQL

本地运行 10 项，失败 0，跳过 0。规划器有两个测试，覆盖贪心反例及 100 个小图和穷举结果的一致性。真实 MySQL 覆盖 8 个场景：

- 8 个并发请求使用同一个请求 ID，均返回版本 1。数据库写入 1 条命令、1 条预约流水和 1 个正式名额。同键改参数被拒绝。
- 两份权益竞争一个工位，仅一笔预约成功。
- 过期清理与迟到确认竞争，原预约仍有效，目标占位释放。
- 用数据库触发器在换位中途注入失败，新旧时段容量一起回滚，原请求可以安全重试。
- 主动改期与取消同一版本竞争，只有一个状态变更成功。
- 目标时段满位，原预约保持不变。
- 已停约时段结束后，定时任务取消预约并恢复服务券，清理待确认邀请。
- 店员在时段结束前批量取消，重复执行不重复返还，用户可重新预约。

测试使用随机命名的独立临时库，退出时清理。CI 使用 MySQL 服务容器。完整购券链路和页面接口仍可用 Node 冒烟复现。

```powershell
$env:BOOKING_TEST_MYSQL = 'jdbc:mysql://127.0.0.1:3306'
$env:BOOKING_TEST_PASSWORD = $env:DB_PASSWORD
mvn -B -pl hmdp-core-service -am "-Dtest=AssignmentPlannerTest,BookingMysqlTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
node scripts/smoke-booking.mjs
```

## 改派策略

固定种子生成稀缺、混合和充足三组候选图，每组 30 份快照，每份 300 笔预约、100 个候选时段。每份快照用相同候选和容量运行三种策略。下表统计可分配的预约，发出占位与用户确认分别由业务链路验收。

| 场景 | 最近优先 | 可选项少优先 | 最大流 |
|---|---:|---:|---:|
| 稀缺 | 7,707 / 9,000 | 8,094 / 9,000 | 8,680 / 9,000 |
| 混合 | 8,227 / 9,000 | 8,685 / 9,000 | 9,000 / 9,000 |
| 充足 | 9,000 / 9,000 | 9,000 / 9,000 | 9,000 / 9,000 |

稀缺组分别为 85.63%、89.93%、96.44%。平均分配距离分别为 4.206、4.514 和 4.907 km，计算时各策略只纳入自己的已分配订单。规划 P95 在一轮独立进程样本中分别为 1.251、1.712 和 3.462 ms。该计时包含 JVM 波动，仅用于本地观察。最大流优化可安排人数，实际发邀仍采用可选项少优先。

```powershell
New-Item -ItemType Directory -Force .local/benchmark-classes | Out-Null
javac -encoding UTF-8 -d .local/benchmark-classes hmdp-core-service/src/main/java/org/javaup/booking/AssignmentPlanner.java hmdp-core-service/src/test/java/org/javaup/booking/AssignmentBenchmark.java
java -cp .local/benchmark-classes org.javaup.booking.AssignmentBenchmark
```

## 预约接口压测

k6 2.3.0 按固定到达率请求预约接口。预先写入不同订单权益，使用同一个测试用户会话，避免登录与购券干扰预约写入测量。各组用新数据，容量充足组全程留有可用名额。预热后按 20 秒窗口运行，压测端最大 120 VU。

| 目标请求/秒 | 时段数 | 成功笔数 | 实际成功笔/秒 | 成功 P95 / P99 | k6 未发出 |
|---:|---:|---:|---:|---:|---:|
| 100 | 1 | 2,000 | 100.0 | 10.47 / 18.77 ms | 0 |
| 100 | 20 | 2,001 | 100.0 | 10.18 / 16.34 ms | 0 |
| 250 | 1 | 4,265 | 208.1 | 653.25 / 682.74 ms | 736 |
| 250 | 20 | 5,000 | 249.8 | 27.49 / 40.40 ms | 0 |

四组已发请求系统错误均为 0。每组成功响应、预约表和时段 booked 一致。单热点 250 组未通过未发出请求数阈值。另一次 15 秒锁等待采样中，单热点 48 次采样中有 47 次观察到预约时段锁等待，20 时段组 39 次采样均未观测到。采样记录的是等待关系，无法据此推导持续吞吐上限。另有 10 名额的售罄样本，2,001 次请求中 10 次成功，1,991 次正常满位拒绝。

```powershell
node scripts/booking-load-fixture.mjs 5100 1 5100
$env:RATE='250'
$env:SECONDS='20'
$env:REPORT='.local/load-hot.json'
k6 run scripts/booking-load.js
node scripts/booking-load-fixture.mjs 5100 20 5100
$env:REPORT='.local/load-spread.json'
k6 run scripts/booking-load.js
```

报告中的两种流量分布用于观察热点，应用库存实现相同。分段库存、Redis 预扣和全链路承载量需要另做实验。压测脚本根据响应体区分成功、满位和系统错误。k6 未发出的请求单独计数。

## 简历表述参考

- 使用 JUnit 与真实 MySQL 验证跨店预约幂等、换位回滚与状态竞争。8 路并发重放返回同一版本，数据库仅产生一条有效预约流水。
- 在 30 份、共 9,000 笔合成停业预约的稀缺样本中，引入容量约束最大流作为离线对照，较最近优先多安排 973 笔，并对比可安排比例、出行距离与规划耗时。
- 用 k6 区分单时段热点和 20 时段均匀写入，在目标 250 请求/秒下复现单热点排队，结合 MySQL 锁等待定位同一时段行的写入竞争。
