# Bloeco 银行基础架构设计

状态：Bloeco 1.3 已实现基线（源码版本；是否发布 Release 由发布流程另行决定）
适用版本：Bloeco 1.3 及后续版本  
平台：Paper 1.21.11、Java 21、MySQL 8.4、Redis 7+

## 1. 目标与边界

Bloeco 在保持唯一货币权威账本的前提下增加国有银行能力。银行存款、贷款、利息、逾期和坏账都必须解释为现有货币、资产或负债的变化，不能绕过 `ISSUE`/`RETIRE` 凭空改变货币供应。

第一阶段采用模块化单体：银行领域与中央货币领域分包、分接口，但共同运行在一个 Bloeco JAR、一个 MySQL 数据库和一个事务管理器中。这样既避免继续扩张 `CentralBankService` 和 `AsyncEconomyFacade`，又能让银行业务记录、总账分录、余额、审计和 Outbox 在一个事务中提交。

Bloeco 负责经济事实；未来外部插件负责订单、持仓、领地权限等业务事实。外部插件只能调用公开 API，不能访问 Bloeco 数据库或 Redis。

Bloeco 1.3 包含：

- 单一国有银行及其资产负债表。
- 活期存款、存入和取出。
- 真实资金贷款、部分/全部还款、年化固定利率按实际期限计息、到期与逾期状态。
- 信用等级、准备金率、贷款开关和银行管理员 GUI。
- 幂等银行结算、政策审计、MySQL Outbox 和崩溃恢复。
- `/eco` 玩家银行、账户详情、A–D 信用说明、聊天自定义金额和权限化银行管理 GUI。

第一版不包含：

- 信用货币创造和部分准备金下的凭空放贷。
- 证券、领地或公司权益抵押。
- 多家玩家银行、银行间市场、浮动利率和贷款证券化。
- 背包、箱子、装备或普通方块估值。
- Vault 兼容层。
- 自动违约、坏账核销、抵押和外部银行 API；这些在后续版本单独设计和发布。

## 2. 模块结构

```text
Bloeco
├── api                 对外稳定、异步、版本化接口
├── domain
│   ├── account         账户定义
│   ├── ledger          日记账与分录
│   ├── settlement      结算与资金冻结
│   ├── institution     机构身份与权限
│   ├── banking         存款、贷款、利息与风险规则
│   └── liability       玩家和机构负债事实
├── application
│   ├── economy         余额、支付与货币政策
│   ├── settlement      冻结、消费、释放、退款和查询
│   ├── banking         存取、放款、还款、计息和违约
│   └── admin           政策、审计和完整性检查
├── storage
│   ├── mysql           事务管理器、Repository、迁移与 Outbox
│   ├── sqlite          开发和导入兼容后端
│   └── redis           可重建缓存与事件流
└── paper               GUI、权限、监听器和生命周期
```

不新增万能 Facade。`EconomyFacade`、`SettlementFacade`、`BankingFacade` 和 `AdminFacade` 只暴露各自用例。Paper 层组合这些接口，但不能直接访问 Repository。

## 3. MySQL 事务内核

银行开发前先替换当前长期持有单个 `Connection` 的实现。

```java
interface TransactionManager {
    <T> T read(TransactionWork<T> work);
    <T> T write(TransactionWork<T> work);
}
```

每次调用从 HikariCP 获取连接，在限定时间内完成事务并归还连接。Repository 接收事务上下文，不自行创建、提交或回滚事务。SQLite 保持单写者实现，但必须满足同一应用契约。

MySQL 写事务统一使用 `READ_COMMITTED`。涉及多个账户时，按 `account_id` 字典序执行 `SELECT ... FOR UPDATE`，防止反向锁顺序。只对 MySQL 错误 `1205` 和 `1213` 进行有限次数、带抖动的整笔事务重试；其他异常失败关闭。

