# Bloeco

> Paper `1.21.11` 的中央经济底座 · Java 21 · MySQL 权威账本 · 内嵌 Native Economy API 1.0

Bloeco 是整个 Bloeco 系列插件共用的中央银行与总账系统。它统一管理货币供给、玩家余额、机构账户、国库、税收、手续费、冻结资金和永久账单，所有资金变化都必须落到可审计的复式分录中。设计重点不是“让插件能加钱”，而是让每一笔钱都能回答三个问题：钱从哪里来、现在属于谁、出了问题如何幂等恢复。

Bloeco 不包含商店、证券、拍卖、任务、商品定价、库存或物品交付，也不依赖或注册 Vault。后续经济业务插件以 Bloeco 为唯一资金底座，通过内嵌的 Native Economy API 获得自己的机构账本并完成结算；业务插件不能直接修改玩家余额、连接 Bloeco 数据库，或维护第二套可消费货币。

## 先看结论

| 你要做的事 | 应该使用的入口 | 资金权威 |
| --- | --- | --- |
| 玩家查看余额、账单、银行 | `/eco` GUI | Bloeco 钱包与银行账本 |
| 玩家向在线玩家付款 | `/pay <玩家> <整数>` | Bloeco 原子转账 |
| 商店、领地、任务、证券等插件收付款 | 内嵌 `com.blocke.bloeco.api` | Bloeco 机构账户与结算凭证 |
| 发行、回收、税费和完整性检查 | `/eco` 权限 GUI | 中央银行与复式总账 |
| 读取事件或加速查询 | Redis（可选） | 只能以 MySQL 已提交事实为准 |

## 文档导航

- [第三方接入规范](docs/third-party-economy-integration.md)：给商店、领地、任务、证券等插件作者的唯一接入合同。
- [系统设计](docs/architecture/central-bank-design.md)：货币供给、账户分级、税费、发行回收和反通胀边界。
- [银行设计](docs/architecture/banking-foundation-design.md)：存款、贷款、准备金、信用等级和银行 GUI。
- [原生 API 设计](docs/architecture/native-api-design.md)：类似 Vault 的稳定接口，但 API 直接内嵌在 Bloeco JAR 中。
- [MySQL/Redis 存储规范](docs/architecture/storage-v2-mysql-redis.md)：事务、迁移、Outbox、缓存降级和 Redis 事件语义。
- [Agent 指南](AGENTS.md)：修改源码、设计资金流或接入新插件时的工程约束。

## 当前功能

- MySQL 8.4/InnoDB 默认权威存储：Runtime 只创建一个共享 HikariCP 连接池，Ledger、Banking 与 Outbox 共用统一事务基础设施。
- 数据库并发保护：多账户资金操作按 `account_id` 固定顺序一次性 `FOR UPDATE`，物化余额每次更新都会递增 `version`，不依赖 Java 全局锁。
- 正式版本迁移：V001-V007 顺序执行并逐版记录；V007 增加机构能力、结算、退款与冻结资金结构，迁移失败时经济中心拒绝启动。
- Redis 7 默认作为可重建缓存与 Redis Stream 事件层；断线进入降级状态并每 5–30 秒自动重连，恢复后无需重启即可续发 Outbox。
- 整数最小货币单位：金额全程使用 `long`，拒绝浮点误差与超过两位小数的玩家输入。
- 分级账户：货币当局、财政、机构、玩家四级分类；每个余额都能追溯到复式分录。
- 受控发行：申请、异人审批、执行三步完成；新货币只能先进入国库，并受单笔、每日和滚动周期政策限制。
- 货币回收：从国库回收后永久退出流通，不能再次消费。
- 余额调整：增加玩家余额只能使用已有国库资金，降低余额则回到国库，不能借管理命令凭空造币。
- 玩家转账：一笔原子分录同时结算本金、付款手续费、收款个人所得税；重复请求不会二次扣款。
- 版本化税制：税率和固定费用保留生效时间、操作者、原因与历史版本。
- 内嵌原生 API 1.0：通过 Paper `ServicesManager` 为每个接入插件建立能力受限的机构客户端和专属账户；API 与实现同 JAR 发布，不依赖 Vault。
- 机构结算：支持消费收款、机构付款、凭证查询、分批退款及中文说明快照；消费税默认 5%，所有操作资金守恒且永久幂等。
- 资金预授权：支持冻结、捕获、释放与到期，冻结资金会同时约束 `/pay`、银行及插件扣款，阻止并发超卖和重复消费。
- 审计与指标：余额、最近账单、货币供给、玩家流通量、国库、税费、账户层级和当日资金流量均可查询。
- 完整性保护：启动及定时重算永久分录；发现不一致即进入只读模式，停止一切资金写入并保留查询能力。
- 安全迁移：旧 CentralEconomy 数据会先生成一致性备份，在临时库转换并验证后才替换。
- 可配置的启动资金：全新账本默认发行 `1000000.00` 到国库；玩家首次加入默认从国库领取 `100.00`，两者均可在 `config.yml` 修改。
- 防重复初始化：国库启动发行只允许在空白总账执行一次；玩家启动资金按 UUID 永久去重，重启、重新进服或余额归零都不会再次领取。
- 央行金额选择：发行与回收操作提供 `100.00`、`1000.00`、`10000.00` 三个明确的 GUI 预设按钮。
- 中文账单：按玩家视角显示中文业务类型、收入或支出金额、本地时间和说明，不暴露内部凭证 UUID。
- 中文说明合同：Bloeco 内置账单使用中文说明；未来接入插件须分别提交机器业务类型、内部业务引用与玩家可读的中文 `displayMemo`。
- 国有银行：银行初始资本由国库真实划拨，支持活期存款、按日应计存款利息、取款、年化固定利率贷款、部分或全部还款、到期/逾期提示和信用等级；预设金额之外可在聊天栏输入自定义金额。
- 反通胀放贷：贷款只从 `bank:cash` 转给玩家，受准备金率、银行现金、放贷开关和单人贷款上限约束；贷款不会调用货币发行。
- 银行经营 GUI：具有 `bloeco.role.banker` 权限的管理员可在 `/eco` 中查看资产负债表，并调整存款利率、贷款利率、准备金率、贷款上限和放贷开关。

