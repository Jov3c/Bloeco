# Bloeco

Bloeco 是 Paper `1.21.11` 的反通胀经济内核。它提供唯一的货币账本、Vault 余额服务、玩家转账、税收、国库和货币供给控制；它**不提供商店、收购、商品价格、库存或物品交付功能**。

货币以整数分保存在 SQLite 中，所有 Bloeco 自身资金操作均通过原子账本结算。

## 功能

- **余额管理**：玩家余额与国库余额由同一 SQLite 账本维护。
- **Vault 服务**：对 QuickShop、商店、任务和证券等独立插件提供标准 `Economy` 接口。
- **GUI 转账**：`/bloeco` 选择在线收款人和金额；付款方手续费、收款方个人所得税自动计入国库。
- **税务管理**：税务管理员在 GUI 内调整转账手续费和个人所得税，调整立即生效并写入配置。
- **货币供给**：税务管理员在有确认页的 GUI 内发行、回收货币；每笔操作均记录到不可变账本。
- **证券准备金**：仅从已经发行的国库余额划拨给 Bloeco-Stock，绝不以准备金名义增发。

## 使用

`/bloeco` 打开经济中心。普通玩家可以查看余额和转账；税务管理员会额外看到税务与国库面板。

兼容命令 `/pay <在线玩家> <金额>` 仍可使用，但日常操作建议使用 GUI。

管理员由权限节点与 UUID 白名单的并集决定：

```yml
tax-administrators:
  permission: "bloeco.tax-admin"
  player-uuids:
    - "玩家 UUID"
```

## 反通胀原则

1. 玩家转账不会产生新货币，手续费与所得税回流国库。
2. 只有税务管理员能通过国库 GUI 发行货币；发行与回收均有对应账本记录。
3. 不允许其他插件直接访问 `plugins/Bloeco/economy.db`。
4. 服务器只能启用一个 Vault `Economy` 提供者；部署 Bloeco 时禁用 EssentialsX Economy 等竞争提供者。

## 给商店、任务和其他插件开发者

Bloeco 是经济底座，不是商店框架。请阅读 [第三方经济接入规范](docs/third-party-economy-integration.md)。核心要求是：只使用 Vault，使用两位小数金额，外部扣款结果不确定时失败关闭，绝不直接写 Bloeco SQLite。

Bloeco-Stock 集成规则见 [证券集成说明](docs/bloeco-stock-integration.md)。

## 迁移与构建

旧 `plugins/CentralEconomy` 数据目录会在 `plugins/Bloeco` 不存在时自动迁移，绝不会覆盖新目录。

使用 Java 21 执行 `./gradlew shadowJar`；成品为 `build/libs/Bloeco-<version>.jar`。SQLite 驱动已内置，Paper 与 Vault 由服务器提供。
