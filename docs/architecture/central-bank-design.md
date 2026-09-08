# Bloeco 中央银行经济内核设计

**状态：** V2 正式基线（MySQL/Redis）
**目标平台：** Paper 1.21.11、Java 21  
**默认存储：** MySQL 8.4 / InnoDB
**兼容存储：** SQLite 仅用于开发、离线测试和迁移；Redis 7 仅作可重建缓存与事件层

## 1. 产品定位

Bloeco 是服务器唯一的中央银行、中央清算中心和权威经济账本。它负责货币发行与回收、账户余额、玩家转账、国库、税收、手续费、插件机构账户、审计和通胀监测。

Bloeco 不实现商店、证券、拍卖、任务、商品定价、库存或物品交付。其他插件只负责业务，并通过 Bloeco 原生 API 请求资金结算。其他插件不得直接写数据库、维护第二套余额或自行发行货币。

本阶段不依赖、不注册 Vault。Vault 兼容桥不包含在本次实现中。

## 2. 不可破坏的经济约束

1. SQL 是账户、余额、流水、税制和货币供给的唯一事实来源。
2. 每一次金额变化必须属于一个完整的复式记账凭证；同一凭证全部分录的 `amount_minor` 之和必须为零。
3. 金额统一使用带符号的 Java `long` 最小货币单位；禁止使用 `double`、`float` 或数据库浮点类型。
4. 普通玩家、插件和管理员的“付款、奖励、加钱、扣钱”都是已有资金的划拨，不得改变货币总量。
5. 新货币只能通过货币发行流程进入国库；货币只能通过回收流程退出流通。
6. 国库余额不足时，预算、奖励和插件拨款必须失败，不得自动补发货币。
7. 已入账凭证不可修改或删除。纠错使用关联原凭证的冲正凭证。
8. 只有 SQL 事务提交成功，调用才算成功。超时或结果不确定时失败关闭，不猜测成功。
9. Redis 故障不得改变余额、回滚已提交 SQL 或成为恢复账本所必需的条件；MySQL 故障则进入只读/不可用保护。
10. Paper 主线程不得执行 JDBC、Redis或数据库迁移。

## 3. 总账与分级账本

Bloeco 只维护一套中央总账。“插件专属账本”是中央总账内按机构隔离的子账本，不是独立数据库。这样可以同时实现插件级审计和全服货币供给核算。

### 3.1 账户层级

| 层级 | 账户类别 | 示例 | 可负余额 |
|---|---|---|---|
| L0 货币当局 | 发行控制、回收控制 | `monetary:issuance`、`monetary:retired` | 仅控制账户按规则允许 |
| L1 财政账户 | 国库、税收、手续费、专项预算 | `fiscal:treasury`、`fiscal:tax`、`fiscal:fee` | 否 |
| L2 机构账户 | 每个接入插件的结算、收入和预算账户 | `plugin:bloeco-shop:settlement` | 否 |
| L3 客户账户 | 玩家钱包 | `player:<uuid>:wallet` | 否 |

每个账户具有稳定 ID、所有者类型、所有者 ID、账户用途、状态、父级账户和创建时间。关闭账户只禁止新业务，不删除历史数据。

### 3.2 插件专属子账本

插件通过 Paper `ServicesManager` 注册并取得 `BloecoApi`。Bloeco 使用调用方的实际 `Plugin` 实例识别身份，并生成规范化 `client_id`；插件不能在请求参数中冒充其他插件。

首次批准接入后，Bloeco 创建：

- 一个机构记录；
- 一个默认结算账户；
- 一个收入账户；
- 一个可选预算账户；
- 一组由管理员授予的能力；
- 以 `client_id` 为维度的只读子账本视图。

插件只能查询自己的机构账户和由其发起的凭证。它不能读取其他插件的子账本，也不能任意扣除玩家资金。

插件被禁用或卸载后，账户冻结但记录永久保留。重新安装且插件身份匹配时，可以恢复使用原机构账户。

## 4. 复式记账模型

一个 `journal_entry` 表示一项完整业务，一个或多个 `posting` 表示账户增减。钱包式账户的正分录增加余额，负分录减少余额。

例如玩家 A 向玩家 B 转账 `100.00`，手续费 `1.00`，个人所得税 `5.00`：

| 账户 | 分录 |
|---|---:|
| 玩家 A 钱包 | `-101.00` |
| 玩家 B 钱包 | `+95.00` |
| 税收账户 | `+5.00` |
| 手续费账户 | `+1.00` |
| 合计 | `0.00` |

税收和手续费仍属于已发行货币，只是从流通账户进入财政账户。它们不会被隐式销毁。

### 4.1 余额来源

`account_balances` 是与分录在同一 SQL 事务内更新的物化余额，用于高效查询；`postings` 是永久审计依据。启动校验和管理命令可以从分录重算余额并与物化余额比较。