幂等键唯一约束是最终裁决。调用方超时后必须使用相同 `institutionId + idempotencyKey` 查询或重试，不能生成新请求。

## 4. Repository 边界

`LedgerStore` 拆为：

- `AccountRepository`：账户定义、状态和余额锁定。
- `LedgerRepository`：日记账、分录、冲正和读取。
- `InstitutionRepository`：机构注册、状态和能力。
- `ReservationRepository`：冻结、释放、消费和过期。
- `SettlementRepository`：业务结算、退款和幂等结果。
- `BankRepository`：银行、存款、贷款、还款和风险快照。
- `LiabilityRepository`：统一负债事实。
- `PolicyRepository`：税、费、发行和风险参数。
- `AuditRepository`：不可变审计记录。
- `OutboxRepository`：同事务事件写入。

一个银行用例可以调用多个 Repository，但只能由外层事务管理器提交一次。

## 5. 账户余额与冻结

`account_balances` 保留总余额，不把总余额改名为可用余额：

```text
account_id       PK/FK
balance_minor    总余额
reserved_minor   已冻结金额
version          乐观版本
updated_at       最后更新时间
```

```text
available_minor = balance_minor - reserved_minor
```

`fund_reservations` 保存每一笔冻结明细。任何时候必须满足：

```text
0 <= reserved_minor <= balance_minor
reserved_minor = 所有 ACTIVE reservation 的金额合计
```

`reserve`、`release` 和 `consume` 均使用幂等键，并与余额版本、结算记录和 Outbox 在同一事务提交。

## 6. 银行会计模型

国有银行机构 ID 固定为 `bloeco.bank`。第一版至少建立：

- `bank:cash`：银行真实可支配货币。
- `bank:interest-income`：已收到的利息现金。
- `bank:interest-expense`：已支付的存款利息现金。

银行资本、存款债务、贷款应收和坏账准备属于银行业务子账，不作为第二份可消费余额重复计入 Bloeco 货币账户。

### 6.1 存款

玩家存入 10,000：

```text
货币总账：玩家钱包 -10,000；bank:cash +10,000
银行子账：玩家存款负债 +10,000
货币供应：不变
```

取款执行相反变化。只有 `bank:cash` 扣除取款后仍满足准备金规则时才能提交。

### 6.2 贷款

银行贷出 8,000：

```text
货币总账：bank:cash -8,000；玩家钱包 +8,000
银行子账：贷款应收 +8,000
负债登记：玩家 BANK_LOAN +8,000
货币供应：不变
```

银行只能贷出真实拥有且未被准备金约束的现金。放款不能调用货币发行接口。

### 6.3 利息与坏账

应计存款利息先增加银行负债，实际支付时才从银行现金转入玩家钱包。应计贷款利息先增加贷款应收，玩家还款时才发生资金转移。

当前贷款合同默认采用 3.2% 年化固定利率和 7 天期限。贷款利息按以下整数公式计算，并在正数计息贷款上保证最低一个最小货币单位：

```text
interestMinor = max(1, principalMinor * annualBasisPoints * termDays / 3,650,000)
```

GUI 在预设金额按钮上同时显示年化利率、期限、预计利息和预计应还金额。每笔贷款保存发放时的利率；管理员修改银行政策只影响后续新贷款。

坏账核销减少贷款应收并冲减银行权益或坏账准备，不产生退款、不补充银行现金，也不改变货币供应。财政注资必须是 Treasury 到 `bank:cash` 的真实划拨并接受审计。

## 7. 货币统计口径

经济信息必须同时显示不同口径，防止存款被错误视为货币消失或重复增发：

- `BaseSupply`：累计发行减累计回收。
- `WalletMoney`：玩家可消费钱包合计。
- `BankCash`：银行持有的真实货币。
- `Deposits`：银行对玩家的可赎回负债。
- `BroadPlayerMoney`：`WalletMoney + Deposits`。

