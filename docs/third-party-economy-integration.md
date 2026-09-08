# Bloeco 第三方经济接入规范

本规范适用于商店、任务、拍卖、证券、领地、小游戏等需要收付玩家货币的插件。

## 唯一边界：Vault

Bloeco 不提供商品或业务 API。第三方插件必须经 Vault 的 `Economy` 服务操作玩家外部钱包，禁止读取、写入、复制或迁移 `plugins/Bloeco/economy.db`。

```java
RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager()
        .getRegistration(Economy.class);
if (registration == null || !registration.getProvider().isEnabled()) {
    // 停止本次涉及资金的业务；不要使用本地余额替代。
    return;
}
Economy economy = registration.getProvider();
```

启用阶段和每次不可逆结算前都应重新确认 provider 可用。服务器必须仅启用一个 Vault Economy provider，Bloeco 应是该 provider。

## 金额规则

- 仅传入有限、非负且最多两位小数的金额。
- 推荐业务内部使用 `BigDecimal` 或整数分，避免 `double` 累积误差。
- 在调用 `withdrawPlayer` 前先验证订单、库存、权限和余额。
- 调用成功后才能持久化不可逆的订单完成、物品交付或业务余额贷记。
- Vault 返回失败、异常或结果不确定时，必须失败关闭；不要自动重试可能已经到达 provider 的扣款或入账。

## 推荐的商店结算顺序

1. 在商店自己的数据库中创建可恢复的待结算订单。
2. 校验库存和玩家背包空间，但不要交付物品。
3. 调用 Vault `withdrawPlayer`，并检查响应类型为 `SUCCESS` 且金额正确。
4. 持久化订单已付款状态，交付物品。
5. 若第 3 步结果不确定，冻结订单并让管理员处理；不得悄悄重试或发货。

商店的商品、库存、订单和物品托管必须由商店插件自己维护，Bloeco 不保存这些数据。

## 税收与货币供给

Bloeco 当前统一自动结算玩家转账手续费与个人所得税。商店插件不得修改 Bloeco 税率、伪造 Bloeco 账本行或自行发行玩家货币。

如商店需要消费税、平台费或折扣，应在自身订单中明确展示并结算；若要成为全服统一税种，先扩展 Bloeco 并新增审计与回归测试。不要将商店私有费用伪装为中央税。

货币发行和回收只能由 Bloeco 税务管理员在国库 GUI 中执行。第三方插件不得把 Vault `depositPlayer` 当成常规货币发行机制。

## Bloeco-Stock

Bloeco-Stock 仅经 Vault 操作玩家钱包，内部证券现金、公司和持仓由它自己维护。蓝筹准备金只能由 Bloeco 从已发行的国库余额划拨；详见 [Bloeco-Stock 集成说明](bloeco-stock-integration.md)。
