# Bloeco 第三方经济接入技术规范

状态：Bloeco V2 接入基线（API 仍以独立版本发布）。当前插件源码提供中央账本实现；外部插件应按本规范编写适配层，不能依赖内部实现类。

本规范适用于所有以 Bloeco 为底座的商店、任务、拍卖、证券、领地和小游戏插件。Bloeco 是整个插件系列唯一的货币、账户、结算与账单权威；接入插件保存自己的商品、订单、库存和业务状态，但不得保存另一份可消费玩家余额。

## 1. 强制边界

1. 禁止读取或写入 Bloeco 的 MySQL、SQLite 表或 `plugins/Bloeco/economy.db`，禁止依赖任何内部表结构。
2. 禁止使用反射调用 Bloeco 内部类，禁止把 Vault 存取款当作 Bloeco 接口。
3. 金额使用整数最小单位 `long`；显示层可以使用十进制字符串，但不得把 `double` 传入结算层。
4. 每次会产生资金变化的请求必须带稳定的 `clientId` 与 `idempotencyKey`。
5. 只有 Bloeco 返回已提交凭证后，业务插件才能把订单标为已付款或交付不可恢复物品。
6. 超时、断线、未知异常属于“结果未知”，不能换一个幂等键自动重试；应使用原键查询结果或进入人工恢复。

## 2. 机构注册与专属账本

未来 API 会要求插件注册稳定机构标识，例如 `example.shop`。Bloeco 为它建立 `INSTITUTION` 级主账户，并允许按用途申请子账户，例如销售收入、退款准备金、平台费用和托管款。账户由 Bloeco 创建并持有，插件只能通过被授权的业务操作使用。

机构注册至少包含：

- 永久不变的 `clientId`；
- 插件名称与版本；
- 所需能力，例如收款、退款、托管或只读查询；
- 税务类别；
- 数据迁移版本。

卸载插件不会删除机构账户和历史分录。重新安装时必须使用同一 `clientId` 恢复关联。

## 3. 计划中的原生操作

API 将采用异步返回，所有成功结果包含永久 `journalId`：

```java
CompletionStage<EconomyResult<InstitutionHandle>> register(InstitutionRegistration request);
CompletionStage<EconomyResult<MoneyBalance>> balance(AccountRef account);
CompletionStage<EconomyResult<SettlementReceipt>> settle(SettlementRequest request);
CompletionStage<EconomyResult<SettlementReceipt>> refund(RefundRequest request);
CompletionStage<EconomyResult<JournalView>> findByIdempotency(String clientId, String idempotencyKey);
CompletionStage<EconomyResult<List<JournalView>>> recentJournal(AccountRef account, int limit);
```

`SettlementRequest` 至少应包含付款账户、收款机构、税务类别、本金、业务摘要、订单引用、幂等键。Bloeco 会在一个数据库事务中计算并记入本金、消费税、平台费或其他中央规则；插件不能自行伪造税收分录。

### 3.1 账单类型与中文说明

接入请求必须把机器字段和玩家展示文本分开：

- `businessType`：稳定的 ASCII 业务类型，例如 `shop.purchase`、`shop.sale`、`stock.buy`、`quest.reward`。它用于程序判断，发布后不得随显示文案改变。
- `businessReference`：插件内部订单号、成交号或任务发放号。它用于审计和退款关联，不直接显示给普通玩家。
- `displayMemo`：写入 Bloeco 永久账单的中文说明，UTF-8 纯文本，必填，建议 1 至 80 个字符，硬上限 256 个字符。不能包含 MiniMessage/颜色控制符、密钥、完整堆栈、幂等键或敏感数据。
- `idempotencyKey`：稳定的重试身份，只用于防止重复扣款，不能当作账单说明。

Bloeco 按提交时的 `displayMemo` 保存快照，不会猜测翻译第三方插件的任意说明。玩家名、商品名、证券名等专有名称可以保留原文，但动作与业务含义应使用中文完整表达。

推荐示例：