`BaseSupply` 的账户分类合计必须守恒。`Deposits` 是对银行现金及贷款资产的索取权，不再次加入基础货币合计。

## 8. 银行政策与风险限制

Bloeco 1.3 的贷款额度取以下上限的最小值：

```text
银行可贷现金上限
单玩家贷款上限
```

当前信用等级是玩家提示与后续风控扩展的基础：A 为无未结清贷款，B 为正常还款中且低于额度 80%，C 为贷款余额达到单人额度 80%，D 为存在逾期贷款。近 30 日收入、净资产负债率和抵押物估值尚未进入当前授信计算，不得在 GUI 或 API 中宣称已经启用。

准备金约束：

```text
bank:cash >= withdrawableDeposits * reserveRatio
```

每次放款和取款都在锁定银行行记录及相关余额后重新计算，GUI 中显示的额度只是快照，不能作为提交依据。

中央货币发行权限、财政权限与银行经营权限分离。新增 `bloeco.role.banker`，无权发行货币或修改税率。

## 9. 数据库表

Bloeco 1.3 已实现的银行表：

```text
banks
bank_deposits
bank_loans
bank_loan_payments
bank_operations
```

所有金额使用 `BIGINT` 最小货币单位，利率使用整数基点。银行、存款和贷款保存状态与时间字段；`bank_operations.idempotency_key` 唯一约束防止重复执行。贷款放款、还款等资金操作保存对应的永久 `journal_id`。

迁移采用递增版本、只前进、可重复执行的显式 Migration。启动时先完成结构迁移，再开放 API 和 GUI。迁移失败时插件拒绝写入，不能自动回退 SQLite。

## 10. Native API

当前银行能力只由 Bloeco 内部的异步 `AsyncBankingFacade` 和 `/eco` GUI 使用，不对第三方插件开放。未来发布独立、版本化的 API artifact 后，再通过 Paper `ServicesManager` 注册以下边界：

```text
BloecoApi
├── economy()
├── institutions()
├── settlement()
├── banking()
└── adminReadOnly()
```

所有写请求包含机构身份、业务类型、业务引用、中文显示说明和稳定幂等键。API 返回 `CompletionStage<Result<T>>`；超时代表结果未知，不代表失败。

外部插件无权提交任意 Posting。它们只能调用经过验证的结算动作，Bloeco 根据账户所有权、机构能力、税费规则和余额生成分录。

## 11. Redis 与 Outbox

MySQL 始终是唯一权威。Redis 仅保存可重建余额、银行概览、净资产读模型和事件流。

Outbox 与业务事务一起写入 MySQL。Publisher 对 Redis Stream 提供至少一次投递，消费者必须按 `event_id` 去重。Publisher 的失败次数、最后错误和积压量需要可观测，不能静默吞掉异常。

当前 MySQL Outbox 对全部资金业务统一发布：

```text
JOURNAL_COMMITTED
```

事件载荷包含永久 `journal_id` 和 `JournalType`，银行业务可由 `BANK_CAPITAL_INJECTION`、`BANK_DEPOSIT`、`BANK_WITHDRAWAL`、`LOAN_DISBURSEMENT`、`LOAN_REPAYMENT` 区分。更细的业务事件尚未成为对外兼容合同。

## 12. GUI

仍只保留 `/eco` 和 `/pay` 两个公开命令，不新增 `/bank` 或银行管理指令。所有银行操作都从 `/eco` 进入：

```text
/eco
└── Bloeco 经济中心
    ├── 国有银行
    │   ├── 我的银行账户（钱包、存款、贷款、利率、信用等级）
    │   ├── 存入银行（预设、自定义、全部存入）
    │   ├── 取出存款（预设、自定义、全部取出）
    │   ├── 申请贷款
    │   ├── 偿还贷款
    └── 中央银行管理
        └── 银行管理
            ├── 银行资产负债表
            ├── 存款利率
            ├── 贷款年化利率
            ├── 准备金率
            ├── 放贷开关
            └── 单人贷款上限
```

