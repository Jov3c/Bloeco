# Bloeco Native Economy API 设计

状态：已确认的架构基线  
目标版本：Bloeco 1.5  
API 首个版本：1.0

## 1. 产品定位

Bloeco Native Economy API 是 Bloeco 系列插件统一使用的经济接口标准。它在系列生态中的作用类似 Vault 的 Economy API：业务插件只依赖稳定接口，不依赖经济实现的内部类、数据库或消息系统。

Bloeco 主插件是当前唯一的 API Provider，负责真实余额、机构账户、结算、税费、资金预留、永久凭证和货币供应纪律。商店、领地、任务、证券、拍卖和小游戏插件是 API Consumer，只保存各自的订单、商品、领地、任务或持仓状态。

“兼容 Bloeco”表示插件只通过公开 API 完成资金操作，并遵守 API 的幂等、结果未知和资金来源规则。Vault 兼容不属于 1.0 范围。

## 2. 构建与发布结构

Bloeco 保持单一 Gradle 工程和单一正式插件 JAR，API 接口直接内嵌在 Bloeco 插件中，不额外发布 `bloeco-api.jar`：

```text
Bloeco.jar
├── com.blocke.bloeco.api           稳定接口、请求、结果、回执和版本声明
└── com.blocke.centraleconomy       Provider 实现、存储、GUI 和运维功能
```

其他 Bloeco 系列插件在编译期以 `compileOnly` 方式引用 Bloeco 主插件 JAR，并在 `plugin.yml` 中声明 `depend: [Bloeco]`。Consumer 不得把 Bloeco JAR 或 `com.blocke.bloeco.api` 类重新打入自己的成品，避免同名 API 类由两个插件类加载器加载后无法通过 `ServicesManager` 匹配。

运行时仍只安装一个 Bloeco 主插件。Consumer 通过 Paper `ServicesManager` 获取 Provider，不直接实例化 API 实现。API 使用独立语义版本；主插件版本变化不自动意味着 API 主版本变化。

公开包固定在 `com.blocke.bloeco.api`。公开兼容面只能引用 Java 标准库、Paper API 和同一公开包中的类型，不能泄露 `com.blocke.centraleconomy` 类型。`com.blocke.centraleconomy`、数据库表、Redis Stream 和 GUI 类均不是兼容合同。

## 3. Provider 发现与绑定

Bloeco 启动并完成数据库迁移、完整性校验后注册：

```java
BloecoApi api = Bukkit.getServicesManager().load(BloecoApi.class);
```

Consumer 在自己的 `onEnable` 中检查：

1. API 是否存在；
2. API 主版本是否受支持；
3. 所需能力是否可用；
4. 机构绑定是否成功。

绑定入口接收真实 Paper `Plugin` 实例和机构描述，返回与该插件绑定的 `InstitutionClient`。后续请求不再允许调用方自由填写其他机构 ID。

```java
CompletionStage<EconomyResult<InstitutionClient>> connect(
        Plugin plugin,
        InstitutionDescriptor descriptor);
```

机构稳定标识由插件声明的永久命名空间生成，例如 `bloeco.shop`、`bloeco.land`。显示名称和插件版本可以变化，命名空间不得随版本变化。卸载插件不会删除机构账户或历史凭证。

## 4. 两层接口

### 4.1 通用经济层

用于大多数商店、领地和任务插件，保持接入体验接近主流经济 API：

```text
walletBalance(playerId)
institutionBalance(account)
chargePlayer(playerId, account, amount, context)
payPlayer(account, playerId, amount, context)
refund(originalReceipt, amount, context)
findOperation(idempotencyKey)
recentTransactions(account, limit)
```

`chargePlayer` 把已有货币从玩家转入调用机构账户；`payPlayer` 从调用机构账户向玩家付款。API 不提供无资金来源的 `depositPlayer`、`give` 或 `setBalance`，以免业务插件绕过货币供应纪律。

### 4.2 高级结算层

用于证券订单、拍卖竞价和需要延迟交付的业务：

```text
reservePlayerFunds(playerId, amount, expiresAt, context)
captureReservation(reservation, account, context)
releaseReservation(reservation, context)
reservation(reservationId)
```

冻结金额仍属于玩家总余额，但不计入可用余额。消费预留、释放和到期只能发生一次，并与余额版本、永久凭证、结算记录和 Outbox 在同一事务内提交。

## 5. 请求上下文与中文账单

每次写操作必须携带：

- `businessType`：稳定的 ASCII 机器业务类型；
- `businessReference`：调用插件内部订单或业务编号；
- `displayMemo`：玩家可读的中文说明快照；
- `idempotencyKey`：该机构内永久稳定的重试标识。

金额使用 `long` 最小货币单位或 API 的不可变 `MoneyAmount`，禁止 `double`。API 对空说明、超长文本、格式控制符、无效金额和不安全标识失败关闭。

## 6. 机构账户与能力

机构首次绑定时由 Bloeco 创建默认 `operating` 账户。机构可按声明申请有限用途账户，例如：

- `sales`：销售收入；
- `payout`：收购或奖励预算；
- `escrow`：托管资金；
- `refund`：退款准备金。

账户由 Bloeco 创建并持有。Consumer 只能引用 `InstitutionClient` 返回的 Account Handle，不能拼接账户 ID。

机构能力采用显式授权：

- `CHARGE_PLAYER`
- `PAY_PLAYER`
- `REFUND`
- `RESERVE_PLAYER_FUNDS`
- `READ_OWN_BALANCE`
- `READ_OWN_JOURNAL`

第一版不向第三方开放发行、回收、任意玩家间转账、银行贷款、税率修改、任意 Posting 或其他机构账户访问。

