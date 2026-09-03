# Bloeco

Bloeco 是 Paper `1.21.11` 的中央经济核心：一个 SQLite 总账本、一个 Vault 货币提供者，以及不依赖第三方商店插件的 GUI 交易系统。金额以分为单位保存，避免浮点误差。

## 玩家使用

输入 `/bloeco`（`/economy` 仍作为兼容别名）即可打开经济中心：

- **国库收购**：按收购表出售背包中的普通物资，收购所得税自动回流国库。
- **玩家市场**：浏览、购买、上架和撤销物资都在 GUI 中完成；买方消费税与市场手续费自动进入国库。
- **转账**：从在线玩家列表选择收款人，再点选金额；付款方承担手续费，收款方的所得税自动结算并显示明细。

`/pay` 与 `/market` 保留为旧服兼容入口，但日常操作应使用 Bloeco GUI。

## 税务与国库管理

税务管理员在 `/bloeco` 中会看到 **税务与国库管理** 面板，无须常规管理指令：

- 左键、右键、Shift 点击分别使用面板显示的金额档位发行、回收或划拨 Bloeco-Stock 准备金；每笔账自动写入操作者和操作类型。
- 税率面板可直接调整收购所得税、转账手续费、转账个人所得税和市场消费税；改动立即生效并保存到 `plugins/Bloeco/config.yml`。

管理员由配置决定，支持权限节点和 UUID 白名单的并集：

```yml
tax-administrators:
  permission: "bloeco.tax-admin"
  player-uuids:
    - "玩家 UUID"
```

默认 `bloeco.tax-admin` 仅赋予 OP。将 `permission` 改成任意权限插件中的节点，或填入 UUID，即可自定义税务管理员。

## Vault 与 Bloeco-Stock

Vault 是可选依赖。检测到 Vault 后，Bloeco 会以最高优先级注册唯一的 `Economy` 提供者；不要同时启用 EssentialsX Economy 等竞争提供者。Bloeco-Stock 仅通过 Vault 调用玩家钱包，绝不读取 Bloeco 的 SQLite 文件。

安装顺序为：`Vault` → `Bloeco` → `Bloeco-Stock`。首次蓝筹开市前，在 Bloeco 的税务与国库面板中从**已经发行的国库余额**划拨足额证券准备金（默认总额 `1,150,000.00`）。这只是资金位置变更，不会增发。详见 [Bloeco-Stock 集成契约](docs/bloeco-stock-integration.md)。

旧版 `plugins/CentralEconomy` 数据目录会在目标 `plugins/Bloeco` 不存在时自动迁移，绝不会覆盖已有新目录。

## 构建

使用 Java 21 执行 `./gradlew shadowJar`。成品为 `build/libs/Bloeco-<version>.jar`；SQLite 驱动已内置，Paper 与 Vault 由服务器提供。