## 最短部署路径

1. 使用 Java 21 和 Paper `1.21.11`，把 `Bloeco-1.5.0-SNAPSHOT.jar` 放入服务器的 `plugins/` 目录。
2. 生产环境创建 MySQL 8.4 数据库，并在启动服务器前设置 `BLOECO_MYSQL_PASSWORD`；默认配置已经选择 `storage.type: mysql`。
3. 按需启动 Redis 7。Redis 断开时只会关闭缓存和事件加速，不会把余额切换到 Redis，也不会静默退回 SQLite。
4. 启动 Paper，等待控制台出现 Bloeco ready 后使用 `/eco`。首次空账本会把配置中的资金发行到国库，玩家启动资金随后从国库划拨一次。
5. 业务插件只安装自己的 JAR，并按照[第三方接入规范](docs/third-party-economy-integration.md)通过 `ServicesManager` 发现 API；不要复制 API 类或连接 Bloeco 数据库。

本地开发可以显式改为 `storage.type: sqlite`，但 SQLite 只用于开发、离线测试和导入。生产环境不能依赖“数据库不可用时自动换库”，否则会形成两套互不一致的经济事实。

## 玩家使用

- `/eco`：打开经济中心 GUI，可查看余额、最近账单、转账，并进入国有银行办理存取款和贷款。
- `/pay <在线玩家> <正整数金额>`：快捷转账；在线玩家名和常用整数金额支持补全。

插件只注册 `/eco` 和 `/pay` 两个指令。税收、货币申请与回收、经济总览和总账校验通过 `/eco` 的权限化 GUI 操作；每个子菜单都有“返回上一级”和“主菜单”。

## 初始资金配置

```yaml
bootstrap:
  treasury-initial-balance: "1000000.00"
  player-initial-balance: "100.00"
bank:
  initial-capital: "250000.00"
  deposit-rate-bps: 100
  loan-rate-bps: 320
  reserve-ratio-bps: 2000
  maximum-loan: "10000.00"
  lending-enabled: true
  loan-term-days: 7
```

金额使用字符串保存，最多两位小数。`treasury-initial-balance` 只在数据库没有任何经济分录时生效；之后修改它不会再次发行。`player-initial-balance` 只影响从未领取过启动资金的玩家，资金来自国库而不是新发行。

`bank.initial-capital` 只在银行首次建立时从国库划拨一次，默认 `250000.00`；它不是新发行货币。利率和准备金率使用基点，`100` 表示 1%。存款利息按完整 24 小时周期惰性应计，在玩家查看或操作银行账户时结算到银行存款负债；提取利息仍需银行拥有足额真实现金。

默认贷款年化利率为 `3.2%`（`320` 基点），默认期限为 7 天。贷款利息按 `本金 × 年化利率 × 期限天数 ÷ 365` 计算，最低计息单位为 `0.01`；GUI 会在申请前显示预计利息和预计应还金额。点击“我的银行账户”可查看钱包、存款、待还贷款和当前利率，信用等级及 A–D 评级标准只在账户详情页显示。配置只负责银行首次建立时的默认政策，开业后的利率和风险参数以银行管理员 GUI 中保存的政策为准。

转账、存款、取款和贷款金额页面均提供“自定义金额”。点击后在聊天栏输入大于零且最多两位小数的金额；输入“取消”可返回，输入会在 60 秒后失效。存款和取款页面另外提供“全部存入”和“全部取出”，金额在提交事务中按实时钱包或存款余额确定。玩家提示只描述操作结果、金额和税费，不展示中央账本或内部清算术语。

## 管理角色