### 4.2 幂等与冲正

所有外部写请求必须携带 `client_id + idempotency_key`。同一组合只能产生一个结果：

- 参数相同：返回首次结果；
- 参数不同：返回 `IDEMPOTENCY_CONFLICT`；
- 首次结果不确定：调用方使用同一幂等键查询或重试，不得换键重复扣款。

冲正必须记录 `reversal_of_entry_id`，交换原凭证分录符号，并校验原凭证未被完整冲正。

## 5. 核心业务

### 5.1 玩家账户与余额

- 玩家首次进入服务器时自动创建钱包，并从国库领取一次可配置的启动资金；默认 `100.00`。
- 启动资金使用按玩家 UUID 固定的幂等键，余额归零、重新进服和重启均不会再次发放。
- `/eco` GUI 显示余额和最近流水。
- 管理员“给予玩家资金”是国库到玩家的划拨；国库不足则失败。
- 管理员“收回玩家资金”是玩家到国库的划拨；玩家不足则失败。
- 不提供直接设置余额的操作。目标余额调整会被转换成国库与玩家之间的差额划拨。

### 5.2 玩家转账

`/pay` 和 GUI 转账由 Bloeco 自己清算。转账本金、付款方手续费、收款方个人所得税在一个 SQL 事务中入账。任一余额或规则校验失败时不写入任何分录。

税费使用基点存储，`10_000` 基点等于 `100%`。计算使用整数和 `HALF_UP` 到最小货币单位，并用溢出检查保护 `long`。

### 5.3 税收与手续费

税费规则由 Bloeco 管理，不由业务插件提交最终金额。规则至少包含：

- 业务类别；
- 百分比基点；
- 可选固定费用；
- 收款账户；
- 生效时间；
- 启用状态；
- 创建者和审批说明。

本阶段启用玩家转账手续费与个人所得税。未来商店等插件只提交中央定义的业务类别，Bloeco 根据当时有效规则计算税费。

修改税率只影响新凭证；历史凭证保存所用规则版本和实际税费，不随配置变化。

### 5.4 货币发行与回收

发行流程为 `REQUESTED -> APPROVED -> EXECUTED`。申请包含金额、原因、申请者、审批者和供给变化预测。执行后，新货币只进入国库，不直接进入玩家或插件账户。

首次创建空白总账时，Bloeco 使用同一发行流程建立可配置的初始国库供给，默认 `1000000.00`。该创世发行具有固定申请 ID 与幂等键，仅在没有经济分录时创建；后续重启或修改配置不得增加既有供给。

回收只能从国库转入回收控制账户。已进入回收账户的金额不能重新支出；如需纠错，使用经过审批的冲正凭证。

默认反通胀保护：

- 每次发行上限；
- 每日发行上限；
- 过去 30 日净货币供给增长率上限；
- 超限默认阻止执行，而不是只记录警告；
- 所有阈值由持有货币政策权限的管理员修改并审计。

控制台可以被授予紧急审批能力，但仍必须填写原因、经过相同上限检查并形成完整凭证。系统永不因国库不足自动发行。

### 5.5 插件资金业务

插件初始账户余额为零。资金来源只能是：

- 玩家通过 Bloeco 授权的支付；
- 插件已有资金的内部划拨；
- 管理员从国库批准的预算拨款；
- 已入账业务的冲正或退款。

默认能力只允许插件查询自身账户和支出自身资金。玩家扣款采用支付意图：插件创建金额和用途确定的意图，玩家在 Bloeco 管理的确认界面批准后才结算。未来确有需要时，可由管理员单独授予受审计的系统代扣能力，但该能力默认不存在。

## 6. 原生异步 API

Bloeco 通过 Paper `ServicesManager` 暴露稳定的 Java API，不依赖 Vault。API 实现返回 `CompletionStage<Result<T>>`，数据库完成后才结束；回调线程不保证为 Paper 主线程。

首版能力：

```java
interface BloecoApi {
    CompletionStage<Result<AccountView>> playerAccount(UUID playerId);
    CompletionStage<Result<MoneyView>> balance(AccountRef account);
    CompletionStage<Result<TransferReceipt>> payPlayer(PlayerPayment command);
    CompletionStage<Result<PaymentIntentView>> createPaymentIntent(PaymentIntentCommand command);
    CompletionStage<Result<TransferReceipt>> spendPluginFunds(PluginTransfer command);
    CompletionStage<Result<List<JournalEntryView>>> ownEntries(EntryQuery query);
    CompletionStage<Result<HealthView>> health();
}
```

写命令包含幂等键、金额、业务类别和长度受限的说明。Bloeco 从服务绑定上下文取得 `client_id`，不信任命令中自报的插件身份。