| 业务类型 | `displayMemo` |
| --- | --- |
| `shop.purchase` | `在 Bloeco 商店购买 16 个钻石` |
| `shop.sale` | `向 Bloeco 商店出售 64 个小麦` |
| `shop.refund` | `Bloeco 商店订单退款` |
| `stock.buy` | `买入 10 股矿业指数` |
| `stock.sell` | `卖出 10 股矿业指数` |
| `quest.reward` | `完成“初来乍到”任务奖励` |

计划中的请求形态：

```java
new SettlementRequest(
        payerAccount,
        institutionAccount,
        TaxCategory.CONSUMPTION,
        amountMinor,
        "shop.purchase",
        "在 Bloeco 商店购买 16 个钻石",
        "order-20260908-0001",
        "shop:order:20260908-0001:payment"
);
```

## 4. 商店推荐状态机

```text
CREATED → VALIDATED → SETTLEMENT_PENDING → PAID → DELIVERED
                                └──────────→ RECOVERY_REQUIRED
```

推荐流程：

1. 商店数据库创建订单和不可变订单金额，生成稳定幂等键。
2. 校验商品、库存、玩家资格和交付空间。
3. 提交 Bloeco 原子结算；在结果确定前不交付。
4. 收到成功凭证后保存 `journalId`，再提交订单付款状态和物品交付。
5. 若交付失败，使用引用原凭证的退款/补偿操作；不能直接给玩家增加余额。

## 5. 错误处理合同

稳定错误至少包括：无效金额、余额不足、账户冻结、未授权、政策拒绝、幂等冲突、完整性只读、存储不可用和内部错误。

- 无效金额、未授权、政策拒绝：修正请求后使用新的业务操作与新幂等键。
- 余额不足：订单保持未付款，不交付。
- 幂等冲突：同一键对应了不同内容，必须人工或业务层修复，禁止覆盖。
- 完整性只读、存储不可用、内部错误：停止涉及资金的业务，不使用本地备用余额。
- 超时或连接中断：用原 `clientId + idempotencyKey` 查询，不盲目重复结算。

## 6. 税收、退款与货币供给

机构收款和退款都是已有账户间的平衡分录，不得改变全服货币供给。第三方插件没有发行权限；管理员也不能借“余额设置”绕过国库。消费税等中央税种由 Bloeco 的版本化政策计算，凭证保留实际使用的政策版本。

Bloeco 的初始国库供给只在空白总账创建一次。玩家启动资金由国库划拨且每个 UUID 只能领取一次。接入插件不得把重装、重连、余额为零或订单失败当作重新发放启动资金的条件。

Bloeco 内置的国有银行属于中央经济系统内部模块。当前第三方 API 不开放替玩家存款、取款、贷款或还款的能力；业务插件不得写入 `banks`、`bank_deposits`、`bank_loans`、`bank_loan_payments` 或 `bank_operations`，也不得把银行存款余额复制成自己的可消费货币。未来若发布银行 API，仍必须遵守版本化能力授权、幂等键和资金守恒规则。

退款应引用原结算凭证。税款是否退还、手续费是否退还由原政策版本决定，不能由商店随意选择当前税率重新计算。

## 7. MySQL 与 Redis 兼容要求

Bloeco 生产环境以 MySQL 8.4/InnoDB 为唯一权威账本，Redis 7 仅用于可重建缓存、限流和 Redis Stream 事件。接入插件不应直接连接任一数据源；只面向版本化原生 API，因此不感知 Bloeco 的内部表结构。MySQL 提交是资金成功边界，Redis 丢失或断开时不得丢余额或凭证，也不得从缓存反向覆盖权威 SQL。Bloeco 使用 MySQL outbox 保障“账本已提交但 Redis 暂时不可用”时的事件补发。

## 8. 版本兼容

公开 API 将采用独立语义版本。接入插件必须声明支持的 API 主版本，忽略未知的附加响应字段，并对不支持的主版本失败关闭。Bloeco 内部 Java 包、数据库表和 GUI 均不属于兼容合同。

在正式 API 工件发布前，第三方插件应只完成自身订单、库存、恢复状态机与 Bloeco 适配器接口，不应交付任何能够直接修改 Bloeco 资金的实现。SQLite 只用于 Bloeco 本地测试或迁移，不是接入插件的生产依赖。