## 7. 典型资金流

商店出售商品给玩家：

```text
玩家钱包 -> 商店 sales 账户 + 中央税费账户
```

商店收购物品：

```text
商店 payout 账户 -> 玩家钱包
```

领地收费：

```text
玩家钱包 -> 领地 operating 账户 + 中央税费账户
```

任务奖励：

```text
任务 payout 账户 -> 玩家钱包
```

任务插件余额不足时奖励失败，不能通过 API 创造货币。管理员如需提供任务预算，必须使用 Bloeco 的国库拨款流程。

证券或拍卖下单：

```text
预留玩家资金 -> 成交时捕获到机构 escrow -> 结算或退款
```

## 8. 税费与退款

业务插件只声明稳定税务场景，不提交税额。Bloeco 使用结算发生时有效的中央政策计算消费税、机构收入税或平台费用，并在回执中返回本金、税费、机构净额和政策版本。

退款必须引用原始已提交结算。可退金额不得超过剩余可退本金；税费是否退回由原政策快照决定。并发重复退款和超额退款失败关闭。

## 9. 幂等与失败语义

同一机构的同一幂等键：

- 请求内容相同：返回原回执；
- 请求内容不同：返回 `IDEMPOTENCY_CONFLICT`；
- 提交过程中超时或连接断开：返回结果未知，调用方必须使用原键查询或重试。

稳定错误至少包括：

- `INVALID_REQUEST`
- `INVALID_AMOUNT`
- `ACCOUNT_NOT_FOUND`
- `ACCOUNT_FROZEN`
- `INSUFFICIENT_FUNDS`
- `UNAUTHORIZED`
- `CAPABILITY_DENIED`
- `POLICY_REJECTED`
- `IDEMPOTENCY_CONFLICT`
- `INTEGRITY_READ_ONLY`
- `STORAGE_UNAVAILABLE`
- `RESULT_UNKNOWN`
- `INTERNAL_ERROR`

业务插件只有收到 `COMMITTED` 回执后才能交付不可恢复物品、建立领地或确认证券成交。

## 10. 数据与事务

MySQL 8.4/InnoDB 继续作为生产权威。新增 V007 迁移，不修改 V001-V006：

- 为机构保存能力、API 版本和绑定元数据；
- 为余额增加并校验 `reserved_minor`；
- 补齐结算方向、金额、税费、政策快照和原交易引用；
- 补齐预留到期、消费凭证、释放原因和请求摘要；
- 增加退款、幂等查询、到期扫描和机构账单索引。

一个 API 写操作只使用一个 `MySqlTransactionManager` 事务。账户按 ID 固定顺序一次性 `FOR UPDATE`；余额、冻结、分录、结算、审计和 Outbox 要么全部提交，要么全部回滚。

SQLite 实现同一应用合同，用于本地开发、单元测试和迁移验证，不作为生产自动降级。

Redis 仍只负责可重建缓存和提交后通知。Redis 不可用不能改变 MySQL 结算结果。

## 11. 生命周期

Bloeco 只有在以下条件全部满足后才注册 Provider：

- 配置校验通过；
- 数据库迁移完成；
- 启动完整性校验通过；
- 异步工作队列可用。

进入完整性只读状态后，查询继续可用，所有 API 写操作返回 `INTEGRITY_READ_ONLY`。插件停用时先从 `ServicesManager` 注销，再拒绝新请求，排空或安全终止有界队列，最后关闭数据库连接池。

## 12. Consumer 接入示例

Consumer 构建配置示例：

```kotlin
dependencies {
    compileOnly(files("libs/Bloeco.jar"))
}
```

Consumer 的 `plugin.yml`：

```yaml
depend: [Bloeco]
```

禁止使用 `implementation(files("libs/Bloeco.jar"))`，也禁止通过 Shadow 将 Bloeco API 类复制进 Consumer 成品。

```java
BloecoApi api = Bukkit.getServicesManager().load(BloecoApi.class);
if (api == null || !api.version().supportsMajor(1)) {
    getLogger().severe("Bloeco API 1.x 不可用");
    getServer().getPluginManager().disablePlugin(this);
    return;
}

api.connect(this, InstitutionDescriptor.of("bloeco.shop", "Bloeco 商店"))
        .thenAccept(result -> {
            if (result.isSuccess()) {
                this.economy = result.value();
            }
        });
```

实际回调线程不保证为 Paper 主线程。Consumer 在操作 Bukkit 实体、物品栏、玩家消息或世界状态前必须切回自己的 Paper 调度器。

## 13. 测试与验收

必须覆盖：

- API Consumer 不引用插件内部包也能编译；
- Provider 只在 Runtime 完全就绪后注册，并在停用时注销；
- 机构重复注册恢复同一账户，不重复创建；
- 无权限机构不能使用未授权能力或其他机构账户；
- 玩家扣款、机构付款、退款和资金预留保持货币守恒；
- 同幂等键并发请求只产生一个永久结果；
- 反向账户操作不死锁、不双花；
- 余额不足、税费失败、Outbox 写入失败全部回滚；
- Redis 断线不影响 MySQL 权威提交；
- 超时后用原幂等键可以恢复确定结果；
- Paper 主线程不执行 JDBC 或等待 Future；
- SQLite 合同测试与 MySQL 集成测试使用同一行为测试集。

## 14. 不在 API 1.0 范围内

- 独立 API JAR 或独立 API Maven 模块；
- Vault 或 VaultUnlocked 兼容；
- 多货币；
- 任意余额设置或无来源入账；
- 第三方银行存贷款；
- 跨进程 HTTP API；
- 商店、领地、任务、证券等业务本身；
- 允许 Consumer 直接订阅 Redis 作为结算依据。