稳定错误码至少包括：`INVALID_AMOUNT`、`ACCOUNT_NOT_FOUND`、`ACCOUNT_FROZEN`、`INSUFFICIENT_FUNDS`、`UNAUTHORIZED`、`POLICY_REJECTED`、`IDEMPOTENCY_CONFLICT`、`STORAGE_UNAVAILABLE` 和 `INTERNAL_ERROR`。错误结果不得泄漏 SQL、路径或凭据。

## 7. 管理权限与 GUI

管理职责分离为：

- `bloeco.role.operator`：查询状态、备份、健康检查；
- `bloeco.role.treasurer`：国库拨款和余额调整；
- `bloeco.role.tax`：税收与手续费规则；
- `bloeco.role.monetary`：发行、回收和供给阈值；
- `bloeco.role.auditor`：只读查看全服总账和审计报告。

玩家主要通过 GUI 使用余额、流水和转账功能。管理员通过 GUI 查看国库、税费、插件子账本、货币供给、待审批事项和异常。控制台命令保留为自动化与灾难恢复入口，但不能绕过领域规则。

高风险操作采用“查看影响 -> 再次确认 -> 执行”的流程。发行、回收、税率变更、账户冻结和大额国库拨款必须填写说明。

## 8. 存储架构

### 8.1 配置

```yaml
storage:
  type: mysql
  mysql:
    jdbc-url: jdbc:mysql://127.0.0.1:3306/bloeco?useSSL=false&serverTimezone=UTC&characterEncoding=utf8mb4
    username: bloeco
    password-env: BLOECO_MYSQL_PASSWORD
    maximum-pool-size: 16
  sqlite:
    file: economy.db

redis:
  enabled: true
  uri: redis://127.0.0.1:6379/0
  key-prefix: bloeco:v2:
  stream: bloeco:v2:ledger-events
```

生产默认使用 MySQL。切换到 MySQL 必须由显式迁移/校验流程完成，不能只改配置后自动得到一套空经济；Bloeco 不会在 MySQL 不可用时静默回退到 SQLite。

### 8.2 SQLite

- `journal_mode=WAL`
- `foreign_keys=ON`
- `busy_timeout=5000`
- `synchronous=FULL`
- 单写入执行器串行提交
- 插件关闭时停止接收新写入、等待队列清空后关闭连接

### 8.3 MySQL（生产权威）

- InnoDB、`utf8mb4`、HikariCP
- `READ COMMITTED` 事务隔离
- 按稳定顺序锁定受影响账户行，避免死锁
- 唯一约束保证幂等
- `outbox_events` 与账本事务一起写入，保证 Redis 事件最终可补发
- 使用数据库级写入者租约，保证同一 Bloeco 数据库只有一个活动写入实例

SQLite 和 MySQL 必须通过同一套存储契约测试。两者的余额、幂等、并发、回滚、冲正和供给统计结果必须一致。

### 8.4 Redis

Redis 默认开启，只允许保存：

- 短期余额查询缓存；
- 提交后的失效通知；
- GUI 统计快照；
- 可重建的运行指标。

写流程固定为“提交 MySQL 账本与 outbox -> 清除缓存 -> 发布 Redis Stream 事件”。Redis 发布失败只记录降级状态，后台依据 outbox 重试，不回滚已提交的 SQL。缓存未命中或不可用时读取 MySQL。

## 9. 数据模型

首版权威表：

- `schema_history`
- `institutions`
- `accounts`
- `account_balances`
- `fund_reservations`
- `journal_entries`
- `postings`
- `idempotency_records`
- `settlements`
- `settlement_items`
- `settlement_batches`
- `tax_rules`
- `issuance_requests`
- `policy_limits`
- `audit_events`
- `daily_monetary_metrics`
- `outbox_events`

关键约束：

- `postings(entry_id, line_no)` 唯一；
- `idempotency_records(client_id/scope_key, idempotency_key)` 唯一；Bloeco V2 API 对外称 `scope_key`，当前 Java 存储端以 `client_id` 兼容旧调用；
- 玩家钱包 `(owner_type, owner_id, purpose)` 唯一；
- 普通账户物化余额不得小于零；
- 每个凭证至少两条分录；
- 凭证提交前在应用层验证分录和为零，并在同一事务中写凭证、分录、余额和幂等结果。

## 10. 通胀监测

Bloeco 至少计算并展示：

- 累计发行、累计回收、当前净货币供给；
- 玩家流通余额、国库余额、税费余额、各插件余额；
- 日发行量、日回收量、净增发量；
- 7 日与 30 日供给增长率；
- 玩家余额中位数和集中度；
- 按业务类别统计的转账额、税收和手续费。