- `bloeco.role.operator`：健康状态与完整性校验。
- `bloeco.role.treasurer`：使用已有国库资金调整玩家余额。
- `bloeco.role.tax`：管理税收与手续费版本。
- `bloeco.role.monetary`：申请、审批、执行发行以及回收货币。
- `bloeco.role.auditor`：查询余额与账单。
- `bloeco.role.banker`：管理国有银行利率、准备金和放贷政策，无货币发行或税率权限。

这些权限默认仅 OP 拥有，建议通过权限插件分别授予不同管理人员。货币申请人不能审批自己的申请。

## 反通胀边界

普通转账、税收、手续费、国库拨款和余额调整只搬运已有货币，不改变总供给。只有经过审批的 `ISSUE` 分录能增加供给，只有 `RETIRE` 分录能减少供给。数据库禁止直接写入；纠错必须追加冲正分录，历史记录不覆盖、不删除。

默认税制为转账手续费 1%、收款个人所得税 5%。它们把货币转入财政账户，不会自动销毁货币；管理员需要通过国库回收操作形成真正的货币退出机制。

## 存储配置

生产默认使用 MySQL，配置位于 `config.yml`：

```yaml
storage:
  type: mysql
  mysql:
    jdbc-url: "jdbc:mysql://127.0.0.1:3306/bloeco?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8"
    username: bloeco
    password-env: BLOECO_MYSQL_PASSWORD
redis:
  enabled: true
  uri: "redis://127.0.0.1:6379/0"
  reconnect-interval-seconds: 10
```

SQLite 仍保留为开发、离线测试和迁移模式，必须显式设置 `storage.type: sqlite`；MySQL 故障时不会静默回退到 SQLite。完整约束见 [MySQL/Redis V2 存储规范](docs/architecture/storage-v2-mysql-redis.md)。

启动时会先校验存储类型、连接池、Redis 地址、初始资金、利率和准备金率；配置不安全时不会进入半初始化状态。经济与银行操作分别通过有界异步队列执行，队列关闭或已满时返回“经济账本暂时不可用”，不会把线程池异常传到 Paper 主线程。SQLite 初始化会启用 WAL、外键、忙等待和内存临时表，并自动创建账单、税则、审计及银行流水索引。

Redis 断线只会关闭缓存和事件加速：MySQL 已提交的账本事实不受影响，未发布的 outbox 事件会保留并重试。状态为 `AVAILABLE`、`DEGRADED` 或 `RECONNECTING`；bridge 暴露可用性、故障次数、最近故障/恢复时间和重连次数。Stream 采用至少一次投递，消费端必须按 `event_id` 去重，Redis 永远不能作为余额确认依据。

## 第三方开发

请阅读 [Bloeco 第三方经济接入技术规范](docs/third-party-economy-integration.md)。商店、证券、任务等插件不得直连 Bloeco 数据库或 Redis，必须通过异步原生 API 提交带机构、业务类型、中文说明和幂等键的结算。

消费者在 `plugin.yml` 声明 `depend: [Bloeco]`，将 Bloeco JAR 设为 `compileOnly`，再从 Paper `ServicesManager` 获取 `BloecoApi`。不要 shade API 类，也不要在主线程等待数据库 Future。完整示例见 [examples/bloeco-api-consumer](examples/bloeco-api-consumer)。

接入 API 时只需要记住四条合同：

| 合同 | 规则 |
| --- | --- |
| 资金来源 | `chargePlayer` 从玩家钱包收款；`payPlayer` 只能从本机构已有余额付款；没有 `give`、`setBalance` 或无来源入账接口。 |
| 金额表示 | 使用 `long` 最小货币单位，`10000` 表示 `100.00`；禁止 `double`。 |
| 结果处理 | 只有收到 `COMMITTED` 回执后才能交付物品或把订单标记为已付款；`RESULT_UNKNOWN` 必须使用原幂等键查询或重试。 |
| 数据边界 | 业务插件保存订单和商品，Bloeco 保存余额、税费、冻结资金、账单与分录；不得直连数据库或 Redis。 |

API 版本独立于插件版本：当前公开合同为 `1.0.0`，同一主版本保持二进制兼容；不支持的主版本必须失败关闭。字段约束、税费快照、退款、预授权和错误码详见[接入规范](docs/third-party-economy-integration.md)。

完整的中央银行设计见 [系统设计](docs/architecture/central-bank-design.md)。

## 构建

需要 Java 21：

```text
# Linux / macOS
./gradlew clean test shadowJar

# Windows
gradlew.bat clean test shadowJar
```

成品位于 `build/libs/Bloeco-1.5.0-SNAPSHOT.jar`。MySQL、HikariCP、SQLite 迁移驱动、Redis 客户端和公开 API 1.0 均位于同一个插件包；不需要 Vault 或其他经济前置。

提交前应确认 `git status --short` 没有 `build/`、服务器运行目录、数据库、日志或凭据；JAR 只作为 GitHub Release 附件发布，不提交到源码树。
