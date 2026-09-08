# Bloeco 第三方经济接入技术规范

状态：Phase 2 API 合同草案。Phase 1 仅提供中央账本本体，以下接口尚未作为可编译依赖发布。

本规范适用于商店、任务、拍卖、证券、领地和小游戏。Bloeco 是唯一货币与账单权威；接入插件保存自己的商品、订单、库存和业务状态，但不得保存另一份可消费玩家余额。

## 1. 强制边界

1. 禁止读取或写入 `plugins/Bloeco/economy.db`，禁止依赖 Bloeco 的 SQLite 表结构。
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

退款应引用原结算凭证。税款是否退还、手续费是否退还由原政策版本决定，不能由商店随意选择当前税率重新计算。

## 7. MySQL 与 Redis 兼容要求

接入插件只面向原生 API，因此不感知 Bloeco 使用 SQLite 还是 MySQL。Bloeco 的 SQL 提交是成功边界。Redis 未来只用于缓存失效、跨服事件和可重建读模型；Redis 丢失时不得丢余额或凭证，也不得从缓存反向覆盖权威 SQL。

## 8. 版本兼容

公开 API 将采用独立语义版本。接入插件必须声明支持的 API 主版本，忽略未知的附加响应字段，并对不支持的主版本失败关闭。Bloeco 内部 Java 包、数据库表和 GUI 均不属于兼容合同。

在 Phase 2 API 发布前，第三方插件应只完成自身订单、库存、恢复状态机与 Bloeco 适配器接口，不应交付任何能够直接修改 Bloeco 资金的实现。