账户间转账不改变净货币供给。净供给必须等于累计发行减累计回收，并能通过总账重算。若物化余额、分录或供给指标不一致，Bloeco 进入只读保护模式，拒绝所有写操作并提示管理员审计。

## 11. 故障处理与安全

- 数据库启动、迁移或完整性校验失败时，不注册写服务。
- 同一请求的部分分录不得提交。
- 关闭期间的新请求返回明确的不可用错误。
- 插件输入的说明、业务键和查询范围设长度与数量上限。
- 日志屏蔽数据库密码和 Redis URI 凭据。
- 所有管理操作写入独立审计事件，并关联产生的凭证 ID。
- SQLite 提供一致性在线备份；MySQL 提供备份前检查与操作文档。
- 任何账本修复都生成报告和冲正记录，不执行无痕 SQL 修改。

## 12. 现有数据迁移

启动发现旧版 `accounts` 与 `ledger_entries` 时执行一次性迁移：

1. 停止经济写入并创建带时间戳的 SQLite 一致性备份；
2. 验证旧账户余额非负、流水字段合法；
3. 将 `TREASURY`、玩家账户、发行和回收账户映射到新层级；
4. 删除 `EXTERNAL_CREDIT`、`EXTERNAL_DEBIT` 的继续使用能力，但保留并迁移其历史为标记了 `LEGACY_EXTERNAL` 的凭证；
5. 将旧 `ledger_entries` 每行转换为一个双分录凭证；
6. 重新计算所有余额、累计发行和累计回收；
7. 只有重算结果与旧余额完全一致才替换为新结构，否则保留原库并拒绝启动；
8. 迁移成功后记录旧库哈希、备份路径、行数和校验结果。

Bloeco-Stock 专用 UUID 和专用拨款入口不进入新核心。未来所有插件统一使用机构注册和国库预算机制。

## 13. 代码边界

第一阶段保持一个可部署 JAR，但源代码按职责隔离：

- `api`：稳定 DTO、错误码和异步接口；
- `domain`：账户、凭证、分录、税费、发行政策和不变量；
- `application`：玩家转账、插件结算、国库、发行、回收和审计用例；
- `storage`：SQL 接口、SQLite、MySQL、迁移和事务；
- `cache`：可选 Redis 与本地缓存；
- `paper`：生命周期、命令、GUI、权限和 ServicesManager 注册。

领域层不依赖 Paper、JDBC、Redis 或 GUI。SQLite 与 MySQL 共享同一存储接口和契约测试。未来如需拆分 `bloeco-api` 独立发布，不改变领域语义。

## 14. 测试与验收

### 14.1 自动测试

- 金额解析、舍入和溢出测试；
- 每种业务的分录和必须为零；
- 余额不足、冻结账户和权限拒绝时零写入；
- 税费边界、规则版本和零税率；
- 重复幂等键、冲突参数和并发重试；
- 冲正不可重复；
- 发行上限和国库不足时禁止自动增发；
- SQLite/MySQL 存储契约测试；
- Redis 停机和缓存过期时 SQL 结果仍正确；
- 旧库迁移成功、校验失败回滚和备份保留；
- Paper 主线程无 JDBC/Redis 调用；
- 插件身份隔离和越权查询拒绝。

### 14.2 完成标准

1. Paper 1.21.11、Java 21 上启动成功。
2. 未安装 Vault 时功能完整，构建产物不包含 Vault API 依赖或注册代码。
3. 新玩家余额为零；除发行外没有任何路径可增加净货币供给。
4. 玩家转账的本金、税收和手续费在一个凭证中原子结算。
5. 两个测试插件拥有互相隔离的机构账户和子账本。
6. MySQL 是默认且通过存储契约测试；SQLite 通过相同契约用于开发和迁移。
7. Redis 完全不可用时，所有权威读写仍然正确。
8. 并发和重试不会造成重复扣款、负余额或不平衡凭证。
9. 旧数据迁移前有可恢复备份，迁移后余额与供给校验一致。
10. 管理员可以查看总供给、国库、税费、插件余额和完整审计链。

## 15. 分阶段交付

### 阶段一：不可增发的中央总账

移除 Vault 与 Bloeco-Stock 专用入口，建立复式凭证、分级账户、MySQL 默认存储、SQLite 迁移、玩家余额/转账、税费、国库、受控发行与回收。

### 阶段二：机构接入与数据治理

提供原生异步 API、插件注册和能力隔离、支付意图、插件子账本及跨数据库存储契约测试。

### 阶段三：Redis 与经济治理

完善 outbox 发布器、统计快照、供给增长限制、审计 GUI、备份工具、只读保护模式和性能/故障测试。

每个阶段都必须产生可安装、可回滚、通过自动测试的 Paper 插件包；不得以未完成的后续阶段作为当前阶段账本正确性的前提。
