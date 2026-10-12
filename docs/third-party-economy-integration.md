# Bloeco 原生经济 API 1.0 接入规范

Bloeco 1.5 内嵌并通过 Paper `ServicesManager` 发布 `com.blocke.bloeco.api.BloecoApi`。商店、证券、领地、拍卖、任务等插件只保存自己的业务数据；玩家余额、机构账户、税费、冻结资金、退款和永久账单全部由 Bloeco 管理。

## 依赖与发现

消费者的 `plugin.yml` 必须声明：

```yaml
depend: [Bloeco]
```

开发时把 Bloeco 成品 JAR 作为 `compileOnly`，不要把 `com.blocke.bloeco.api` 复制或 shade 到消费者 JAR，否则 Paper 会产生类加载器不一致。API 与实现位于同一个 Bloeco JAR，不需要独立前置：

```kotlin
dependencies {
    compileOnly(files("libs/Bloeco-1.5.0-SNAPSHOT.jar"))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}
```

启动后发现并检查主版本：

```java
BloecoApi api = getServer().getServicesManager().load(BloecoApi.class);
if (api == null || !api.version().supportsMajor(1)) {
    getLogger().severe("需要 Bloeco API 1.x");
    getServer().getPluginManager().disablePlugin(this);
    return;
}
```

Bloeco 只有在权威数据库和完整性检查就绪后才注册服务，停服时先注销服务再关闭队列和数据库。消费者不得缓存跨重启的 Provider 或账户对象。

## 注册机构

使用调用插件的真实 `Plugin` 实例连接。`clientId` 发布后永久不变；重新安装或升级使用同一标识恢复专属账本：

```java
InstitutionDescriptor descriptor = new InstitutionDescriptor(
    "example.shop",
    "示例商店",
    Set.of(InstitutionCapability.CHARGE_PLAYER,
           InstitutionCapability.PAY_PLAYER,
           InstitutionCapability.REFUND,
           InstitutionCapability.RESERVE_PLAYER_FUNDS,
           InstitutionCapability.READ_OWN_BALANCE,
           InstitutionCapability.READ_OWN_JOURNAL),
    Set.of("sales", "escrow")
);

api.connect(this, descriptor).thenAccept(result -> {
    if (result.isSuccess()) client = result.value();
});
```

Bloeco 会创建默认 `operating` 账户及声明的用途账户。账户句柄不可自行实现或伪造，不能跨机构使用。卸载业务插件不会删除账户和历史。

## 线程合同

所有 API 方法都返回 `CompletionStage<EconomyResult<T>>`，数据库工作在 Bloeco 的有界工作池执行。不要在 Paper 主线程调用 `join()`、`get()` 或忙等；在回调中只处理业务数据，任何 Bukkit 玩家、背包、世界或 GUI 操作必须切回 Paper 调度器。

队列已满、正在关闭或数据库不可用时返回 `STORAGE_UNAVAILABLE`，不会泄漏线程池异常。Bloeco 完整性只读时写操作返回 `INTEGRITY_READ_ONLY`，查询仍可用。业务插件不得启用本地备用余额。

## 收款、付款与税收

金额为 `long` 最小货币单位，两位小数货币中 `10000` 表示 `100.00`。禁止 `double`。

```java
OperationContext context = new OperationContext(
    "shop.purchase",                 // 稳定机器类型
    orderId,                         // 业务内部引用
    "在示例商店购买 16 个钻石",       // 玩家可读中文账单说明
    "order:" + orderId + ":payment" // 永久稳定幂等键
);

client.chargePlayer(playerId, client.account("sales").orElseThrow(),
    MoneyAmount.positive(6400), SettlementKind.PURCHASE, context);
```

`PURCHASE` 和 `SERVICE_FEE` 使用 Bloeco 当前的版本化消费税，默认 5%，税额在本金之外从玩家钱包扣除。`PAYOUT` 和 `REWARD` 只能从机构已有资金付款，余额不足即失败；API 没有 `give`、`depositPlayer` 或 `setBalance`，业务插件不能凭空发钱。

只有收到 `SettlementReceipt.Status.COMMITTED` 后，商店才能把订单标记已付款并交付不可恢复物品。凭证包含永久 `operationId`、`journalId`、本金、税、费、机构净额和说明快照。

## 幂等、未知结果与退款

同一机构内的 `idempotencyKey` 永久唯一：同键同请求返回原凭证，同键不同内容返回 `IDEMPOTENCY_CONFLICT`。超时或回调丢失时，用原键调用 `findOperation` 或原样重试；绝不能生成新键“再试一次”。

退款引用原收款的 `operationId`：

```java
client.refund(originalOperationId, MoneyAmount.positive(refundMinor),
    new OperationContext("shop.refund", orderId, "示例商店订单退款",
        "order:" + orderId + ":refund:1"));
```

允许分批退款但累计不能超过原本金。退税使用原交易保存的税率快照，最后一笔退款吸收舍入差额；不能用当前税率重算。手续费是否返还由 Bloeco 政策决定。

## 冻结资金

拍卖、证券委托等延迟成交业务使用预授权：

1. `reservePlayerFunds` 冻结本金及中央计算的消费税；冻结金额仍属于玩家总余额，但不能被 `/pay`、银行或其他插件再次花费。
2. `captureReservation` 只允许一次，把本金转入指定机构账户并把税转入税收账户。
3. `releaseReservation`、到期和捕获互斥，只会有一个终态：`CAPTURED`、`RELEASED` 或 `EXPIRED`。

预授权、捕获和释放都要使用各自稳定的幂等键。不要先查余额再扣款；以返回结果为准。

## 错误处理

- `INVALID_REQUEST` / `INVALID_AMOUNT`：修正业务请求后使用新的业务操作键。
- `INSUFFICIENT_FUNDS`：订单保持未付款，不交付。
- `UNAUTHORIZED` / `CAPABILITY_DENIED`：配置或权限错误，停止该功能。
- `POLICY_REJECTED`：冻结已终结、退款超额或中央政策拒绝。
- `IDEMPOTENCY_CONFLICT`：键被不同请求复用，必须人工排查。
- `INTEGRITY_READ_ONLY` / `STORAGE_UNAVAILABLE` / `RESULT_UNKNOWN`：停止交付，按原键查询或恢复，禁止本地补余额。

## 数据与供给边界

禁止连接 Bloeco 的 MySQL、SQLite 或 Redis，禁止反射内部类。Redis 只是可重建缓存和提交后事件传输，不是余额确认依据。所有普通结算都是守恒分录；只有央行审批发行能增加供给，只有回收分录能减少供给。

完整可编译示例位于 `examples/bloeco-api-consumer`。API 使用独立语义版本；1.x 内保持二进制兼容，消费者必须对未知主版本失败关闭。