“银行”对普通玩家开放；“管理员中心”和“银行管理”仅向具有相应权限的玩家显示。银行经营权限使用 `bloeco.role.banker`，审计入口使用 `bloeco.role.auditor`。控制台不承载银行日常操作。

所有银行子页面固定提供“返回上一级”和“主菜单”；关闭按钮只关闭界面，不能代替导航按钮。

玩家银行页面包括：账户详情、存入、取出、贷款和还款。转账、存款、取款和贷款支持 100/1000/10000 预设金额与聊天栏自定义金额；自定义输入最多两位小数、60 秒超时，并支持输入“取消”返回。存取款还提供按事务实时余额执行的“全部存入”和“全部取出”。信用等级及 A–D 评级标准只在账户详情页展示。银行管理员页面包括：资产负债表、存款利率、贷款年化利率、准备金率、单人贷款上限和放贷开关。

金额输入优先使用 GUI 预设与聊天输入会话；聊天输入必须有超时和取消。余额、利率和额度展示是读取快照，最终结果以提交回执为准。

## 13. 完整性与失败保护

除现有日记账平衡和余额重建外，新增：

```text
存款负债合计 = 所有有效 bank_deposits
贷款资产合计 = ACTIVE/OVERDUE 贷款未偿本金与应计利息
统一负债记录 = 银行贷款业务记录
reserved_minor = ACTIVE fund_reservations 合计
银行现金和准备金规则满足当前政策
银行资产 - 银行负债 = 银行权益
```

任一权威校验失败，Bloeco 进入全局只读保护。查询、审计和导出继续开放；支付、存取、放款、还款及外部机构结算全部停止。

## 14. 测试与验收

必须覆盖：

- 多连接并发扣款、双花和固定锁顺序。
- 同幂等键并发提交只产生一个结果。
- 贷款记录、分录、余额、负债和 Outbox 的原子提交与回滚。
- 存款、取款、还款和坏账的资金守恒。
- MySQL 断连、死锁重试、提交结果未知和重连恢复。
- Redis 断连不影响 MySQL 权威写入，恢复后 Outbox 可追平。
- Paper 主线程不执行 JDBC，不等待数据库 Future。
- 全量迁移、重启恢复和只读保护。

每项资金用例都必须证明：成功时全部事实存在，失败时全部事实不存在，不允许半笔银行业务。

## 15. 实施阶段

### Phase A：事务内核（已完成当前实现）

引入 `MySqlTransactionManager`，按事务获取连接；拆分核心 Repository；保持现有余额、支付、税收、发行和 GUI 行为不变。

### Phase B：机构结算（待独立 API 版本）

落地 Institution、Reservation、Settlement、幂等查询和第一版 Native API。

### Phase C：存款银行（已完成）

建立国有银行、真实资本注入、存款和取款，并增加基础货币与广义玩家货币统计。

### Phase D：贷款与负债（贷款、还款和逾期识别已完成）

增加信用等级、贷款、还款、年化固定利率和逾期识别。自动违约、坏账核销和统一 Liability Registry 留待后续版本。

### Phase E：经济中心 GUI（已完成当前功能）

扩展 `/eco`，增加玩家银行和银行管理员界面。

### Phase F：资产扩展（未开始）

银行稳定后再设计 Asset Provider、净资产读模型和抵押系统；不与第一版银行并行开发。

## 16. 发布策略

Phase A 和 Phase B 属于兼容性重构，不修改现有货币或玩家余额。每个数据库版本都必须先在复制数据上完成迁移、完整性校验和回滚演练。

银行首次初始化只有在数据库迁移、初始资本真实划拨和完整性检查成功后才开放 GUI 写操作；任一步失败均保持不可用或只读，不得用 SQLite、Redis 或新增发行作为兜底。
