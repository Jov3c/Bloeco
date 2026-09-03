# Bloeco × Bloeco-Stock 集成契约

状态：已在 Paper 1.21.11 本地联调验证。

## 边界

- **Bloeco** 是唯一的玩家货币、税收、国库和 Vault `Economy` 提供者；账本位于 `plugins/Bloeco/economy.db`。
- **Bloeco-Stock** 负责公司、证券现金、持仓、订单、成交与托管；它只能经 Vault 读写玩家钱包，不能读取或修改 Bloeco SQLite 文件。
- Vault 金额固定两位小数；非有限、负数或无法精确表示的金额必须失败关闭。

## 启动与准备金

1. 先备份现有经济与证券数据，保留 `Vault.jar`，禁用 EssentialsX Economy 等竞争 Vault 货币提供者。
2. 启动顺序必须是 `Vault` → `Bloeco` → `Bloeco-Stock`。Bloeco 以最高优先级注册 Vault Economy，Bloeco-Stock 只在它可用时开放交易。
3. 税务管理员从 `/bloeco` 的 **税务与国库管理** 面板发行经过批准的货币，再用 **证券准备金** 将已存在的国库余额划拨至专用 UUID `...0098`。默认蓝筹总额为 `1,150,000.00`。
4. Bloeco-Stock 会通过 Vault 将 `.98` 准备金划入自己的托管账户；只有扣款与入账两腿都得到明确确认后，才创建蓝筹流动性并开放二级交易。

准备金余额不足时，Bloeco-Stock 必须拒绝开市、不得创建市场或托管入账。外部 Vault 调用结果不确定时同样失败关闭，交由管理员人工恢复；绝不自动重试。

## 已验证的验收项

- Bloeco 成功注册 Vault 提供者，QuickShop 使用 Bloeco，且没有 EssentialsX Economy 竞争提供者。
- 未准备金时 Bloeco-Stock 立即停止自身初始化，交易不会开放。
- 国库发行并划拨 `1,150,000.00` 后，Bloeco-Stock 启动日志确认 `secondary trading=open`，`.98` 准备金被完整消费至证券托管路径。
- 两项目的旧数据目录分别可从 `CentralEconomy` 和 `BlockStock`/`BlockecoExchange` 无损迁移到新品牌目录；目标目录存在时拒绝覆盖。
